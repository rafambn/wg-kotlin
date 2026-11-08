use crate::platform::{self, CleanupHook};
use crate::validation::Config;
#[cfg(target_os = "windows")]
use crate::wintun::WintunDll;
use anyhow::{Context, Result};
use std::net::IpAddr;
use std::sync::Arc;
use tun_rs::{AsyncDevice, DeviceBuilder};

pub type SessionFactory = Arc<dyn Fn(&Config) -> Result<TunSession> + Send + Sync>;

pub struct TunSession {
    pub(crate) interface_name: String,
    pub(crate) device: AsyncDevice,
    cleanup: CleanupHook,
    // Drop after the device so that the DLL stays protected until it unloads.
    #[cfg(target_os = "windows")]
    _wintun: Arc<WintunDll>,
}

pub fn factory() -> Result<SessionFactory> {
    #[cfg(windows)]
    let wintun = Arc::new(WintunDll::prepare()?);
    Ok(Arc::new(move |config| {
        let endpoint_routes = platform::resolve_endpoint_routes(config)?;
        #[cfg(windows)]
        platform::clear_stale_nrpt_rules_once().context("failed to prepare platform session")?;

        let builder = DeviceBuilder::new().name(config.interface_name.clone());
        let builder = config.addresses.iter().fold(builder, |builder, &(ip, prefix)| match ip {
            IpAddr::V4(v4) => builder.ipv4(v4, prefix, None),
            IpAddr::V6(v6) => builder.ipv6(v6, prefix),
        });
        let builder = config.mtu.into_iter().fold(builder, |builder, mtu| builder.mtu(mtu.get()));

        #[cfg(target_os = "windows")]
        let builder = builder.wintun_file(wintun.path().to_str().context("Wintun DLL path is not Unicode")?.to_owned());

        let device = builder.build_async().context("failed to create TUN device")?;
        let interface_name = device.name().unwrap_or_else(|_| config.interface_name.clone());
        let cleanup = platform::configure_session(config, &interface_name, endpoint_routes).context("failed to configure platform session")?;

        Ok(TunSession {
            interface_name,
            device,
            cleanup,
            #[cfg(target_os = "windows")]
            _wintun: wintun.clone(),
        })
    }))
}

impl TunSession {
    pub fn close(self) -> Result<()> {
        (self.cleanup)()
    }
}

#[cfg(all(test, any(target_os = "linux", target_os = "macos")))]
#[path = "../tests/unit/session_io_tests.rs"]
mod tests;
