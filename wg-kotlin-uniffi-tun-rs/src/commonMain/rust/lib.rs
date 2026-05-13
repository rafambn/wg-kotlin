use std::cell::RefCell;
use std::sync::{Arc, Mutex as StdMutex};
use std::{net::IpAddr, str::FromStr};
use tun_rs::{InterruptEvent, SyncDevice};

thread_local! {
    static READ_BUFFER: RefCell<Vec<u8>> = RefCell::new(vec![0; 65536]);
}

struct DeviceState {
    device: Option<Arc<SyncDevice>>,
    interrupt: Option<Arc<InterruptEvent>>,
}

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
    state: Arc<StdMutex<DeviceState>>,
    interface_name: String,
}

#[uniffi::export]
impl TunDevice {
    #[uniffi::constructor]
    pub fn new(interface_name: String) -> Arc<Self> {
        Arc::new(TunDevice {
            state: Arc::new(StdMutex::new(DeviceState {
                device: None,
                interrupt: None,
            })),
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

        #[cfg(unix)]
        device
            .set_nonblocking(true)
            .map_err(|e: std::io::Error| TunError::DeviceCreationFailed(e.to_string()))?;

        let interrupt = InterruptEvent::new()
            .map_err(|e: std::io::Error| TunError::DeviceCreationFailed(e.to_string()))?;

        let mut guard = self.state.lock().map_err(|_| {
            TunError::DeviceCreationFailed("Failed to acquire device lock".to_string())
        })?;
        guard.device = Some(Arc::new(device));
        guard.interrupt = Some(Arc::new(interrupt));

        Ok(())
    }

    pub fn read_packet(&self) -> Result<Vec<u8>, TunError> {
        let (device, interrupt) = self.device_handles_for_read()?;

        READ_BUFFER.with(|buffer_cell| {
            let mut buffer = buffer_cell.borrow_mut();
            if buffer.len() < 65536 {
                buffer.resize(65536, 0);
            }

            let len = device
                .recv_intr(&mut buffer, &interrupt)
                .map_err(|e: std::io::Error| {
                    if e.kind() == std::io::ErrorKind::Interrupted {
                        TunError::DeviceClosed
                    } else {
                        TunError::ReadFailed(e.to_string())
                    }
                })?;

            Ok(buffer[..len].to_vec())
        })
    }

    pub fn write_packet(&self, packet: Vec<u8>) -> Result<(), TunError> {
        let (device, interrupt) = self.device_handles_for_write()?;

        device
            .send_intr(&packet, &interrupt)
            .map_err(|e: std::io::Error| {
                if e.kind() == std::io::ErrorKind::Interrupted {
                    TunError::DeviceClosed
                } else {
                    TunError::WriteFailed(e.to_string())
                }
            })?;

        Ok(())
    }

    pub fn get_interface_name(&self) -> String {
        self.state
            .lock()
            .ok()
            .and_then(|guard| guard.device.as_ref().and_then(|device| device.name().ok()))
            .unwrap_or_else(|| self.interface_name.clone())
    }

    pub fn shutdown(&self) -> Result<(), TunError> {
        let interrupt = {
            let guard = self.state.lock().map_err(|_| {
                TunError::DeviceCreationFailed("Failed to acquire device lock".to_string())
            })?;
            guard.interrupt.clone()
        };

        if let Some(interrupt) = interrupt {
            interrupt
                .trigger()
                .map_err(|e: std::io::Error| TunError::DeviceCreationFailed(e.to_string()))?;
        }

        let mut guard = self.state.lock().map_err(|_| {
            TunError::DeviceCreationFailed("Failed to acquire device lock".to_string())
        })?;
        guard.device = None;
        guard.interrupt = None;
        Ok(())
    }
}

impl TunDevice {
    fn device_handles_for_read(&self) -> Result<(Arc<SyncDevice>, Arc<InterruptEvent>), TunError> {
        let guard = self.state.lock().map_err(|_| {
            TunError::ReadFailed("Failed to acquire device lock".to_string())
        })?;
        let device = guard.device.as_ref().ok_or(TunError::DeviceClosed)?;
        let interrupt = guard.interrupt.as_ref().ok_or(TunError::DeviceClosed)?;
        Ok((Arc::clone(device), Arc::clone(interrupt)))
    }

    fn device_handles_for_write(&self) -> Result<(Arc<SyncDevice>, Arc<InterruptEvent>), TunError> {
        let guard = self.state.lock().map_err(|_| {
            TunError::WriteFailed("Failed to acquire device lock".to_string())
        })?;
        let device = guard.device.as_ref().ok_or(TunError::DeviceClosed)?;
        let interrupt = guard.interrupt.as_ref().ok_or(TunError::DeviceClosed)?;
        Ok((Arc::clone(device), Arc::clone(interrupt)))
    }
}

uniffi::setup_scaffolding!();
