use crate::platform::{self, RegisteredDevice, SessionNetwork};
use crate::validation::Config;
#[cfg(target_os = "windows")]
use crate::wintun::WintunDll;
use anyhow::{Context, Result, ensure};
use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::Arc;
use tun_rs::{AsyncDevice, DeviceBuilder};

pub type SessionFactory = Arc<dyn Fn(&Config) -> Result<TunSession> + Send + Sync>;

pub struct TunSession {
    pub(crate) tun: RegisteredDevice,
    pub(crate) network: SessionNetwork,
    // Drop after the device so that the DLL stays protected until it unloads.
    #[cfg(target_os = "windows")]
    _wintun: Arc<WintunDll>,
}

pub fn factory() -> Result<SessionFactory> {
    #[cfg(windows)]
    let wintun = Arc::new(WintunDll::prepare()?);
    Ok(Arc::new(move |config| {
        // AsyncDevice registers its FD with the caller's reactor, including on blocking workers.
        tokio::runtime::Handle::try_current().context("TUN setup requires a Tokio runtime")?;
        let addresses = unique_addresses(&config.addresses)?;
        let mut endpoint_routes = platform::resolve_endpoint_routes(config)?;
        #[cfg(windows)]
        platform::clear_stale_nrpt_rules_once().context("failed to prepare platform session")?;

        let mut builder = DeviceBuilder::new().name(config.interface_name.clone());
        let mut ipv4 = addresses.iter().filter_map(|&(ip, prefix)| match ip {
            IpAddr::V4(ip) => Some((ip, prefix)),
            IpAddr::V6(_) => None,
        });
        if let Some((ip, prefix)) = ipv4.next() {
            builder = builder.ipv4(ip, prefix, None);
        }
        for &(ip, prefix) in &addresses {
            if let IpAddr::V6(ip) = ip {
                builder = builder.ipv6(ip, prefix);
            }
        }
        let builder = config.mtu.into_iter().fold(builder, |builder, mtu| builder.mtu(mtu.get()));

        #[cfg(target_os = "windows")]
        let builder = builder.wintun_file(wintun.path().to_str().context("Wintun DLL path is not Unicode")?.to_owned());

        // Drop a partially built device before releasing the route registry lock.
        let device = (|| -> Result<AsyncDevice> {
            let device = builder.build_async().context("failed to create TUN device")?;
            for (ip, prefix) in ipv4 {
                device
                    .add_address_v4(ip, prefix)
                    .with_context(|| format!("failed to add IPv4 address {ip}/{prefix} to {}", config.interface_name))?;
            }
            Ok(device)
        })()?;
        let interface_name = device.name().unwrap_or_else(|_| config.interface_name.clone());
        let tun = endpoint_routes.register_device(interface_name, device);
        let network = platform::configure_session(config, tun.interface_name(), endpoint_routes).context("failed to configure platform session")?;

        Ok(TunSession {
            tun,
            network,
            #[cfg(target_os = "windows")]
            _wintun: wintun.clone(),
        })
    }))
}

fn unique_addresses(addresses: &[(IpAddr, u8)]) -> Result<Vec<(IpAddr, u8)>> {
    let mut prefixes = HashMap::new();
    let mut unique = Vec::new();
    for &(ip, prefix) in addresses {
        if let Some(previous) = prefixes.insert(ip, prefix) {
            ensure!(previous == prefix, "TUN address {ip} has conflicting prefixes /{previous} and /{prefix}");
        } else {
            unique.push((ip, prefix));
        }
    }
    Ok(unique)
}

impl TunSession {
    pub fn close(self) -> Result<()> {
        (self.network.cleanup)()
    }
}

#[cfg(all(test, any(target_os = "linux", target_os = "macos")))]
#[path = "../tests/unit/session_io_tests.rs"]
mod tests;
