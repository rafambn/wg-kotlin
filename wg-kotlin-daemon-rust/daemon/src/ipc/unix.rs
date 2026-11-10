use crate::owner::OwnerProcess;
use std::io;
use std::os::unix::fs::DirBuilderExt;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;
use tempfile::TempPath;
use tokio::net::{UnixListener, UnixStream};

pub struct LocalListener {
    // Unlink before closing the listener; remove the directory last.
    path: TempPath,
    listener: UnixListener,
    _directory: SocketDirectory,
}

struct SocketDirectory(PathBuf);
impl Drop for SocketDirectory {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir(&self.0);
    }
}

impl LocalListener {
    pub fn bind(endpoint: &str, owner: &OwnerProcess) -> anyhow::Result<Self> {
        let path = PathBuf::from(endpoint);
        let parent = path.parent().ok_or_else(|| io::Error::other("missing socket directory"))?;
        anyhow::ensure!(
            parent.parent() == Some(std::path::Path::new("/tmp"))
                && parent.file_name().unwrap_or_default().to_string_lossy().starts_with("wg-kotlin-"),
            io::Error::other("IPC socket requires its own /tmp/wg-kotlin-* directory")
        );
        // This directory is created by the privileged daemon, not the app. Its
        // root ownership prevents other user processes replacing the socket
        // between bind, chmod and chown.
        std::fs::DirBuilder::new().mode(0o700).create(parent)?;
        let directory = SocketDirectory(parent.to_path_buf());
        // Bind before creating TempPath, so collisions never unlink an existing socket.
        let result = Self { listener: UnixListener::bind(&path)?, path: TempPath::try_from_path(path)?, _directory: directory };
        // PID checks enforce app exclusivity. Socket permissions allow its user
        // to reach a root-owned listener without sharing credentials.
        std::fs::set_permissions(&result.path, std::fs::Permissions::from_mode(0o600))?;
        if unsafe { libc::geteuid() } == 0 {
            std::os::unix::fs::chown(&result.path, Some(owner.uid), None)?;
        }
        std::fs::set_permissions(&result._directory.0, std::fs::Permissions::from_mode(0o711))?;
        Ok(result)
    }

    pub async fn accept(&mut self) -> anyhow::Result<(UnixStream, u32)> {
        let (stream, _) = self.listener.accept().await?;
        #[cfg(target_os = "linux")]
        let pid = stream.peer_cred().ok().and_then(|credentials| credentials.pid()).filter(|pid| *pid > 0).unwrap_or(0) as u32;
        #[cfg(target_os = "macos")]
        // A disconnected peer is rejected without failing the listener.
        let pid = nix::sys::socket::getsockopt(&stream, nix::sys::socket::sockopt::LocalPeerPid).unwrap_or(0).max(0) as u32;
        Ok((stream, pid))
    }
}
