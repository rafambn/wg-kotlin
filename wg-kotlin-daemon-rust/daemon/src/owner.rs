#[cfg(unix)]
use anyhow::Context;

/// Pins the app instance, not just its reusable numeric PID. The app supplies
/// boot-relative start ticks on Linux, or ProcessHandle's epoch milliseconds on
/// macOS/Windows, checked against the OS before binding.
pub struct OwnerProcess {
    pub pid: u32,
    #[cfg(unix)]
    pub uid: u32,
    // The main task owns this checker; connection tasks receive only the service.
    check_alive: Box<dyn Fn(u32) -> bool>,
    #[cfg(windows)]
    pub sid: String,
}

impl OwnerProcess {
    /// Prepare privileges and identity before starting any worker threads.
    /// On Unix, keep root effective privileges and let the owner's real UID send SIGKILL.
    pub fn prepare(pid: u32, start_time: u64) -> anyhow::Result<Self> {
        #[cfg(unix)]
        {
            let effective_uid = unsafe { libc::geteuid() };
            anyhow::ensure!(effective_uid == 0, "wg-kotlin-daemon requires root privileges on Unix (effective uid = {effective_uid})");
        }
        #[cfg(windows)]
        {
            let elevated = nt_token::OwnedToken::from_current_process(windows::Win32::Security::TOKEN_QUERY)?.is_elevated()?;
            anyhow::ensure!(elevated, "wg-kotlin-daemon requires Administrator privileges on Windows");
        }
        let owner = Self::open(pid, start_time)?;
        #[cfg(unix)]
        {
            if unsafe { libc::setreuid(owner.uid, 0) } != 0 {
                return Err(std::io::Error::last_os_error()).context("configure daemon signal ownership");
            }
            anyhow::ensure!(unsafe { libc::getuid() } == owner.uid && unsafe { libc::geteuid() } == 0, "invalid daemon UIDs");
        }
        Ok(owner)
    }

    pub fn open(pid: u32, start_time: u64) -> anyhow::Result<Self> {
        let max_pid = if cfg!(unix) { i32::MAX as u32 } else { u32::MAX };
        anyhow::ensure!((1..=max_pid).contains(&pid), "invalid owner PID");
        #[cfg(target_os = "linux")]
        let owner = {
            use procfs_core::{FromRead, process};
            use rustix::process::{Pid, PidfdFlags, pidfd_open};
            let pidfd = pidfd_open(Pid::from_raw(pid as i32).unwrap(), PidfdFlags::empty()).context("open owner pidfd (Linux 5.3+ required)")?;
            // Keep native I/O errors and strict UTF-8; procfs-core decodes Stat lossily.
            let uid = process::Status::from_read(std::fs::read_to_string(format!("/proc/{pid}/status"))?.as_bytes())?.ruid;
            let ticks = process::Stat::from_read(std::fs::read_to_string(format!("/proc/{pid}/stat"))?.as_bytes())?.starttime;
            anyhow::ensure!(ticks == start_time, "owner process creation time mismatch");
            let check_alive = Box::new(move |_| {
                use rustix::event::{PollFd, PollFlags, Timespec, poll};
                poll(&mut [PollFd::new(&pidfd, PollFlags::IN)], Some(&Timespec::default())) == Ok(0)
            });
            Self { pid, uid, check_alive }
        };
        #[cfg(target_os = "macos")]
        let owner = {
            let info = macos_info(pid).context("read owner process identity")?;
            anyhow::ensure!(info.pbi_start_tvsec * 1000 + info.pbi_start_tvusec / 1000 == start_time, "owner process creation time mismatch");
            let birth = (info.pbi_start_tvsec, info.pbi_start_tvusec);
            let check_alive = Box::new(move |pid| macos_info(pid).is_some_and(|info| (info.pbi_start_tvsec, info.pbi_start_tvusec) == birth));
            Self { pid, uid: info.pbi_ruid, check_alive }
        };
        #[cfg(windows)]
        let owner = {
            use windows::Win32::System::Threading::*;
            let process = unsafe { windows::core::Owned::new(OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_SYNCHRONIZE, false, pid)?) };
            let ticks = windows_start_time(*process)?;
            anyhow::ensure!(ticks / 10_000 - 11_644_473_600_000 == start_time, "owner process creation time mismatch");
            let mut token = windows::core::Owned::<windows::Win32::Foundation::HANDLE>::default();
            unsafe { OpenProcessToken(*process, windows::Win32::Security::TOKEN_QUERY, &mut *token)? };
            let sid = unsafe { nt_token::Token::new(&token) }.user()?.to_string()?;
            drop(token);
            Self { pid, check_alive: Box::new(move |_| unsafe { WaitForSingleObject(*process, 0) == windows::Win32::Foundation::WAIT_TIMEOUT }), sid }
        };
        #[cfg(not(target_os = "macos"))]
        anyhow::ensure!(owner.is_alive(), "owner has exited");
        Ok(owner)
    }

    pub fn authorizes(&self, peer_pid: u32) -> bool {
        peer_pid == self.pid && self.is_alive()
    }

    pub fn is_alive(&self) -> bool {
        (self.check_alive)(self.pid)
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

/// Native FILETIME ticks; callers choose epoch milliseconds or an exact process identity.
#[cfg(windows)]
pub fn windows_start_time(process: windows::Win32::Foundation::HANDLE) -> windows::core::Result<u64> {
    use windows::Win32::Foundation::FILETIME;
    let [mut creation, mut exit, mut kernel, mut user] = [FILETIME::default(); 4];
    unsafe { windows::Win32::System::Threading::GetProcessTimes(process, &mut creation, &mut exit, &mut kernel, &mut user)? };
    Ok(((creation.dwHighDateTime as u64) << 32) | creation.dwLowDateTime as u64)
}

#[cfg(all(test, windows))]
#[path = "../tests/unit/windows_owner_tests.rs"]
mod windows_owner_tests;

#[cfg(all(test, windows))]
#[path = "../tests/unit/windows_privilege_tests.rs"]
mod windows_privilege_tests;
