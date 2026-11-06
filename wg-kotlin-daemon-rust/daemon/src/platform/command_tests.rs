use super::{MAX_COMMAND_OUTPUT_BYTES, command_output, run_command};
use std::time::{Duration, Instant};
use tokio::process::Command;

const INPUT_BYTES: usize = 2 * 1024 * 1024;

fn fixture(mode: &str) -> Command {
    let mut command = Command::new(std::env::current_exe().unwrap());
    command.args(["--exact", "platform::command_tests::command_fixture", "--nocapture"]);
    command.env("WG_KOTLIN_COMMAND_FIXTURE", mode);
    command
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
        "full-duplex" => {
            let output = vec![b'x'; MAX_COMMAND_OUTPUT_BYTES + 65536];
            std::io::stdout().write_all(&output).unwrap();
            std::io::stderr().write_all(&output).unwrap();
            let mut input = Vec::new();
            std::io::stdin().read_to_end(&mut input).unwrap();
            assert_eq!(input.len(), INPUT_BYTES);
        }
        "fail" => {
            eprintln!("fixture-failure");
            std::process::exit(7);
        }
        _ => panic!("unknown fixture mode"),
    }
    std::process::exit(0);
}

#[test]
fn blocked_stdin_and_running_process_share_the_deadline() {
    let input = "x".repeat(INPUT_BYTES);
    for stdin in [None, Some(input.as_str())] {
        let started_at = Instant::now();
        let error = command_output(&mut fixture("stall"), stdin, Duration::from_millis(500)).unwrap_err();
        assert!(started_at.elapsed() < Duration::from_secs(5));
        assert!(error.contains("timed out after 0.5s"), "{error}");
        assert!(error.contains("fixture-stalled"), "{error}");
        #[cfg(unix)]
        {
            let pid: libc::pid_t = error.split_whitespace().find_map(|part| part.strip_prefix("fixture_pid=")).unwrap().parse().unwrap();
            let mut status = 0;
            assert_eq!(unsafe { libc::waitpid(pid, &mut status, libc::WNOHANG) }, -1);
            assert_eq!(std::io::Error::last_os_error().raw_os_error(), Some(libc::ECHILD));
            assert_eq!(unsafe { libc::kill(pid, 0) }, -1);
            assert_eq!(std::io::Error::last_os_error().raw_os_error(), Some(libc::ESRCH));
        }
    }
}

#[test]
fn drains_both_outputs_while_writing_and_caps_retained_bytes() {
    let input = "x".repeat(INPUT_BYTES);
    let output = command_output(&mut fixture("full-duplex"), Some(&input), Duration::from_secs(5)).unwrap();
    assert!(output.status.success());
    assert_eq!(output.stdout.len(), MAX_COMMAND_OUTPUT_BYTES);
    assert_eq!(output.stderr.len(), MAX_COMMAND_OUTPUT_BYTES);
}

#[test]
fn reports_early_stdin_closure() {
    let input = "x".repeat(INPUT_BYTES);
    let error = command_output(&mut fixture("fail"), Some(&input), Duration::from_secs(5)).unwrap_err();
    assert!(error.contains("failed writing stdin"), "{error}");
}

#[test]
fn preserves_exit_errors_and_ignored_failure_patterns() {
    let program = std::env::current_exe().unwrap().to_str().unwrap().to_string();
    let args = ["--exact", "platform::command_tests::command_fixture", "--nocapture"].map(str::to_string);
    let environment = [("WG_KOTLIN_COMMAND_FIXTURE".to_string(), "fail".to_string())];
    let error = run_command("fixture", &program, &args, None, &environment, &[]).unwrap_err();
    assert!(error.contains("code Some(7)"), "{error}");
    assert!(error.contains("fixture-failure"), "{error}");
    assert!(run_command("fixture", &program, &args, None, &environment, &["fixture-failure"]).is_ok());
}
