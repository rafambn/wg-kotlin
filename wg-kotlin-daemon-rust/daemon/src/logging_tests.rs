use super::*;
use crate::ipc::{read_frame, write_frame};
use crate::server::DaemonService;
use daemon_proto::pb::{ClientMessage, ServerMessage, client_message};
use std::process::{Child, Command, Output, Stdio};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Instant;

struct LogDirectory(std::path::PathBuf);

impl LogDirectory {
    fn new() -> Self {
        static NEXT_ID: AtomicUsize = AtomicUsize::new(0);
        let path = std::env::temp_dir().join(format!("wg-kotlin-logs-{}-{}", std::process::id(), NEXT_ID.fetch_add(1, Ordering::Relaxed)));
        fs::create_dir(&path).unwrap();
        Self(path)
    }
}

impl Drop for LogDirectory {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn fixture(mode: &str, path: &Path) -> Child {
    Command::new(std::env::current_exe().unwrap())
        .args(["--exact", "logging::tests::log_fixture", "--ignored", "--nocapture"])
        .env("WG_LOG_TEST_MODE", mode)
        .env("WG_LOG_TEST_PATH", path)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap()
}

fn finish(mut child: Child) -> Output {
    let deadline = Instant::now() + Duration::from_secs(5);
    while child.try_wait().unwrap().is_none() {
        if Instant::now() >= deadline {
            child.kill().unwrap();
            let _ = child.wait();
            panic!("logging fixture did not exit");
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    let output = child.wait_with_output().unwrap();
    assert!(output.status.success(), "{}", String::from_utf8_lossy(&output.stderr));
    output
}

#[test]
#[ignore = "subprocess fixture"]
fn log_fixture() {
    let mode = std::env::var("WG_LOG_TEST_MODE").unwrap();
    let path = std::env::var_os("WG_LOG_TEST_PATH").unwrap();
    let logger = Logger::new((mode == "file").then(|| Path::new(&path))).unwrap();
    if mode == "blocked" {
        logger.log("x".repeat(1024 * 1024));
        let service = DaemonService::new(logger.clone());
        tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(async {
            let (mut client, stream) = tokio::io::duplex(1024);
            let (shutdown, mut stopped) = tokio::sync::watch::channel(false);
            let connection = tokio::spawn(async move { service.serve_connection(stream, shutdown).await });
            read_frame::<ServerMessage>(&mut client).await.unwrap().unwrap();
            write_frame(&mut client, &ClientMessage { payload: Some(client_message::Payload::Shutdown(true)) }).await.unwrap();
            tokio::time::timeout(Duration::from_secs(2), stopped.wait_for(|value| *value)).await.unwrap().unwrap();
            connection.await.unwrap().unwrap();
        });
    } else {
        logger.log("session started interface=utun7\nsecond line");
    }
    logger.flush();
    std::process::exit(0);
}

#[test]
fn defaults_to_stdout_and_uses_only_the_requested_file() {
    let directory = LogDirectory::new();
    let path = directory.0.join("nested/daemon.log");
    let stdout = finish(fixture("stdout", &path));
    assert!(stdout.stderr.is_empty());
    let text = String::from_utf8(stdout.stdout).unwrap();
    assert!(text.contains("pid="));
    assert!(text.contains("session started interface=utun7\\nsecond line"));
    assert!(!path.exists());
    for _ in 0..2 {
        let output = finish(fixture("file", &path));
        assert!(!String::from_utf8_lossy(&output.stdout).contains("session started"));
        assert!(output.stderr.is_empty());
    }
    let text = fs::read_to_string(path).unwrap();
    assert_eq!(text.lines().count(), 2);
    assert!(text.lines().all(|line| line.contains("session started interface=utun7\\nsecond line")));
    assert!(Logger::new(Some(&directory.0)).is_err());
}

#[test]
fn unread_stdout_does_not_block_ipc_or_shutdown() {
    let directory = LogDirectory::new();
    finish(fixture("blocked", &directory.0));
}

#[test]
fn failed_output_does_not_block_the_caller() {
    struct FailedWriter;
    impl Write for FailedWriter {
        fn write(&mut self, _: &[u8]) -> io::Result<usize> {
            Err(io::Error::other("output unavailable"))
        }
        fn flush(&mut self) -> io::Result<()> {
            Ok(())
        }
    }
    let logger = Logger::with_writer(FailedWriter).unwrap();
    logger.log("first");
    logger.flush();
    logger.log("second");
    logger.flush();
}
