use crate::platform::{self, SessionNetwork};
use crate::validation::Config;
#[cfg(target_os = "windows")]
use crate::wintun::WintunDll;
use anyhow::{Context, Result};
use itertools::Itertools;
use std::net::IpAddr;
use std::sync::Arc;
use tun_rs::{AsyncDevice, DeviceBuilder};

pub type SessionFactory = Arc<dyn Fn(&Config) -> Result<TunSession> + Send + Sync>;

pub struct TunSession {
    pub(crate) device: AsyncDevice,
    // Keep the interface registered for endpoint checks until the device drops.
    pub(crate) interface_name: Arc<str>,
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
        let endpoint_routes = platform::resolve_endpoint_routes(config)?;
        #[cfg(windows)]
        platform::clear_stale_nrpt_rules_once().context("failed to prepare platform session")?;

        let mut builder = DeviceBuilder::new().name(config.interface_name.clone());
        let mut ipv4 = config
            .addresses
            .iter()
            .filter_map(|&(ip, prefix)| match ip {
                IpAddr::V4(ip) => Some((ip, prefix)),
                IpAddr::V6(_) => None,
            })
            .unique();
        if let Some((ip, prefix)) = ipv4.next() {
            builder = builder.ipv4(ip, prefix, None);
        }
        for &(ip, prefix) in &config.addresses {
            if let IpAddr::V6(ip) = ip {
                builder = builder.ipv6(ip, prefix);
            }
        }
        let builder = config.mtu.into_iter().fold(builder, |builder, mtu| builder.mtu(mtu.get()));

        #[cfg(target_os = "windows")]
        let builder = builder.wintun_file(wintun.path().to_str().context("Wintun DLL path is not Unicode")?.to_owned());

        let interface_name: Arc<str>;
        let device = builder.build_async().context("failed to create TUN device")?;
        for (ip, prefix) in ipv4 {
            device.add_address_v4(ip, prefix).with_context(|| format!("failed to add IPv4 address {ip}/{prefix} to {}", config.interface_name))?;
        }
        interface_name = device.name().unwrap_or_else(|_| config.interface_name.clone()).into();
        let network = platform::configure_session(config, &interface_name, endpoint_routes).context("failed to configure platform session")?;

        Ok(TunSession {
            interface_name,
            device,
            network,
            #[cfg(target_os = "windows")]
            _wintun: wintun.clone(),
        })
    }))
}

impl TunSession {
    pub fn close(self) -> Result<()> {
        (self.network.cleanup)()
    }
}

#[cfg(all(test, any(target_os = "linux", target_os = "macos")))]
#[path = "../tests/unit/session_io_tests.rs"]
mod tests;
