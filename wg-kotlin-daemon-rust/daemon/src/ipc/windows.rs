use crate::owner::OwnerProcess;
use std::io;
use std::os::windows::io::AsRawHandle;
use tokio::net::windows::named_pipe::{NamedPipeServer, ServerOptions};
use windows::Win32::Foundation::{HANDLE, HLOCAL};
use windows::Win32::Security::Authorization::ConvertStringSecurityDescriptorToSecurityDescriptorW;
use windows::Win32::Security::{PSECURITY_DESCRIPTOR, SECURITY_ATTRIBUTES};
use windows::Win32::System::Pipes::GetNamedPipeClientProcessId;

pub struct LocalListener {
    endpoint: String,
    sid: String,
    pending: NamedPipeServer,
}

impl LocalListener {
    pub fn bind(endpoint: &str, owner: &OwnerProcess) -> anyhow::Result<Self> {
        anyhow::ensure!(endpoint.starts_with(r"\\.\pipe\wg-kotlin-"), io::Error::other("invalid local named pipe endpoint"));
        Ok(Self { pending: create(endpoint, &owner.sid, true)?, endpoint: endpoint.into(), sid: owner.sid.clone() })
    }

    pub async fn accept(&mut self) -> anyhow::Result<(NamedPipeServer, u32)> {
        while let Err(error) = self.pending.connect().await {
            // Clients may disconnect before connect completes.
            anyhow::ensure!(matches!(error.raw_os_error(), Some(109 | 232 | 233)), error);
            self.pending = create(&self.endpoint, &self.sid, false)?;
        }
        // Keep a listening instance alive so another server cannot claim the name.
        let stream = std::mem::replace(&mut self.pending, create(&self.endpoint, &self.sid, false)?);
        let mut pid = 0;
        let pid = unsafe { GetNamedPipeClientProcessId(HANDLE(stream.as_raw_handle()), &mut pid) }.map_or(0, |_| pid);
        Ok((stream, pid))
    }
}

fn create(endpoint: &str, sid: &str, first: bool) -> io::Result<NamedPipeServer> {
    // Account ACLs narrow who may connect; kernel PID checks enforce process
    // exclusivity. Medium integrity permits the unelevated app to write to its
    // elevated daemon. The client also verifies the server PID before sending.
    let sddl = windows::core::HSTRING::from(format!("D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGW;;;{sid})S:(ML;;NW;;;ME)"));
    let mut descriptor = PSECURITY_DESCRIPTOR::default();
    unsafe { ConvertStringSecurityDescriptorToSecurityDescriptorW(&sddl, 1, &mut descriptor, None) }?;
    let _descriptor = unsafe { windows::core::Owned::new(HLOCAL(descriptor.0)) };
    let attributes = SECURITY_ATTRIBUTES {
        nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
        lpSecurityDescriptor: descriptor.0,
        bInheritHandle: false.into(),
    };
    let mut options = ServerOptions::new();
    options.first_pipe_instance(first).reject_remote_clients(true);
    unsafe { options.create_with_security_attributes_raw(endpoint, &attributes as *const _ as *mut _) }
}
