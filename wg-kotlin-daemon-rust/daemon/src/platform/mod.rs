use crate::ip_util::{parse_proto_ip, proto_ip_to_cidr};
use daemon_proto::pb::IpAddr;
use route_manager::{Route, RouteManager};
use std::io::{Read, Write};
use std::net::IpAddr as StdIpAddr;
use std::process::{Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

#[cfg(target_os = "linux")]
pub mod linux;
#[cfg(target_os = "macos")]
pub mod macos;
#[cfg(target_os = "windows")]
pub mod windows;

pub type CleanupHook = Box<dyn FnOnce() -> Result<(), String> + Send + 'static>;

pub fn required_binaries() -> &'static [&'static str] {
    #[cfg(target_os = "linux")]
    {
        return &["resolvectl"];
    }

    #[cfg(target_os = "macos")]
    {
        return &["scutil"];
    }

    #[cfg(target_os = "windows")]
    {
        return &["powershell"];
    }

    #[allow(unreachable_code)]
    &[]
}

pub fn configure_session(config: &daemon_proto::pb::TunSessionConfig, interface_name: &str) -> Result<CleanupHook, String> {
    #[cfg(target_os = "linux")]
    {
        return linux::configure_session(config, interface_name);
    }

    #[cfg(target_os = "macos")]
    {
        return macos::configure_session(config, interface_name);
    }

    #[cfg(target_os = "windows")]
    {
        return windows::configure_session(config, interface_name);
    }

    #[allow(unreachable_code)]
    Err("unsupported platform for daemon runtime".to_string())
}

pub fn prepare_session_start() -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        return windows::clear_stale_nrpt_rules_once();
    }

    #[allow(unreachable_code)]
    Ok(())
}

pub fn ips_to_args(values: &[IpAddr]) -> Vec<String> {
    let mut seen = Vec::new();
    for addr in values {
        let ip = proto_ip_to_cidr(addr);
        if !seen.contains(&ip) {
            seen.push(ip);
        }
    }
    seen
}

pub fn normalize_domains(values: &[String]) -> Vec<String> {
    let mut normalized = Vec::<String>::new();

    for value in values {
        let trimmed = value.trim();
        if trimmed.is_empty() {
            continue;
        }

        let domain = trimmed.trim_start_matches('.').to_string();
        if domain.is_empty() {
            continue;
        }

        if !normalized.iter().any(|existing| existing == &domain) {
            normalized.push(domain);
        }
    }

    normalized
}

pub fn ensure_required_binaries(binaries: &[&str]) -> Result<(), String> {
    for binary in binaries {
        which::which(binary).map_err(|_| format!("required binary '{binary}' not found in PATH"))?;
    }

    Ok(())
}

pub fn run_command(
    operation_label: &str,
    program: &str,
    arguments: &[String],
    stdin: Option<&str>,
    environment: &[(String, String)],
    ignored_failure_patterns: &[&str],
) -> Result<String, String> {
    let mut command = Command::new(program);
    command.args(arguments);

    for (key, value) in environment {
        command.env(key, value);
    }

    if stdin.is_some() {
        command.stdin(Stdio::piped());
    }

    let mut child = command
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|error| format!("{operation_label}: failed to start command '{program}': {error}"))?;

    if let Some(stdin_payload) = stdin {
        let mut stdin_handle = child.stdin.take().ok_or_else(|| format!("{operation_label}: command '{program}' did not expose stdin pipe"))?;

        stdin_handle
            .write_all(stdin_payload.as_bytes())
            .map_err(|error| format!("{operation_label}: failed writing stdin to '{program}': {error}"))?;
    }

    let stdout_handle = child.stdout.take().ok_or_else(|| format!("{operation_label}: command '{program}' did not expose stdout pipe"))?;
    let stderr_handle = child.stderr.take().ok_or_else(|| format!("{operation_label}: command '{program}' did not expose stderr pipe"))?;

    let stdout_reader = thread::spawn(move || read_capped(stdout_handle, MAX_COMMAND_OUTPUT_BYTES));
    let stderr_reader = thread::spawn(move || read_capped(stderr_handle, MAX_COMMAND_OUTPUT_BYTES));

    let started_at = Instant::now();
    let mut timed_out = false;
    let status = loop {
        let status = child.try_wait().map_err(|error| format!("{operation_label}: failed waiting for command '{program}': {error}"))?;
        if let Some(status) = status {
            break status;
        }

        if started_at.elapsed() >= COMMAND_TIMEOUT {
            timed_out = true;
            break child
                .kill()
                .and_then(|_| child.wait())
                .map_err(|error| format!("{operation_label}: failed to terminate timed out command '{program}': {error}"))?;
        }

        thread::sleep(COMMAND_POLL_INTERVAL);
    };

    let stdout = stdout_reader
        .join()
        .map_err(|_| format!("{operation_label}: failed joining stdout reader for command '{program}'"))?
        .map_err(|error| format!("{operation_label}: failed reading stdout for command '{program}': {error}"))
        .map(|bytes| String::from_utf8_lossy(&bytes).to_string())?;
    let stderr = stderr_reader
        .join()
        .map_err(|_| format!("{operation_label}: failed joining stderr reader for command '{program}'"))?
        .map_err(|error| format!("{operation_label}: failed reading stderr for command '{program}': {error}"))
        .map(|bytes| String::from_utf8_lossy(&bytes).to_string())?;

    if timed_out {
        return Err(format!(
            "{operation_label}: command '{program}' timed out after {}s\nstdout: {}\nstderr: {}",
            COMMAND_TIMEOUT.as_secs(),
            stdout.trim(),
            stderr.trim(),
        ));
    }

    if status.success() {
        return Ok(stdout);
    }

    let output_detail = format!("{}\n{}", stdout.to_lowercase(), stderr.to_lowercase());
    if ignored_failure_patterns.iter().any(|pattern| output_detail.contains(pattern)) {
        return Ok(stdout);
    }

    Err(format!("{operation_label}: command '{program}' failed (code {:?})\nstdout: {}\nstderr: {}", status.code(), stdout.trim(), stderr.trim(),))
}

fn read_capped<R: Read>(mut reader: R, max_bytes: usize) -> std::io::Result<Vec<u8>> {
    let mut output = Vec::<u8>::with_capacity(max_bytes.min(8192));
    let mut chunk = [0_u8; 8192];

    loop {
        let bytes_read = reader.read(&mut chunk)?;
        if bytes_read == 0 {
            break;
        }

        let remaining = max_bytes.saturating_sub(output.len());
        if remaining > 0 {
            let bytes_to_copy = remaining.min(bytes_read);
            output.extend_from_slice(&chunk[..bytes_to_copy]);
        }
    }

    Ok(output)
}

pub fn build_routes(routes: &[IpAddr], interface_name: &str) -> Vec<Route> {
    routes
        .iter()
        .filter_map(|addr| {
            let (ip, _) = parse_proto_ip(addr)?;
            Some(Route::new(ip, u8::try_from(addr.prefix?).ok()?).with_if_name(interface_name.to_string()))
        })
        .collect()
}

pub fn build_endpoint_routes(mgr: &mut RouteManager, endpoints: &[IpAddr], interface_name: &str) -> Vec<Route> {
    endpoints
        .iter()
        .filter_map(|addr| {
            let (ip, _) = parse_proto_ip(addr)?;
            let found = mgr.find_route(&ip).ok()??;
            Some(found.with_if_name(interface_name.to_string()))
        })
        .collect()
}

pub fn filter_routes_for_endpoints(routes: &[Route], endpoint_ips: &[StdIpAddr]) -> Vec<Route> {
    routes
        .iter()
        .filter(|route| !endpoint_ips.iter().any(|ep| *ep == route.destination()))
        .cloned()
        .collect()
}

const COMMAND_TIMEOUT: Duration = Duration::from_secs(20);
const COMMAND_POLL_INTERVAL: Duration = Duration::from_millis(25);
const MAX_COMMAND_OUTPUT_BYTES: usize = 1 * 1024 * 1024;
