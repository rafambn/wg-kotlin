#![cfg(unix)]

use daemon::owner::OwnerProcess;
use std::{fs, process::Command};

fn expected_preparation_error() -> String {
    let effective_uid = unsafe { libc::geteuid() };
    if effective_uid == 0 {
        "invalid owner PID".into()
    } else {
        format!("wg-kotlin-daemon requires root privileges on Unix (effective uid = {effective_uid})")
    }
}

#[test]
fn invalid_owner_preparation_checks_privileges_first_and_keeps_credentials() {
    let real_uid = unsafe { libc::getuid() };
    let effective_uid = unsafe { libc::geteuid() };
    let error = OwnerProcess::prepare(0, 0).err().unwrap();
    assert_eq!(error.to_string(), expected_preparation_error());
    assert_eq!(unsafe { libc::getuid() }, real_uid);
    assert_eq!(unsafe { libc::geteuid() }, effective_uid);
}

#[test]
fn startup_logs_owner_preparation_failure_before_platform_initialization() {
    let directory = tempfile::tempdir().unwrap();
    let endpoint = directory.path().join("ipc.sock");
    let log = directory.path().join("daemon.log");
    let output = Command::new(env!("CARGO_BIN_EXE_wg-kotlin-daemon"))
        .arg("--endpoint")
        .arg(&endpoint)
        .args(["--owner-pid", "0", "--owner-start-time", "0", "--log-path"])
        .arg(&log)
        .env("PATH", "")
        .output()
        .unwrap();
    assert_eq!(output.status.code(), Some(1));
    assert!(output.stdout.is_empty(), "logs must use the requested file");
    assert!(output.stderr.is_empty(), "{}", String::from_utf8_lossy(&output.stderr));
    let contents = fs::read_to_string(log).unwrap();
    assert!(contents.contains(&format!("daemon failed: {}", expected_preparation_error())), "{contents}");
    assert!(!endpoint.exists());
}

#[test]
fn unavailable_log_output_reports_its_original_cause_to_stdout_before_exiting() {
    for parent_is_file in [false, true] {
        let directory = tempfile::tempdir().unwrap();
        let endpoint = directory.path().join("ipc.sock");
        let occupied = directory.path().join("occupied");
        let log = if parent_is_file {
            fs::write(&occupied, b"keep this file").unwrap();
            occupied.join("daemon.log")
        } else {
            fs::create_dir(&occupied).unwrap();
            occupied.clone()
        };
        let expected = daemon::logging::Logger::new(Some(&log)).err().expect("the requested log destination must be unusable");
        let output = Command::new(env!("CARGO_BIN_EXE_wg-kotlin-daemon"))
            .arg("--endpoint")
            .arg(&endpoint)
            .args(["--owner-pid", "0", "--owner-start-time", "0", "--log-path"])
            .arg(&log)
            .env("PATH", "")
            .output()
            .unwrap();
        assert_eq!(output.status.code(), Some(1));
        let stdout = String::from_utf8(output.stdout).unwrap();
        assert!(stdout.contains(&format!("cannot open log output: {expected}")), "{stdout}");
        assert_eq!(stdout.lines().count(), 1, "{stdout}");
        assert!(output.stderr.is_empty(), "{}", String::from_utf8_lossy(&output.stderr));
        assert!(!endpoint.exists());
        if parent_is_file {
            assert_eq!(fs::read(&occupied).unwrap(), b"keep this file");
        } else {
            assert_eq!(fs::read_dir(&occupied).unwrap().count(), 0);
        }
    }
}

#[cfg(target_os = "linux")]
#[test]
#[ignore = "isolated Linux root with SETUID and resolvectl; changes credentials only in children"]
fn prepared_daemon_keeps_effective_root_and_accepts_sigkill_from_the_owner_uid() {
    use std::os::unix::process::{CommandExt, ExitStatusExt};
    use std::process::{Child, Stdio};
    use std::time::{Duration, Instant};

    struct ChildGuard(Child);
    impl Drop for ChildGuard {
        fn drop(&mut self) {
            let _ = self.0.kill();
            let _ = self.0.wait();
        }
    }
    let uids = |status: &str| {
        status
            .lines()
            .find_map(|line| line.strip_prefix("Uid:"))
            .unwrap()
            .split_whitespace()
            .map(|uid| uid.parse::<u32>().unwrap())
            .collect::<Vec<_>>()
    };
    assert_eq!(unsafe { libc::geteuid() }, 0);
    let parent_real_uid = unsafe { libc::getuid() };
    let owner_uid = 65534;
    let owner = ChildGuard(Command::new("/bin/sleep").arg("30").uid(owner_uid).spawn().unwrap());
    let owner_stat = fs::read_to_string(format!("/proc/{}/stat", owner.0.id())).unwrap();
    let birth = owner_stat.rsplit_once(')').unwrap().1.split_whitespace().nth(19).unwrap();
    let sockets = tempfile::Builder::new().prefix("wg-kotlin-owner-signals-").tempdir_in("/tmp").unwrap();
    fs::remove_dir(sockets.path()).unwrap(); // The daemon must create its own socket directory.
    let endpoint = sockets.path().join("ipc.sock");
    let logs = tempfile::tempdir().unwrap();
    let log = logs.path().join("daemon.log");
    let mut daemon = ChildGuard(
        Command::new(env!("CARGO_BIN_EXE_wg-kotlin-daemon"))
            .arg("--endpoint")
            .arg(&endpoint)
            .args(["--owner-pid", &owner.0.id().to_string(), "--owner-start-time", birth, "--log-path"])
            .arg(&log)
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap(),
    );
    let deadline = Instant::now() + Duration::from_secs(3);
    while !endpoint.exists() {
        assert!(daemon.0.try_wait().unwrap().is_none(), "{}", fs::read_to_string(&log).unwrap_or_default());
        assert!(Instant::now() < deadline, "daemon did not bind IPC");
        std::thread::sleep(Duration::from_millis(10));
    }
    let status = fs::read_to_string(format!("/proc/{}/status", daemon.0.id())).unwrap();
    assert_eq!(&uids(&status)[..2], &[owner_uid, 0]);
    let sender = Command::new("/bin/sh")
        .args(["-c", "set -e; cat /proc/$$/status; kill -KILL \"$1\"", "owner-signal", &daemon.0.id().to_string()])
        .uid(owner_uid)
        .output()
        .unwrap();
    assert!(sender.status.success(), "{}", String::from_utf8_lossy(&sender.stderr));
    let sender_status = String::from_utf8(sender.stdout).unwrap();
    assert_eq!(&uids(&sender_status)[..2], &[owner_uid, owner_uid]);
    let capabilities = sender_status.lines().find_map(|line| line.strip_prefix("CapEff:")).unwrap().trim();
    assert_eq!(u64::from_str_radix(capabilities, 16).unwrap(), 0);
    let deadline = Instant::now() + Duration::from_secs(3);
    loop {
        if let Some(status) = daemon.0.try_wait().unwrap() {
            assert_eq!(status.signal(), Some(libc::SIGKILL));
            break;
        }
        assert!(Instant::now() < deadline, "owner UID could not terminate the daemon");
        std::thread::sleep(Duration::from_millis(10));
    }
    assert_eq!(unsafe { libc::getuid() }, parent_real_uid);
    assert_eq!(unsafe { libc::geteuid() }, 0);
}
