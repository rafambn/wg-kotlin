use super::{MAX_COMMAND_OUTPUT_BYTES, command_output, read_capped, run_command};
use std::time::{Duration, Instant};
use tokio::process::Command;

fn fixture(mode: &str) -> Command {
    let mut command = Command::new(std::env::current_exe().unwrap());
    command.args(["--exact", "platform::command_tests::command_fixture", "--nocapture"]);
    command.env("WG_KOTLIN_COMMAND_FIXTURE", mode);
    command
}

#[tokio::test]
async fn read_failures_keep_the_stream_context_native_cause_and_retained_bytes() {
    use std::{
        pin::Pin,
        task::{Context, Poll},
    };
    use tokio::io::{AsyncRead, AsyncReadExt, ReadBuf};

    struct FailingRead;
    impl AsyncRead for FailingRead {
        fn poll_read(self: Pin<&mut Self>, _: &mut Context<'_>, _: &mut ReadBuf<'_>) -> Poll<std::io::Result<()>> {
            Poll::Ready(Err(std::io::Error::from_raw_os_error(5)))
        }
    }

    for context in ["failed reading stdout", "failed reading stderr"] {
        for size in [3, MAX_COMMAND_OUTPUT_BYTES + 3] {
            let reader = std::io::Cursor::new(vec![b'x'; size]).chain(FailingRead);
            let mut output = Vec::new();
            let error = read_capped(reader, &mut output, context).await.unwrap_err();
            assert_eq!(error.to_string(), context);
            assert_eq!(error.downcast_ref::<std::io::Error>().unwrap().raw_os_error(), Some(5));
            assert_eq!(output.len(), size.min(MAX_COMMAND_OUTPUT_BYTES));
            assert!(output.iter().all(|byte| *byte == b'x'));
        }
    }
}

#[test]
fn command_fixture() {
    use std::io::{Read, Write};

    let Ok(mode) = std::env::var("WG_KOTLIN_COMMAND_FIXTURE") else { return };
    println!("fixture_pid={}", std::process::id());
    std::io::stdout().flush().unwrap();
    match mode.as_str() {
        "stall" => {
            eprintln!("fixture-stalled");
            std::thread::sleep(Duration::from_secs(60));
        }
        "full-duplex" | "full-duplex-fail" => {
            let output = vec![b'x'; MAX_COMMAND_OUTPUT_BYTES + 65536];
            std::io::stdout().write_all(&output).unwrap();
            std::io::stderr().write_all(&output).unwrap();
            let mut input = Vec::new();
            std::io::stdin().read_to_end(&mut input).unwrap();
            assert!(input.is_empty());
            if mode == "full-duplex-fail" {
                std::process::exit(7);
            }
        }
        "continuous" => loop {
            std::io::stdout().write_all(&[b'x'; 4096]).unwrap();
        },
        #[cfg(unix)]
        "closed-streams" => {
            eprintln!("fixture-closed-streams");
            std::io::stdout().flush().unwrap();
            std::io::stderr().flush().unwrap();
            unsafe {
                libc::close(libc::STDOUT_FILENO);
                libc::close(libc::STDERR_FILENO);
            }
            std::thread::sleep(Duration::from_secs(60));
        }
        "fail" => {
            eprintln!("fixture-failure");
            std::process::exit(7);
        }
        "literal-env" => {
            eprintln!("literal-value={}", serde_json::to_string(&std::env::var("WG_KOTLIN_LITERAL_VALUE").unwrap()).unwrap());
            std::process::exit(7);
        }
        _ => panic!("unknown fixture mode"),
    }
    std::process::exit(0);
}

#[test]
fn a_stalled_process_is_killed_and_reaped_with_captured_diagnostics() {
    let started_at = Instant::now();
    let error = format!("{:#}", command_output(&mut fixture("stall"), Duration::from_millis(500)).unwrap_err());
    assert!(started_at.elapsed() < Duration::from_secs(5));
    assert!(error.contains("timed out after 0.5s"), "{error}");
    assert!(error.contains("fixture-stalled"), "{error}");
    assert_killed_and_reaped(&error);
}

fn assert_killed_and_reaped(detail: &str) {
    let pid: u32 = detail.split_whitespace().find_map(|part| part.strip_prefix("fixture_pid=")).unwrap().parse().unwrap();
    #[cfg(unix)]
    {
        let pid = pid as libc::pid_t;
        let mut status = 0;
        assert_eq!(unsafe { libc::waitpid(pid, &mut status, libc::WNOHANG) }, -1);
        assert_eq!(std::io::Error::last_os_error().raw_os_error(), Some(libc::ECHILD));
        assert_eq!(unsafe { libc::kill(pid, 0) }, -1);
        assert_eq!(std::io::Error::last_os_error().raw_os_error(), Some(libc::ESRCH));
    }
    #[cfg(windows)]
    {
        use windows::Win32::Foundation::{ERROR_INVALID_PARAMETER, WAIT_OBJECT_0};
        use windows::Win32::System::Threading::{OpenProcess, PROCESS_SYNCHRONIZE, WaitForSingleObject};
        match unsafe { OpenProcess(PROCESS_SYNCHRONIZE, false, pid) } {
            Ok(process) => {
                let process = unsafe { windows::core::Owned::new(process) };
                assert_eq!(unsafe { WaitForSingleObject(*process, 0) }, WAIT_OBJECT_0);
            }
            Err(error) => assert_eq!(error.code(), windows::core::HRESULT::from_win32(ERROR_INVALID_PARAMETER.0)),
        }
    }
}

#[test]
fn drains_both_outputs_with_closed_stdin_and_caps_retained_bytes() {
    command_output(&mut fixture("full-duplex"), Duration::from_secs(5)).unwrap();
    let error = command_output(&mut fixture("full-duplex-fail"), Duration::from_secs(5)).unwrap_err();
    let diagnostics = error.to_string();
    let (stdout, stderr) = diagnostics.strip_prefix("stdout: ").unwrap().split_once("\nstderr: ").unwrap();
    // libtest emits a leading newline, removed when diagnostics are trimmed.
    assert_eq!(stdout.len(), MAX_COMMAND_OUTPUT_BYTES - 1);
    assert_eq!(stderr.len(), MAX_COMMAND_OUTPUT_BYTES);
    assert!(format!("{error:#}").contains("code Some(7)"));
}

#[test]
fn continuous_output_cannot_escape_the_total_deadline() {
    let started_at = Instant::now();
    let error = format!("{:#}", command_output(&mut fixture("continuous"), Duration::from_millis(500)).unwrap_err());
    assert!(started_at.elapsed() < Duration::from_secs(5));
    assert!(error.contains("timed out after 0.5s"), "{error}");
    assert_killed_and_reaped(&error);
}

#[test]
#[cfg(unix)]
fn closed_streams_do_not_escape_the_total_deadline() {
    let started_at = Instant::now();
    let error = format!("{:#}", command_output(&mut fixture("closed-streams"), Duration::from_millis(500)).unwrap_err());
    assert!(started_at.elapsed() < Duration::from_secs(5));
    assert!(error.contains("timed out after 0.5s") && error.contains("fixture-closed-streams"), "{error}");
    assert_killed_and_reaped(&error);
}

#[test]
fn preserves_exit_errors_and_captured_stderr() {
    let program = std::env::current_exe().unwrap().to_str().unwrap().to_string();
    let args = ["--exact", "platform::command_tests::command_fixture", "--nocapture"];
    let environment = [("WG_KOTLIN_COMMAND_FIXTURE", "fail")];
    let error = format!("{:#}", run_command("fixture", &program, &args, &environment).unwrap_err());
    assert!(error.contains("code Some(7)"), "{error}");
    assert!(error.contains("fixture-failure"), "{error}");
}

#[test]
fn spawn_errors_keep_the_native_cause_and_command_context() {
    let program = format!("wg-kotlin-missing-command-{}", std::process::id());
    let error = run_command("set-dns", &program, &[], &[]).unwrap_err();
    assert_eq!(error.downcast_ref::<std::io::Error>().unwrap().kind(), std::io::ErrorKind::NotFound);
    let detail = format!("{error:#}");
    assert!(detail.contains("set-dns") && detail.contains(&program) && detail.contains("failed to start"), "{detail}");
}

#[test]
fn required_binaries_stop_at_the_first_missing_program_and_keep_its_cause() {
    let program = std::env::current_exe().unwrap();
    let first = format!("wg-kotlin-missing-first-{}", std::process::id());
    let second = format!("wg-kotlin-missing-second-{}", std::process::id());
    let error = super::ensure_required_binaries(&[program.to_str().unwrap(), &first, &second]).unwrap_err();
    assert!(error.downcast_ref::<which::Error>().is_some(), "{error:#}");
    let detail = format!("{error:#}");
    assert!(detail.contains(&first) && !detail.contains(&second), "{detail}");
    assert!(super::ensure_required_binaries(&[]).is_ok());
}

#[test]
fn borrowed_environment_values_reach_the_child_verbatim() {
    let program = std::env::current_exe().unwrap();
    let value = "linha um\n'quoted' \"texto\" ; $(not-a-command) `literal` \\path ç";
    let args = ["--exact", "platform::command_tests::command_fixture", "--nocapture"];
    let environment = [("WG_KOTLIN_COMMAND_FIXTURE", "literal-env"), ("WG_KOTLIN_LITERAL_VALUE", value)];
    let error = format!("{:#}", run_command("fixture", program.to_str().unwrap(), &args, &environment).unwrap_err());
    assert!(error.contains("code Some(7)"), "{error}");
    assert!(error.contains(&format!("literal-value={}", serde_json::to_string(value).unwrap())), "{error}");
}

#[test]
fn cleanup_attempts_every_step_and_reports_all_contexts_and_native_causes() {
    use anyhow::Context;
    use std::cell::Cell;

    let attempted = Cell::new(0);
    let errors = [("restore-route", libc::EIO), ("clear-dns", libc::EPERM)];
    let steps = errors.iter().map(|&(operation, errno)| {
        attempted.set(attempted.get() + 1);
        Err(std::io::Error::from_raw_os_error(errno)).context(operation)
    });
    let detail = format!("{:#}", super::cleanup_results(steps).unwrap_err());
    assert_eq!(attempted.get(), 2);
    for (operation, errno) in errors {
        assert!(detail.contains(operation), "{detail}");
        assert!(detail.contains(&std::io::Error::from_raw_os_error(errno).to_string()), "{detail}");
    }
    assert!(super::cleanup_results([Err(anyhow::anyhow!(""))]).is_err());
}
