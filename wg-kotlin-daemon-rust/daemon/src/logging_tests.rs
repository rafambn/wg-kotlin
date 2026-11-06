use super::*;
use crate::server::DaemonService;
use clap::Parser;
use std::process::{Child, Command, Output, Stdio};
use std::time::Instant;

#[derive(Parser)]
struct Arguments {
    #[command(flatten)]
    logging: LogOptions,
}

struct LogDirectory(PathBuf);

impl LogDirectory {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!("wg-kotlin-log-context-{}", scribe_rs::scroll::new_scroll_id()));
        fs::create_dir(&path).unwrap();
        Self(path)
    }

    fn log(&self) -> PathBuf {
        self.0.join("nested").join("daemon.jsonl")
    }
}

impl Drop for LogDirectory {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn json_lines(bytes: &[u8]) -> Vec<Value> {
    String::from_utf8_lossy(bytes).lines().filter(|line| line.starts_with('{')).map(|line| serde_json::from_str(line).unwrap()).collect()
}

fn fixture(mode: &str, path: &std::path::Path) -> Child {
    Command::new(std::env::current_exe().unwrap())
        .args(["--exact", "logging::tests::logging_fixture", "--nocapture"])
        .env("WG_KOTLIN_LOG_MODE", mode)
        .env("WG_KOTLIN_LOG_PATH", path)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap()
}

fn finish(mut child: Child) -> Output {
    let started = Instant::now();
    loop {
        if child.try_wait().unwrap().is_some() {
            break;
        }
        if started.elapsed() > Duration::from_secs(5) {
            child.kill().unwrap();
            let output = child.wait_with_output().unwrap();
            panic!("log fixture timed out: {}", String::from_utf8_lossy(&output.stderr));
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    let output = child.wait_with_output().unwrap();
    assert!(output.status.success(), "{}", String::from_utf8_lossy(&output.stderr));
    output
}

struct FailingWriter {
    fail_flush: bool,
}
impl Write for FailingWriter {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        if self.fail_flush { Ok(bytes.len()) } else { Err(io::Error::new(io::ErrorKind::BrokenPipe, "fixture writer failed")) }
    }
    fn flush(&mut self) -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::BrokenPipe, "fixture flush failed"))
    }
}

struct StartedWriter {
    started: Option<tokio::sync::oneshot::Sender<()>>,
    target: LogTarget,
}
impl Write for StartedWriter {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        if let Some(started) = self.started.take() {
            let _ = started.send(());
        }
        match self.target {
            LogTarget::Stdout => io::stdout().write(bytes),
            LogTarget::Stderr => io::stderr().write(bytes),
            LogTarget::File => unreachable!(),
        }
    }
    fn flush(&mut self) -> io::Result<()> {
        match self.target {
            LogTarget::Stdout => io::stdout().flush(),
            LogTarget::Stderr => io::stderr().flush(),
            LogTarget::File => unreachable!(),
        }
    }
}

#[test]
fn logging_fixture() {
    let Ok(mode) = std::env::var("WG_KOTLIN_LOG_MODE") else { return };
    if mode.starts_with("blocked") {
        let target = if mode == "blocked-stderr" { LogTarget::Stderr } else { LogTarget::Stdout };
        let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        runtime.block_on(async {
            use crate::ipc::{read_frame, write_frame};
            use daemon_proto::pb::{ClientMessage, ServerMessage, client_message};
            let (started, writing) = tokio::sync::oneshot::channel();
            let mut scribe = base_scribe().saver(Arc::new(JsonSaver::new(target, StartedWriter { started: Some(started), target }))).build();
            scribe.hire();
            let service = DaemonService::new(Arc::new(tokio::sync::Mutex::new(scribe)));
            service
                .seal_scroll(
                    LogComponent::Session,
                    LogLevel::Info,
                    "blocked_output",
                    [("message".into(), Value::from("x".repeat(3 * 1024 * 1024)))],
                    true,
                )
                .await;
            writing.await.unwrap();
            let (mut client, server) = tokio::io::duplex(65536);
            let (shutdown, stopped) = tokio::sync::watch::channel(false);
            let handler = {
                let service = service.clone();
                tokio::spawn(async move { service.serve_connection(server, shutdown).await })
            };
            assert!(read_frame::<ServerMessage>(&mut client).await.unwrap().is_some());
            write_frame(&mut client, &ClientMessage { payload: Some(client_message::Payload::Shutdown(true)) }).await.unwrap();
            handler.await.unwrap().unwrap();
            assert!(*stopped.borrow());
            service.retire_logs().await;
        });
        drop(runtime);
        if mode == "blocked-stderr" {
            report_fatal_error("fixture terminal error".to_string());
        }
        std::process::exit(0);
    }
    let path = PathBuf::from(std::env::var_os("WG_KOTLIN_LOG_PATH").unwrap());
    if mode == "fatal" {
        report_fatal_error("fixture fatal error".to_string());
        std::process::exit(0);
    }
    let scribe = if mode.starts_with("fail-") {
        let target = match mode.as_str() {
            "fail-stderr" => LogTarget::Stderr,
            "fail-stdout" => LogTarget::Stdout,
            _ => LogTarget::File,
        };
        base_scribe()
            .filter(|scroll| scroll.data["component"] == "session")
            .saver(Arc::new(JsonSaver::new(target, FailingWriter { fail_flush: mode == "fail-flush" })))
            .build()
    } else {
        let targets = if mode == "bad-path" {
            vec![LogTarget::File]
        } else {
            mode.split(',').map(|value| LogTarget::from_str(value, false).unwrap()).collect()
        };
        create_daemon_scribe(&LogOptions { targets, log_path: Some(path), ..Default::default() })
    };
    let mut scroll = scribe.new_scroll(None);
    scroll.insert("event".into(), Value::from("fixture_event"));
    scroll.insert("component".into(), Value::from("session"));
    scroll.insert("session_id".into(), Value::from("fixture-session"));
    scribe.seal(scroll, true);
    std::process::exit(0);
}

#[test]
fn cli_options_select_targets_levels_and_components() {
    let defaults = Arguments::try_parse_from(["daemon"]).unwrap().logging;
    assert_eq!(defaults.targets, [LogTarget::File]);
    assert_eq!(defaults.level, LogLevel::Info);
    let options = Arguments::try_parse_from([
        "daemon",
        "--log-target",
        "stdout,stderr",
        "--log-target",
        "file",
        "--log-level",
        "warn",
        "--log-component",
        "session,ipc",
        "--log-path",
        "custom.jsonl",
    ])
    .unwrap()
    .logging;
    assert_eq!(options.targets, [LogTarget::Stdout, LogTarget::Stderr, LogTarget::File]);
    assert_eq!(options.components, [LogComponent::Session, LogComponent::Ipc]);
    assert_eq!(options.level, LogLevel::Warn);
    assert_eq!(options.log_path, Some(PathBuf::from("custom.jsonl")));
    assert!(Arguments::try_parse_from(["daemon", "--log-target", "invalid"]).is_err());
    assert!(Arguments::try_parse_from(["daemon", "--log-level", "invalid"]).is_err());
    assert!(Arguments::try_parse_from(["daemon", "--log-component", "invalid"]).is_err());
    assert!(default_log_path().unwrap().is_absolute());
}

#[test]
fn targets_receive_jsonl_with_context_and_duplicates_are_removed() {
    for mode in ["stdout", "stderr", "file", "file,stdout,stderr,file"] {
        let directory = LogDirectory::new();
        let output = finish(fixture(mode, &directory.log()));
        let stdout = json_lines(&output.stdout);
        let stderr = json_lines(&output.stderr);
        assert_eq!(stdout.len(), usize::from(mode.contains("stdout")));
        assert_eq!(stderr.len(), usize::from(mode.contains("stderr")));
        let file = if mode.contains("file") {
            json_lines(&fs::read(directory.log()).unwrap())
        } else {
            assert!(!directory.log().exists());
            Vec::new()
        };
        assert_eq!(file.len(), usize::from(mode.contains("file")));
        for record in stdout.iter().chain(&stderr).chain(&file) {
            assert_eq!(record["data"]["event"], "fixture_event");
            assert_eq!(record["data"]["component"], "session");
            assert_eq!(record["data"]["level"], "info");
            assert_eq!(record["data"]["session_id"], "fixture-session");
            assert!(record["data"]["timestamp"].as_u64().unwrap() > 0);
            assert!(record["data"]["pid"].as_u64().unwrap() > 0);
        }
    }
}

#[test]
fn failed_targets_fall_back_to_stderr_without_recursion() {
    let directory = LogDirectory::new();
    for mode in ["fail-file", "fail-stdout", "fail-flush", "bad-path"] {
        let output = finish(fixture(mode, &directory.0));
        let records = json_lines(&output.stderr);
        assert_eq!(records.len(), 2);
        assert_eq!(records[0]["data"]["event"], "daemon_log_error");
        assert_eq!(records[0]["data"]["component"], "logging");
        assert_eq!(records[0]["data"]["level"], "error");
        assert_eq!(records[1]["data"]["event"], "fixture_event");
        if mode.starts_with("fail-") {
            assert_eq!(records[0]["data"]["session_id"], "fixture-session");
        }
    }
    let output = finish(fixture("fail-stderr", &directory.log()));
    assert!(output.stderr.is_empty());
    let output = finish(fixture("fatal", &directory.log()));
    let records = json_lines(&output.stderr);
    assert_eq!(records.len(), 1);
    assert_eq!(records[0]["data"]["event"], "daemon_error");
    assert_eq!(records[0]["data"]["component"], "daemon");
    assert_eq!(records[0]["data"]["level"], "error");
}

#[tokio::test]
async fn severity_and_component_filters_work_together() {
    let directory = LogDirectory::new();
    let mut scribe = create_daemon_scribe(&LogOptions {
        targets: vec![LogTarget::File],
        log_path: Some(directory.log()),
        level: LogLevel::Warn,
        components: vec![LogComponent::Session],
    });
    scribe.hire();
    let service = DaemonService::new(Arc::new(tokio::sync::Mutex::new(scribe)));
    for (component, level, event) in [
        (LogComponent::Daemon, LogLevel::Error, "process_error"),
        (LogComponent::Session, LogLevel::Info, "session_info"),
        (LogComponent::Session, LogLevel::Warn, "session_warning"),
        (LogComponent::Session, LogLevel::Error, "session_error"),
    ] {
        service.seal_scroll(component, level, event, [], false).await;
    }
    service.retire_logs().await;
    let records = json_lines(&fs::read(directory.log()).unwrap());
    let events: Vec<_> = records.iter().map(|record| record["data"]["event"].as_str().unwrap()).collect();
    assert_eq!(events, ["session_warning", "session_error"]);
}

#[test]
fn unread_output_does_not_block_ipc_or_runtime_shutdown() {
    let directory = LogDirectory::new();
    let output = finish(fixture("blocked", &directory.log()));
    assert!(json_lines(&output.stderr).iter().any(|record| record["data"]["target"] == "flush"));
    let output = finish(fixture("blocked-stderr", &directory.log()));
    assert!(!output.stderr.is_empty());
}
