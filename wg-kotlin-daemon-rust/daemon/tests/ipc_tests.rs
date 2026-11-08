use daemon::ipc::{MAX_FRAME_SIZE, read_frame, write_frame};
use daemon_proto::pb::{ClientMessage, Packet, client_message};
use tokio::io::AsyncWriteExt;

#[cfg(windows)]
#[tokio::test]
async fn windows_listener_recovers_from_abandoned_clients_and_keeps_pending_name() {
    use daemon::{ipc::LocalListener, owner::OwnerProcess};
    use std::time::Duration;
    use tokio::io::AsyncReadExt;
    use tokio::net::windows::named_pipe::{ClientOptions, NamedPipeClient, ServerOptions};
    use tokio::time::{sleep, timeout};
    use windows::Win32::System::Threading::GetCurrentProcess;

    async fn connect(endpoint: &str) -> NamedPipeClient {
        timeout(Duration::from_secs(3), async {
            loop {
                match ClientOptions::new().open(endpoint) {
                    Ok(client) => break client,
                    Err(error) if error.raw_os_error() == Some(231) => sleep(Duration::from_millis(1)).await,
                    Err(error) => panic!("opening test pipe: {error}"),
                }
            }
        })
        .await
        .expect("test pipe remained busy")
    }

    let start_time = daemon::owner::windows_start_time(unsafe { GetCurrentProcess() }).unwrap() / 10_000 - 11_644_473_600_000;
    let owner = OwnerProcess::open(std::process::id(), start_time).unwrap();
    let error = LocalListener::bind(r"\\.\pipe\invalid-endpoint", &owner).err().unwrap();
    let native = error.downcast_ref::<std::io::Error>().unwrap();
    assert_eq!(native.kind(), std::io::ErrorKind::Other);
    assert_eq!(native.to_string(), "invalid local named pipe endpoint");
    let endpoint = format!(r"\\.\pipe\wg-kotlin-ipc-recovery-{}", owner.pid);
    let mut listener = LocalListener::bind(&endpoint, &owner).unwrap();
    let mut tasks = tokio::task::JoinSet::new();
    tasks.spawn(async move {
        loop {
            let (mut stream, pid) = listener.accept().await?;
            if matches!(stream.read_u8().await, Ok(1)) {
                return Ok::<_, anyhow::Error>((listener, stream, pid));
            }
        }
    });
    for _ in 0..8 {
        drop(connect(&endpoint).await);
        tokio::task::yield_now().await;
    }
    let mut client = connect(&endpoint).await;
    client.write_u8(1).await.unwrap();
    let (listener, stream, pid) = timeout(Duration::from_secs(3), tasks.join_next()).await.unwrap().unwrap().unwrap().unwrap();
    assert_eq!(pid, owner.pid);
    drop(stream);
    drop(client);
    let expected = ServerOptions::new().first_pipe_instance(true).create(&endpoint).expect_err("the pending instance must still own the name");
    let error = match LocalListener::bind(&endpoint, &owner) {
        Ok(_) => panic!("a second listener must not replace the first"),
        Err(error) => error,
    };
    let native = error.downcast_ref::<std::io::Error>().unwrap();
    assert_eq!(native.kind(), expected.kind());
    assert_eq!(native.raw_os_error(), expected.raw_os_error());
    drop(listener);
}

#[cfg(unix)]
#[tokio::test]
async fn unix_listener_preserves_native_errors_and_removes_only_its_socket() {
    use daemon::{ipc::LocalListener, owner::OwnerProcess};
    use std::time::{SystemTime, UNIX_EPOCH};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let pid = std::process::id();
    #[cfg(target_os = "linux")]
    let start_time = {
        let stat = std::fs::read_to_string(format!("/proc/{pid}/stat")).unwrap();
        stat.rsplit_once(')').unwrap().1.split_whitespace().nth(19).unwrap().parse().unwrap()
    };
    #[cfg(target_os = "macos")]
    let start_time = {
        let mut info: libc::proc_bsdinfo = unsafe { std::mem::zeroed() };
        let size = std::mem::size_of_val(&info) as i32;
        assert_eq!(unsafe { libc::proc_pidinfo(pid as i32, libc::PROC_PIDTBSDINFO, 0, &mut info as *mut _ as *mut _, size) }, size);
        info.pbi_start_tvsec * 1000 + info.pbi_start_tvusec / 1000
    };
    let owner = OwnerProcess::open(pid, start_time).unwrap();
    for invalid in
        ["", "tmp/wg-kotlin-test/ipc.sock", "/tmp/not-wg-kotlin/ipc.sock", "/var/tmp/wg-kotlin-test/ipc.sock", "/tmp/wg-kotlin-test/nested/ipc.sock"]
    {
        let error = LocalListener::bind(invalid, &owner).err().unwrap();
        let native = error.downcast_ref::<std::io::Error>().unwrap();
        assert_eq!(native.kind(), std::io::ErrorKind::Other);
        let expected = if invalid.is_empty() { "missing socket directory" } else { "IPC socket requires its own /tmp/wg-kotlin-* directory" };
        assert_eq!(native.to_string(), expected);
    }
    let nonce = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
    let directory = std::path::PathBuf::from(format!("/tmp/wg-kotlin-ipc-errors-{pid}-{nonce}"));
    let path = directory.join("ipc.sock");
    let listener = LocalListener::bind(path.to_str().unwrap(), &owner).unwrap();
    let error = LocalListener::bind(path.to_str().unwrap(), &owner).err().unwrap();
    let native = error.downcast_ref::<std::io::Error>().unwrap();
    assert_eq!(native.kind(), std::io::ErrorKind::AlreadyExists);
    assert_eq!(native.raw_os_error(), Some(libc::EEXIST));
    assert!(path.exists());
    drop(listener);
    assert!(!directory.exists());

    let mut listener = LocalListener::bind(path.to_str().unwrap(), &owner).unwrap();
    let mut client = tokio::net::UnixStream::connect(&path).await.unwrap();
    let (mut accepted, _) = listener.accept().await.unwrap();
    let unrelated = directory.join("unrelated.txt");
    std::fs::write(&unrelated, b"keep this file").unwrap();
    drop(listener);
    assert!(!path.exists());
    assert_eq!(std::fs::read(&unrelated).unwrap(), b"keep this file");
    tokio::time::timeout(std::time::Duration::from_secs(5), async {
        let mut byte = [0];
        client.write_all(b"a").await.unwrap();
        accepted.read_exact(&mut byte).await.unwrap();
        assert_eq!(&byte, b"a");
        accepted.write_all(b"b").await.unwrap();
        client.read_exact(&mut byte).await.unwrap();
        assert_eq!(&byte, b"b");
    })
    .await
    .unwrap();
    drop((client, accepted));
    std::fs::remove_file(unrelated).unwrap();
    std::fs::remove_dir(&directory).unwrap();

    let too_long = directory.join("s".repeat(256));
    assert!(LocalListener::bind(too_long.to_str().unwrap(), &owner).is_err());
    assert!(!directory.exists(), "failed bind must release the directory it created");
}

#[cfg(target_os = "macos")]
#[test]
fn macos_ipc_peer_fixture() {
    use std::io::Read;
    let Some(path) = std::env::var_os("WG_KOTLIN_PEER_FIXTURE") else { return };
    let _stream = std::os::unix::net::UnixStream::connect(path).unwrap();
    std::io::stdin().read_to_end(&mut Vec::new()).unwrap();
}

#[cfg(target_os = "macos")]
#[tokio::test]
async fn macos_listener_identifies_owner_and_foreign_peers_and_survives_disconnects() {
    use daemon::{ipc::LocalListener, owner::OwnerProcess};
    use std::time::{Duration, SystemTime, UNIX_EPOCH};
    use tokio::net::UnixStream;

    let pid = std::process::id();
    let mut info: libc::proc_bsdinfo = unsafe { std::mem::zeroed() };
    let size = std::mem::size_of_val(&info) as i32;
    assert_eq!(unsafe { libc::proc_pidinfo(pid as i32, libc::PROC_PIDTBSDINFO, 0, &mut info as *mut _ as *mut _, size) }, size);
    let owner = OwnerProcess::open(pid, info.pbi_start_tvsec * 1000 + info.pbi_start_tvusec / 1000).unwrap();
    let nonce = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
    let directory = std::path::PathBuf::from(format!("/tmp/wg-kotlin-peer-{pid}-{nonce}"));
    let path = directory.join("ipc.sock");
    let mut listener = LocalListener::bind(path.to_str().unwrap(), &owner).unwrap();

    let client = UnixStream::connect(&path).await.unwrap();
    let (stream, peer) = listener.accept().await.unwrap();
    assert_eq!(peer, pid);
    assert!(owner.authorizes(peer));
    drop((client, stream));

    let client = UnixStream::connect(&path).await.unwrap();
    drop(client);
    let (stream, peer) = listener.accept().await.unwrap();
    // The kernel may still return the original PID after a disconnect.
    assert!(peer == 0 || peer == pid);
    drop(stream);

    let mut child = tokio::process::Command::new(std::env::current_exe().unwrap())
        .args(["--exact", "macos_ipc_peer_fixture", "--nocapture"])
        .env("WG_KOTLIN_PEER_FIXTURE", &path)
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .unwrap();
    let (stream, peer) = tokio::time::timeout(Duration::from_secs(5), listener.accept()).await.unwrap().unwrap();
    assert_eq!(peer, child.id().unwrap());
    assert!(!owner.authorizes(peer));
    drop(stream);
    drop(child.stdin.take());
    assert!(tokio::time::timeout(Duration::from_secs(5), child.wait()).await.unwrap().unwrap().success());

    let client = UnixStream::connect(&path).await.unwrap();
    let (stream, peer) = listener.accept().await.unwrap();
    assert!(owner.authorizes(peer));
    drop((client, stream, listener));
    assert!(!path.exists());
    assert!(!directory.exists());
}

#[test]
fn rejects_invalid_or_mismatched_owner_instances() {
    assert!(daemon::owner::OwnerProcess::open(0, 0).is_err());
    assert!(daemon::owner::OwnerProcess::open(std::process::id(), 0).is_err());
    #[cfg(unix)]
    for pid in [i32::MAX as u32 + 1, u32::MAX] {
        assert_eq!(daemon::owner::OwnerProcess::open(pid, 0).err().unwrap().to_string(), "invalid owner PID");
    }
}

#[cfg(unix)]
#[test]
fn owner_lifetime_fixture() {
    use std::io::{Read, Write};
    if std::env::var_os("WG_KOTLIN_OWNER_FIXTURE").is_none() {
        return;
    }
    #[cfg(target_os = "linux")]
    {
        if let Some(name) = std::env::var_os("WG_KOTLIN_OWNER_COMM") {
            use std::os::unix::ffi::OsStrExt;
            std::fs::write("/proc/self/comm", name.as_bytes()).unwrap();
        }
        if let Ok(uid) = std::env::var("WG_KOTLIN_OWNER_RUID") {
            assert_eq!(unsafe { libc::setreuid(uid.parse().unwrap(), 0) }, 0, "{}", std::io::Error::last_os_error());
        }
    }
    println!("owner-ready");
    std::io::stdout().flush().unwrap();
    std::io::stdin().read_to_end(&mut Vec::new()).unwrap();
}

#[cfg(target_os = "macos")]
#[test]
fn macos_owner_stops_authorizing_a_zombie_and_reaped_process() {
    use std::io::BufRead;
    use std::process::{Child, Command, Stdio};
    use std::time::{Duration, Instant};

    struct Fixture(Child);
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = self.0.kill();
            let _ = self.0.wait();
        }
    }
    let mut child = Fixture(
        Command::new(std::env::current_exe().unwrap())
            .args(["--exact", "owner_lifetime_fixture", "--nocapture"])
            .env("WG_KOTLIN_OWNER_FIXTURE", "1")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .unwrap(),
    );
    let mut output = std::io::BufReader::new(child.0.stdout.take().unwrap());
    assert!((&mut output).lines().any(|line| line.unwrap() == "owner-ready"));
    let pid = child.0.id();
    let mut info: libc::proc_bsdinfo = unsafe { std::mem::zeroed() };
    let size = std::mem::size_of_val(&info) as i32;
    assert_eq!(unsafe { libc::proc_pidinfo(pid as i32, libc::PROC_PIDTBSDINFO, 0, &mut info as *mut _ as *mut _, size) }, size);
    let mut owner = daemon::owner::OwnerProcess::open(pid, info.pbi_start_tvsec * 1000 + info.pbi_start_tvusec / 1000).unwrap();
    assert_eq!(owner.uid, info.pbi_ruid);
    assert!(owner.is_alive());
    assert!(owner.authorizes(pid));
    assert!(!owner.authorizes(std::process::id()));
    owner.pid = std::process::id();
    assert!(!owner.is_alive());
    assert!(!owner.authorizes(std::process::id()));
    owner.pid = pid;
    assert!(owner.is_alive());
    drop(child.0.stdin.take());
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        let mut status: libc::siginfo_t = unsafe { std::mem::zeroed() };
        assert_eq!(
            unsafe { libc::waitid(libc::P_PID, pid, &mut status, libc::WEXITED | libc::WNOWAIT | libc::WNOHANG) },
            0,
            "{}",
            std::io::Error::last_os_error()
        );
        if status.si_signo == libc::SIGCHLD {
            break;
        }
        assert!(Instant::now() < deadline, "owner fixture did not exit");
        std::thread::sleep(Duration::from_millis(1));
    }
    assert!(!owner.is_alive());
    assert!(!owner.authorizes(pid));
    assert!(daemon::owner::OwnerProcess::open(pid, info.pbi_start_tvsec * 1000 + info.pbi_start_tvusec / 1000).is_err());
    assert!(child.0.wait().unwrap().success());
    assert!(!owner.is_alive());
    assert!(!owner.authorizes(pid));
}

#[cfg(target_os = "linux")]
#[test]
fn owner_pidfd_stops_authorizing_an_exited_and_reaped_process() {
    use std::io::BufRead;
    use std::process::{Child, Command, Stdio};
    struct Fixture(Child);
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = self.0.kill();
            let _ = self.0.wait();
        }
    }
    let mut child = Fixture(
        Command::new(std::env::current_exe().unwrap())
            .args(["--exact", "owner_lifetime_fixture", "--nocapture"])
            .env("WG_KOTLIN_OWNER_FIXTURE", "1")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .unwrap(),
    );
    let mut output = std::io::BufReader::new(child.0.stdout.take().unwrap());
    assert!((&mut output).lines().any(|line| line.unwrap() == "owner-ready"));
    let pid = child.0.id();
    let stat = std::fs::read_to_string(format!("/proc/{pid}/stat")).unwrap();
    let ticks = stat.rsplit_once(')').unwrap().1.split_whitespace().nth(19).unwrap().parse().unwrap();
    let owner = daemon::owner::OwnerProcess::open(pid, ticks).unwrap();
    assert!(owner.is_alive());
    assert!(owner.authorizes(pid));
    assert!(!owner.authorizes(std::process::id()));
    drop(child.0.stdin.take());
    assert!(child.0.wait().unwrap().success());
    assert!(!owner.is_alive());
    assert!(!owner.authorizes(pid));
}

#[cfg(target_os = "linux")]
#[test]
fn linux_owner_identity_uses_boot_relative_ticks() {
    let pid = std::process::id();
    let stat = std::fs::read_to_string(format!("/proc/{pid}/stat")).unwrap();
    let start_ticks: u64 = stat.rsplit_once(')').unwrap().1.split_whitespace().nth(19).unwrap().parse().unwrap();
    let owner = daemon::owner::OwnerProcess::open(pid, start_ticks).unwrap();
    assert!(owner.authorizes(pid));
    assert!(daemon::owner::OwnerProcess::open(pid, start_ticks + 1).is_err());
}

#[cfg(target_os = "linux")]
#[test]
fn linux_owner_identity_preserves_rejection_of_non_utf8_process_names() {
    use std::io::BufRead;
    use std::os::unix::ffi::OsStringExt;
    use std::process::{Child, Command, Stdio};

    struct Fixture(Child);
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = self.0.kill();
            let _ = self.0.wait();
        }
    }
    let mut child = Fixture(
        Command::new(std::env::current_exe().unwrap())
            .args(["--exact", "owner_lifetime_fixture", "--nocapture"])
            .env("WG_KOTLIN_OWNER_FIXTURE", "1")
            .env("WG_KOTLIN_OWNER_COMM", std::ffi::OsString::from_vec(vec![b'o', 0xff, b' ', b')']))
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .unwrap(),
    );
    let mut output = std::io::BufReader::new(child.0.stdout.take().unwrap());
    assert!((&mut output).lines().any(|line| line.unwrap() == "owner-ready"));
    let pid = child.0.id();
    let stat = std::fs::read(format!("/proc/{pid}/stat")).unwrap();
    let tail = &stat[stat.iter().rposition(|&byte| byte == b')').unwrap() + 1..];
    let ticks = std::str::from_utf8(tail).unwrap().split_whitespace().nth(19).unwrap().parse().unwrap();
    let previous =
        std::fs::read_to_string(format!("/proc/{pid}/status")).and_then(|_| std::fs::read_to_string(format!("/proc/{pid}/stat"))).unwrap_err();
    let error = daemon::owner::OwnerProcess::open(pid, ticks).err().unwrap();
    assert_eq!(previous.kind(), std::io::ErrorKind::InvalidData);
    assert_eq!(error.downcast_ref::<std::io::Error>().unwrap().kind(), previous.kind());
    drop(child.0.stdin.take());
    assert!(child.0.wait().unwrap().success());
}

#[cfg(target_os = "linux")]
#[test]
#[ignore = "requires isolated Linux root with SETUID; the UID change is confined to a child"]
fn linux_owner_identity_uses_real_uid_and_handles_spaces_and_parentheses_in_comm() {
    use std::io::BufRead;
    use std::process::{Child, Command, Stdio};

    assert_eq!(unsafe { libc::geteuid() }, 0);
    struct Fixture(Child);
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = self.0.kill();
            let _ = self.0.wait();
        }
    }
    let mut child = Fixture(
        Command::new(std::env::current_exe().unwrap())
            .args(["--exact", "owner_lifetime_fixture", "--nocapture"])
            .env("WG_KOTLIN_OWNER_FIXTURE", "1")
            .env("WG_KOTLIN_OWNER_COMM", "owner ) 42 (x)")
            .env("WG_KOTLIN_OWNER_RUID", "1234")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .unwrap(),
    );
    let mut output = std::io::BufReader::new(child.0.stdout.take().unwrap());
    assert!((&mut output).lines().any(|line| line.unwrap() == "owner-ready"));
    let pid = child.0.id();
    let stat = std::fs::read_to_string(format!("/proc/{pid}/stat")).unwrap();
    assert!(stat.contains("(owner ) 42 (x))"), "{stat}");
    let status = std::fs::read_to_string(format!("/proc/{pid}/status")).unwrap();
    let uids: Vec<u32> =
        status.lines().find_map(|line| line.strip_prefix("Uid:")).unwrap().split_whitespace().map(|value| value.parse().unwrap()).collect();
    assert_eq!(&uids[..2], &[1234, 0]);
    // Freeze the old extraction independently of the production iterator.
    let fields: Vec<_> = stat.rsplit_once(')').unwrap().1.split_whitespace().collect();
    let ticks = fields[19].parse().unwrap();
    let owner = daemon::owner::OwnerProcess::open(pid, ticks).unwrap();
    assert_eq!(owner.uid, 1234);
    assert!(owner.authorizes(pid));
    assert!(!owner.authorizes(std::process::id()));
    assert!(daemon::owner::OwnerProcess::open(pid, ticks + 1).is_err());
    drop(child.0.stdin.take());
    assert!(child.0.wait().unwrap().success());
    assert!(!owner.is_alive());
    assert!(!owner.authorizes(pid));
}

#[tokio::test]
async fn framed_protobuf_round_trip_and_clean_eof() {
    let (mut client, mut server) = tokio::io::duplex(128);
    let message = ClientMessage { payload: Some(client_message::Payload::OutgoingPacket(Packet { data: vec![1, 2, 3] })) };
    write_frame(&mut client, &message).await.unwrap();
    drop(client);
    assert_eq!(read_frame::<ClientMessage>(&mut server).await.unwrap(), Some(message));
    assert!(read_frame::<ClientMessage>(&mut server).await.unwrap().is_none());
}

#[tokio::test]
async fn rejects_empty_oversized_truncated_and_malformed_frames() {
    use std::io::ErrorKind::{InvalidData, UnexpectedEof};
    for (bytes, expected) in [
        (vec![0, 0, 0, 0], InvalidData),
        (((MAX_FRAME_SIZE + 1) as u32).to_be_bytes().to_vec(), InvalidData),
        (vec![0, 0], UnexpectedEof),
        (vec![0, 0, 0, 4], UnexpectedEof),
        (vec![0, 0, 0, 4, 1], UnexpectedEof),
        (vec![0, 0, 0, 1, 255], InvalidData),
    ] {
        let (mut client, mut server) = tokio::io::duplex(128);
        client.write_all(&bytes).await.unwrap();
        drop(client);
        assert_eq!(read_frame::<ClientMessage>(&mut server).await.unwrap_err().kind(), expected);
    }
}

#[tokio::test]
async fn only_eof_before_a_header_is_clean_and_every_truncated_frame_is_reported() {
    use prost::Message;
    let message = ClientMessage { payload: Some(client_message::Payload::OutgoingPacket(Packet { data: vec![1, 2, 3] })) };
    let payload = message.encode_to_vec();
    let mut wire = (payload.len() as u32).to_be_bytes().to_vec();
    wire.extend(payload);
    for end in 0..=wire.len() {
        let mut input = &wire[..end];
        let result = read_frame::<ClientMessage>(&mut input).await;
        match end {
            0 => assert!(result.unwrap().is_none()),
            end if end == wire.len() => assert_eq!(result.unwrap().unwrap(), message),
            _ => assert_eq!(result.unwrap_err().kind(), std::io::ErrorKind::UnexpectedEof),
        }
    }
}

#[tokio::test]
async fn nonempty_unknown_fields_are_valid_and_decode_failures_keep_their_cause() {
    use prost::Message;
    let mut input = &[0, 0, 0, 2, 0x78, 0x01][..];
    assert_eq!(read_frame::<ClientMessage>(&mut input).await.unwrap(), Some(ClientMessage::default()));
    assert!(read_frame::<ClientMessage>(&mut input).await.unwrap().is_none());
    let cause = ClientMessage::decode(&[0xff][..]).unwrap_err().to_string();
    let mut malformed = &[0, 0, 0, 1, 0xff][..];
    let error = read_frame::<ClientMessage>(&mut malformed).await.unwrap_err();
    assert_eq!(error.kind(), std::io::ErrorKind::InvalidData);
    assert!(error.to_string().contains(&cause), "{error}");
}
