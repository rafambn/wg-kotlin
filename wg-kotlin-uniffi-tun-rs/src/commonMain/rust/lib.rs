use std::sync::{Arc, Mutex as StdMutex};
use std::{net::IpAddr, str::FromStr};
use tun_rs::SyncDevice;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum TunError {
    #[error("failed to create tun device: {0}")]
    DeviceCreationFailed(String),
    #[error("failed to read packet: {0}")]
    ReadFailed(String),
    #[error("failed to write packet: {0}")]
    WriteFailed(String),
    #[error("device already closed")]
    DeviceClosed,
}

#[derive(uniffi::Object)]
pub struct TunDevice {
    device: Arc<StdMutex<Option<SyncDevice>>>,
    interface_name: String,
}

#[uniffi::export]
impl TunDevice {
    #[uniffi::constructor]
    pub fn new(interface_name: String) -> Arc<Self> {
        Arc::new(TunDevice {
            device: Arc::new(StdMutex::new(None)),
            interface_name,
        })
    }

    pub fn open(
        &self,
        ip_addr: String,
        prefix_len: u8,
        wintun_dll_path: Option<String>,
    ) -> Result<(), TunError> {
        let parsed_ip = IpAddr::from_str(ip_addr.as_str()).map_err(|_| {
            TunError::DeviceCreationFailed(format!("invalid ip address: {ip_addr}"))
        })?;

        let mut builder = tun_rs::DeviceBuilder::new().name(self.interface_name.clone());
        builder = match parsed_ip {
            IpAddr::V4(ipv4_addr) => builder.ipv4(ipv4_addr, prefix_len, None),
            IpAddr::V6(ipv6_addr) => builder.ipv6(ipv6_addr, prefix_len),
        };

        #[cfg(windows)]
        if let Some(path) = wintun_dll_path {
            if !path.trim().is_empty() {
                builder = builder.wintun_file(path);
            }
        }

        #[cfg(not(windows))]
        let _ = wintun_dll_path;

        let device = builder
            .build_sync()
            .map_err(|e: std::io::Error| TunError::DeviceCreationFailed(e.to_string()))?;

        let mut guard = self.device.lock().map_err(|_| {
            TunError::DeviceCreationFailed("Failed to acquire device lock".to_string())
        })?;
        *guard = Some(device);

        Ok(())
    }

    pub fn read_packet(&self) -> Result<Vec<u8>, TunError> {
        let mut guard = self.device.lock().map_err(|_| {
            TunError::ReadFailed("Failed to acquire device lock".to_string())
        })?;
        let device = guard.as_mut().ok_or(TunError::DeviceClosed)?;

        let mut buf = vec![0; 65536];
        let len = device
            .recv(&mut buf)
            .map_err(|e: std::io::Error| TunError::ReadFailed(e.to_string()))?;

        buf.truncate(len);
        Ok(buf)
    }

    pub fn write_packet(&self, packet: Vec<u8>) -> Result<(), TunError> {
        let mut guard = self.device.lock().map_err(|_| {
            TunError::WriteFailed("Failed to acquire device lock".to_string())
        })?;
        let device = guard.as_mut().ok_or(TunError::DeviceClosed)?;

        device
            .send(&packet)
            .map_err(|e: std::io::Error| TunError::WriteFailed(e.to_string()))?;

        Ok(())
    }

    pub fn get_interface_name(&self) -> String {
        self.device
            .lock()
            .ok()
            .and_then(|guard| guard.as_ref().and_then(|device| device.name().ok()))
            .unwrap_or_else(|| self.interface_name.clone())
    }

    pub fn shutdown(&self) -> Result<(), TunError> {
        let mut guard = self.device.lock().map_err(|_| {
            TunError::DeviceCreationFailed("Failed to acquire device lock".to_string())
        })?;
        *guard = None;
        Ok(())
    }
}

uniffi::setup_scaffolding!();
