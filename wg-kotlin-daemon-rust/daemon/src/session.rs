use crate::ip_util::parse_proto_cidr;
use crate::platform::{self, CleanupHook};
use daemon_proto::pb::TunSessionConfig;
use std::net::IpAddr;
#[cfg(target_os = "windows")]
use std::sync::OnceLock;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
use tun_rs::{DeviceBuilder, InterruptEvent, SyncDevice};

#[cfg(target_os = "windows")]
static WINTUN_DLL_PATH: OnceLock<String> = OnceLock::new();

#[cfg(target_os = "windows")]
pub fn set_wintun_dll_path(path: String) -> Result<(), String> {
    WINTUN_DLL_PATH.set(path).map_err(|_| "wintun DLL path already initialized".to_string())
}

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
            return Err(format!("unsupported interface name '{}': expected utun[0-9]+", config.interface_name));
        }

        platform::prepare_session_start().map_err(|error| format!("failed to prepare platform session: {error}"))?;

        let mut builder = DeviceBuilder::new().name(config.interface_name.clone());
        for addr in &config.addresses {
            let (ip, prefix) = parse_proto_cidr(addr).unwrap();
            let prefix = u8::try_from(prefix).unwrap();
            builder = match ip {
                IpAddr::V4(v4) => builder.ipv4(v4, prefix, None),
                IpAddr::V6(v6) => builder.ipv6(v6, prefix),
            };
        }
        if config.mtu > 0 {
            builder = builder.mtu(config.mtu as u16);
        }

        #[cfg(target_os = "windows")]
        if let Some(path) = WINTUN_DLL_PATH.get() {
            builder = builder.wintun_file(path.clone());
        }

        let device = builder.build_sync().map_err(|error| format!("failed to create TUN device: {error}"))?;

        #[cfg(unix)]
        device.set_nonblocking(true).map_err(|error| format!("failed to configure non-blocking TUN: {error}"))?;

        let interface_name = device.name().unwrap_or_else(|_| config.interface_name.clone());

        let interrupt = InterruptEvent::new().map_err(|error| format!("failed to create TUN interrupt event: {error}"))?;

        let cleanup_hook = platform::configure_session(config, &interface_name).map_err(|error| {
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

pub fn is_supported_interface_name(interface_name: &str) -> bool {
    let Some(index_part) = interface_name.strip_prefix("utun") else {
        return false;
    };

    !index_part.is_empty() && index_part.chars().all(|ch| ch.is_ascii_digit())
}
