use crate::owner::OwnerProcess;
use std::io;
use std::os::unix::fs::DirBuilderExt;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;
use tokio::net::{UnixListener, UnixStream};

pub struct LocalListener {
    listener: UnixListener,
    path: PathBuf,
    _directory: SocketDirectory,
}

struct SocketDirectory(PathBuf);
impl Drop for SocketDirectory {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir(&self.0);
    }
}

impl LocalListener {
    pub fn bind(endpoint: &str, owner: &OwnerProcess) -> io::Result<Self> {
        let path = PathBuf::from(endpoint);
        let parent = path.parent().ok_or_else(|| io::Error::other("missing socket directory"))?;
        if parent.parent() != Some(std::path::Path::new("/tmp"))
            || !parent.file_name().unwrap_or_default().to_string_lossy().starts_with("wg-kotlin-")
        {
            return Err(io::Error::other("IPC socket requires its own /tmp/wg-kotlin-* directory"));
        }
        // This directory is created by the privileged daemon, not the app. Its
        // root ownership prevents other user processes replacing the socket
        // between bind, chmod and chown.
        std::fs::DirBuilder::new().mode(0o700).create(parent)?;
        let directory = SocketDirectory(parent.to_path_buf());
        // Never unlink a pre-existing endpoint; refuse collisions instead.
        let listener = UnixListener::bind(&path)?;
        let result = Self { listener, path, _directory: directory };
        // PID checks enforce app exclusivity. Socket permissions allow its user
        // to reach a root-owned listener without sharing credentials.
        std::fs::set_permissions(&result.path, std::fs::Permissions::from_mode(0o600))?;
        if unsafe { libc::geteuid() } == 0 {
            let name = std::ffi::CString::new(endpoint)?;
            if unsafe { libc::chown(name.as_ptr(), owner.uid, u32::MAX) } != 0 {
                return Err(io::Error::last_os_error());
            }
        }
        std::fs::set_permissions(&result._directory.0, std::fs::Permissions::from_mode(0o711))?;
        Ok(result)
    }

    pub async fn accept(&mut self) -> io::Result<(UnixStream, u32)> {
        let (stream, _) = self.listener.accept().await?;
        #[cfg(target_os = "linux")]
        let pid = stream.peer_cred().ok().and_then(|credentials| credentials.pid()).filter(|pid| *pid > 0).unwrap_or(0) as u32;
        #[cfg(target_os = "macos")]
        let pid = {
            use std::os::fd::AsRawFd;
            let mut pid: libc::pid_t = 0;
            let mut size = std::mem::size_of_val(&pid) as libc::socklen_t;
            let result = unsafe { libc::getsockopt(stream.as_raw_fd(), 0, 2, &mut pid as *mut _ as *mut _, &mut size) };
            // A peer may disconnect before credentials are queried. Reject that
            // connection without treating it as a failure of the listener.
            if result == 0 && size as usize == std::mem::size_of_val(&pid) && pid > 0 { pid as u32 } else { 0 }
        };
        Ok((stream, pid))
    }
}

impl Drop for LocalListener {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
    }
}
