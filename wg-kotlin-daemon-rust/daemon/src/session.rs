use crate::platform::{self, CleanupHook};
use std::net::IpAddr;
use std::str::FromStr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
use tun_rs::{DeviceBuilder, InterruptEvent, SyncDevice};
use daemon_proto::pb::TunSessionConfig;

pub struct SessionManager;

#[derive(Clone)]
pub struct TunSession {
    interface_name: String,
    device: Arc<SyncDevice>,
    interrupt: Arc<InterruptEvent>,
    closed: Arc<AtomicBool>,
    cleanup_hook: Arc<StdMutex<Option<CleanupHook>>>,
}

impl SessionManager {
    pub fn start(config: &TunSessionConfig) -> Result<TunSession, String> {
        if !is_supported_interface_name(&config.interface_name) {
            return Err(format!(
                "unsupported interface name '{}': expected utun[0-9]+",
                config.interface_name
            ));
        }

        platform::prepare_session_start()
            .map_err(|error| format!("failed to prepare platform session: {error}"))?;

        let (primary_ip, prefix_len) = parse_primary_address(config)?;

        let mut builder = DeviceBuilder::new().name(config.interface_name.clone());
        builder = match primary_ip {
            IpAddr::V4(ipv4) => builder.ipv4(ipv4, prefix_len, None),
            IpAddr::V6(ipv6) => builder.ipv6(ipv6, prefix_len),
        };

        let device = builder
            .build_sync()
            .map_err(|error| format!("failed to create TUN device: {error}"))?;

        #[cfg(unix)]
        device
            .set_nonblocking(true)
            .map_err(|error| format!("failed to configure non-blocking TUN: {error}"))?;

        let interface_name = device
            .name()
            .unwrap_or_else(|_| config.interface_name.clone());

        let interrupt = InterruptEvent::new()
            .map_err(|error| format!("failed to create TUN interrupt event: {error}"))?;

        let cleanup_hook =
            platform::configure_session(config, &interface_name).map_err(|error| {
                let _ = interrupt.trigger();
                format!("failed to configure platform session: {error}")
            })?;

        Ok(TunSession {
            interface_name,
            device: Arc::new(device),
            interrupt: Arc::new(interrupt),
            closed: Arc::new(AtomicBool::new(false)),
            cleanup_hook: Arc::new(StdMutex::new(Some(cleanup_hook))),
        })
    }
}

impl TunSession {
    pub fn interface_name(&self) -> &str {
        &self.interface_name
    }

    pub fn read_packet(&self) -> std::io::Result<Vec<u8>> {
        let mut buffer = vec![0_u8; 65536];
        let bytes = self.device.recv_intr(&mut buffer, &self.interrupt)?;
        buffer.truncate(bytes);
        Ok(buffer)
    }

    pub fn write_packet(&self, packet: &[u8]) -> std::io::Result<()> {
        let _ = self.device.send_intr(packet, &self.interrupt)?;
        Ok(())
    }

    pub fn close(&self) -> Result<(), String> {
        if self.closed.swap(true, Ordering::SeqCst) {
            return Ok(());
        }
        let _ = self.interrupt.trigger();

        let cleanup = match self.cleanup_hook.lock() {
            Ok(mut guard) => guard.take(),
            Err(poisoned) => poisoned.into_inner().take(),
        };

        if let Some(cleanup) = cleanup {
            cleanup()?;
        }

        Ok(())
    }
}

fn parse_primary_address(config: &TunSessionConfig) -> Result<(IpAddr, u8), String> {
    let Some(primary_address) = config.addresses.first() else {
        return Err("session config missing primary address".to_string());
    };

    let (ip_text, prefix_text) = primary_address
        .split_once('/')
        .ok_or_else(|| format!("invalid CIDR '{}': missing '/'", primary_address))?;

    let ip = IpAddr::from_str(ip_text.trim())
        .map_err(|_| format!("invalid IP address '{}'", ip_text.trim()))?;
    let prefix = prefix_text
        .trim()
        .parse::<u8>()
        .map_err(|_| format!("invalid prefix length '{}'", prefix_text.trim()))?;

    let max_prefix = if ip.is_ipv4() { 32 } else { 128 };
    if prefix > max_prefix {
        return Err(format!(
            "invalid prefix length '{}': expected 0..={max_prefix}",
            prefix_text.trim()
        ));
    }

    Ok((ip, prefix))
}

fn is_supported_interface_name(interface_name: &str) -> bool {
    let Some(index_part) = interface_name.strip_prefix("utun") else {
        return false;
    };

    !index_part.is_empty() && index_part.chars().all(|ch| ch.is_ascii_digit())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_primary_address_extracts_ipv4_and_prefix() {
        let config = TunSessionConfig {
            addresses: vec!["10.0.0.1/24".to_string()],
            ..Default::default()
        };
        let (ip, prefix) = parse_primary_address(&config).unwrap();
        assert!(ip.is_ipv4());
        assert_eq!(prefix, 24);
    }

    #[test]
    fn parse_primary_address_extracts_ipv6_and_prefix() {
        let config = TunSessionConfig {
            addresses: vec!["fd00::1/64".to_string()],
            ..Default::default()
        };
        let (ip, prefix) = parse_primary_address(&config).unwrap();
        assert!(ip.is_ipv6());
        assert_eq!(prefix, 64);
    }

    #[test]
    fn parse_primary_address_rejects_missing_prefix() {
        let config = TunSessionConfig {
            addresses: vec!["10.0.0.1".to_string()],
            ..Default::default()
        };
        assert!(parse_primary_address(&config).is_err());
    }

    #[test]
    fn parse_primary_address_rejects_invalid_prefix() {
        let config = TunSessionConfig {
            addresses: vec!["10.0.0.1/abc".to_string()],
            ..Default::default()
        };
        assert!(parse_primary_address(&config).is_err());
    }

    #[test]
    fn parse_primary_address_rejects_prefix_too_large_for_ipv4() {
        let config = TunSessionConfig {
            addresses: vec!["10.0.0.1/33".to_string()],
            ..Default::default()
        };
        assert!(parse_primary_address(&config).is_err());
    }

    #[test]
    fn parse_primary_address_rejects_prefix_too_large_for_ipv6() {
        let config = TunSessionConfig {
            addresses: vec!["fd00::1/129".to_string()],
            ..Default::default()
        };
        assert!(parse_primary_address(&config).is_err());
    }

    #[test]
    fn parse_primary_address_rejects_empty_addresses() {
        let config = TunSessionConfig {
            addresses: vec![],
            ..Default::default()
        };
        assert!(parse_primary_address(&config).is_err());
    }

    #[test]
    fn is_supported_interface_name_accepts_utun_names() {
        assert!(is_supported_interface_name("utun0"));
        assert!(is_supported_interface_name("utun99"));
        assert!(is_supported_interface_name("utun123"));
    }

    #[test]
    fn is_supported_interface_name_rejects_non_utun_names() {
        assert!(!is_supported_interface_name("wg0"));
        assert!(!is_supported_interface_name("eth0"));
        assert!(!is_supported_interface_name("utun"));
        assert!(!is_supported_interface_name("utunabc"));
        assert!(!is_supported_interface_name("utun1a"));
    }
}
