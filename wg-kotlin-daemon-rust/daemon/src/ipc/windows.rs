use crate::owner::OwnerProcess;
use std::io;
use std::os::windows::io::AsRawHandle;
use tokio::net::windows::named_pipe::{NamedPipeServer, ServerOptions};
use windows::Win32::Foundation::{HANDLE, HLOCAL, LocalFree};
use windows::Win32::Security::Authorization::ConvertStringSecurityDescriptorToSecurityDescriptorW;
use windows::Win32::Security::{PSECURITY_DESCRIPTOR, SECURITY_ATTRIBUTES};
use windows::Win32::System::Pipes::GetNamedPipeClientProcessId;

pub struct LocalListener {
    endpoint: String,
    sid: String,
    pending: NamedPipeServer,
}

impl LocalListener {
    pub fn bind(endpoint: &str, owner: &OwnerProcess) -> io::Result<Self> {
        if !endpoint.starts_with(r"\\.\pipe\wg-kotlin-") {
            return Err(io::Error::other("invalid local named pipe endpoint"));
        }
        let pending = create(endpoint, &owner.sid, true)?;
        Ok(Self { endpoint: endpoint.into(), sid: owner.sid.clone(), pending })
    }

    pub async fn accept(&mut self) -> io::Result<(NamedPipeServer, u32)> {
        loop {
            match self.pending.connect().await {
                Ok(()) => break,
                Err(error) if matches!(error.raw_os_error(), Some(109 | 232 | 233)) => {
                    self.pending = create(&self.endpoint, &self.sid, false)?;
                }
                Err(error) => return Err(error),
            }
        }
        // Keep a listening instance alive so another server cannot claim the name.
        let next = create(&self.endpoint, &self.sid, false)?;
        let stream = std::mem::replace(&mut self.pending, next);
        let mut pid = 0;
        if unsafe { GetNamedPipeClientProcessId(HANDLE(stream.as_raw_handle()), &mut pid) }.is_err() {
            pid = 0;
        }
        Ok((stream, pid))
    }
}

fn create(endpoint: &str, sid: &str, first: bool) -> io::Result<NamedPipeServer> {
    // Account ACLs narrow who may connect; kernel PID checks enforce process
    // exclusivity. Medium integrity permits the unelevated app to write to its
    // elevated daemon. The client also verifies the server PID before sending.
    let sddl: Vec<u16> = format!("D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGW;;;{sid})S:(ML;;NW;;;ME)").encode_utf16().chain(Some(0)).collect();
    let mut descriptor = PSECURITY_DESCRIPTOR::default();
    unsafe {
        ConvertStringSecurityDescriptorToSecurityDescriptorW(windows::core::PCWSTR(sddl.as_ptr()), 1, &mut descriptor, None)?;
        let attributes = SECURITY_ATTRIBUTES {
            nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: descriptor.0,
            bInheritHandle: false.into(),
        };
        let result = ServerOptions::new()
            .first_pipe_instance(first)
            .reject_remote_clients(true)
            .create_with_security_attributes_raw(endpoint, &attributes as *const _ as *mut _);
        let _ = LocalFree(HLOCAL(descriptor.0));
        result
    }
}
