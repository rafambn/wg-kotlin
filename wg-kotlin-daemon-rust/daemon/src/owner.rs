#[cfg(unix)]
use anyhow::Context;
use anyhow::bail;

/// Pins the app instance, not just its reusable numeric PID. The app supplies
/// boot-relative start ticks on Linux, or ProcessHandle's epoch milliseconds on
/// macOS/Windows, checked against the OS before binding.
pub struct OwnerProcess {
    pub pid: u32,
    #[cfg(unix)]
    pub uid: u32,
    #[cfg(target_os = "linux")]
    process: std::os::fd::OwnedFd,
    #[cfg(target_os = "macos")]
    birth: (u64, u64),
    #[cfg(windows)]
    process: std::os::windows::io::OwnedHandle,
    #[cfg(windows)]
    pub sid: String,
}

impl OwnerProcess {
    pub fn open(pid: u32, start_time: u64) -> anyhow::Result<Self> {
        if pid == 0 {
            bail!("invalid owner PID");
        }
        #[cfg(unix)]
        anyhow::ensure!(pid <= i32::MAX as u32, "invalid owner PID");
        #[cfg(target_os = "linux")]
        {
            use std::os::fd::FromRawFd;
            let fd = unsafe { libc::syscall(libc::SYS_pidfd_open, pid, 0) };
            if fd < 0 {
                return Err(std::io::Error::last_os_error()).context("open owner pidfd (Linux 5.3+ required)");
            }
            let process = unsafe { std::os::fd::OwnedFd::from_raw_fd(fd as i32) };
            let status = std::fs::read_to_string(format!("/proc/{pid}/status"))?;
            let uid = status
                .lines()
                .find_map(|line| line.strip_prefix("Uid:"))
                .context("missing owner UID")?
                .split_whitespace()
                .next()
                .context("missing real UID")?
                .parse()?;
            let stat = std::fs::read_to_string(format!("/proc/{pid}/stat"))?;
            let fields: Vec<_> = stat.rsplit_once(')').context("invalid proc stat")?.1.split_whitespace().collect();
            let ticks: u64 = fields.get(19).context("missing process start time")?.parse()?;
            anyhow::ensure!(ticks == start_time, "owner process creation time mismatch");
            let owner = Self { pid, uid, process };
            anyhow::ensure!(owner.is_alive(), "owner has exited");
            Ok(owner)
        }
        #[cfg(target_os = "macos")]
        {
            let info = macos_info(pid).context("read owner process identity")?;
            anyhow::ensure!(info.pbi_start_tvsec * 1000 + info.pbi_start_tvusec / 1000 == start_time, "owner process creation time mismatch");
            Ok(Self { pid, uid: info.pbi_ruid, birth: (info.pbi_start_tvsec, info.pbi_start_tvusec) })
        }
        #[cfg(windows)]
        {
            use std::os::windows::io::{AsRawHandle, FromRawHandle};
            use windows::Win32::Foundation::{FILETIME, HANDLE};
            use windows::Win32::System::Threading::*;
            let handle = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE, false, pid)? };
            let process = unsafe { std::os::windows::io::OwnedHandle::from_raw_handle(handle.0) };
            let mut creation = FILETIME::default();
            let mut exit = FILETIME::default();
            let mut kernel = FILETIME::default();
            let mut user = FILETIME::default();
            unsafe {
                GetProcessTimes(HANDLE(process.as_raw_handle()), &mut creation, &mut exit, &mut kernel, &mut user)?;
            }
            let ticks = ((creation.dwHighDateTime as u64) << 32) | creation.dwLowDateTime as u64;
            anyhow::ensure!(ticks / 10_000 - 11_644_473_600_000 == start_time, "owner process creation time mismatch");
            let sid = windows_sid(HANDLE(process.as_raw_handle()))?;
            let owner = Self { pid, process, sid };
            anyhow::ensure!(owner.is_alive(), "owner has exited");
            Ok(owner)
        }
    }

    pub fn authorizes(&self, peer_pid: u32) -> bool {
        peer_pid == self.pid && self.is_alive()
    }

    pub fn is_alive(&self) -> bool {
        #[cfg(target_os = "linux")]
        {
            use std::os::fd::AsRawFd;
            let mut fd = libc::pollfd { fd: self.process.as_raw_fd(), events: libc::POLLIN, revents: 0 };
            unsafe { libc::poll(&mut fd, 1, 0) == 0 }
        }
        #[cfg(target_os = "macos")]
        {
            macos_info(self.pid).is_some_and(|info| (info.pbi_start_tvsec, info.pbi_start_tvusec) == self.birth)
        }
        #[cfg(windows)]
        {
            use std::os::windows::io::AsRawHandle;
            unsafe {
                windows::Win32::System::Threading::WaitForSingleObject(windows::Win32::Foundation::HANDLE(self.process.as_raw_handle()), 0)
                    == windows::Win32::Foundation::WAIT_TIMEOUT
            }
        }
    }
}

#[cfg(target_os = "macos")]
fn macos_info(pid: u32) -> Option<libc::proc_bsdinfo> {
    let mut info: libc::proc_bsdinfo = unsafe { std::mem::zeroed() };
    let size = std::mem::size_of_val(&info) as i32;
    let read = unsafe { libc::proc_pidinfo(pid as i32, libc::PROC_PIDTBSDINFO, 0, &mut info as *mut _ as *mut _, size) };
    // SZOMB is 5; a zombie must never authorize another connection.
    (read == size && info.pbi_status != 5).then_some(info)
}

#[cfg(windows)]
fn windows_sid(process: windows::Win32::Foundation::HANDLE) -> anyhow::Result<String> {
    use std::os::windows::io::{AsRawHandle, FromRawHandle};
    use windows::Win32::Foundation::{HANDLE, HLOCAL, LocalFree};
    use windows::Win32::Security::Authorization::ConvertSidToStringSidW;
    use windows::Win32::Security::*;
    let mut token = HANDLE::default();
    unsafe {
        windows::Win32::System::Threading::OpenProcessToken(process, TOKEN_QUERY, &mut token)?;
    }
    let token = unsafe { std::os::windows::io::OwnedHandle::from_raw_handle(token.0) };
    let mut size = 0;
    let _ = unsafe { GetTokenInformation(HANDLE(token.as_raw_handle()), TokenUser, None, 0, &mut size) };
    let mut storage = vec![0_usize; (size as usize).div_ceil(std::mem::size_of::<usize>())];
    unsafe {
        GetTokenInformation(HANDLE(token.as_raw_handle()), TokenUser, Some(storage.as_mut_ptr().cast()), size, &mut size)?;
        let user = &*storage.as_ptr().cast::<TOKEN_USER>();
        let mut text = windows::core::PWSTR::null();
        ConvertSidToStringSidW(user.User.Sid, &mut text)?;
        let sid = text.to_string();
        let _ = LocalFree(HLOCAL(text.0.cast()));
        Ok(sid?)
    }
}

/// Must run before runtime threads. Keep root effective privileges and allow the
/// owner's real UID to send SIGKILL independently of the daemon's runtime.
#[cfg(unix)]
pub fn allow_owner_signals(owner_uid: u32) -> anyhow::Result<()> {
    if unsafe { libc::setreuid(owner_uid, 0) } != 0 {
        return Err(std::io::Error::last_os_error()).context("configure daemon signal ownership");
    }
    anyhow::ensure!(unsafe { libc::getuid() } == owner_uid && unsafe { libc::geteuid() } == 0, "invalid daemon UIDs");
    Ok(())
}
