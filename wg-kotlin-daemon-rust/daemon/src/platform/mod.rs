use crate::ip_util::{parse_proto_cidr, parse_proto_ip, proto_ip_to_string};
use daemon_proto::pb::{Ip, TunSessionConfig};
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

#[cfg(target_os = "linux")]
#[path = "endpoint_linux.rs"]
mod endpoint;
#[cfg(target_os = "macos")]
#[path = "endpoint_macos.rs"]
mod endpoint;
#[cfg(target_os = "windows")]
#[path = "endpoint_windows.rs"]
mod endpoint;
pub use endpoint::EndpointRoute;

#[cfg(target_os = "macos")]
mod routes_macos;

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

pub fn configure_session(config: &TunSessionConfig, interface_name: &str, endpoint_routes: Vec<EndpointRoute>) -> Result<CleanupHook, String> {
    #[cfg(target_os = "linux")]
    {
        return linux::configure_session(config, interface_name, endpoint_routes);
    }

    #[cfg(target_os = "macos")]
    {
        return macos::configure_session(config, interface_name, endpoint_routes);
    }

    #[cfg(target_os = "windows")]
    {
        return windows::configure_session(config, interface_name, endpoint_routes);
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

pub fn ips_to_args(values: &[Ip]) -> Vec<String> {
    let mut seen = Vec::new();
    for addr in values {
        let ip = proto_ip_to_string(addr);
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

    // The daemon uses the app's real UID and root's effective UID. Give child
    // tools matching root UIDs so shells and network tools retain privileges.
    #[cfg(unix)]
    if unsafe { libc::geteuid() } == 0 {
        use std::os::unix::process::CommandExt;
        command.uid(0);
    }

    for (key, value) in environment {
        command.env(key, value);
    }

    // Child tools must not inherit the private launcher control pipe.
    command.stdin(if stdin.is_some() { Stdio::piped() } else { Stdio::null() });

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

/// Resolve before creating the TUN: assigning its addresses can install
/// connected routes that would hide the endpoint's previous egress route.
pub fn resolve_endpoint_routes(config: &TunSessionConfig) -> Result<Vec<EndpointRoute>, String> {
    config.peer_endpoints
        .iter()
        .map(|addr| {
            let ip = parse_proto_ip(addr).ok_or("invalid peer endpoint address")?;
            EndpointRoute::lookup(ip).map_err(|error| format!("failed to resolve route for endpoint {ip}: {error}"))
        })
        .collect()
}

pub fn build_and_filter_routes(
    config: &TunSessionConfig,
    interface_name: &str,
    endpoint_routes: Vec<EndpointRoute>,
) -> Result<(RouteManager, Vec<Route>, Vec<EndpointRoute>), String> {
    let mgr = RouteManager::new().map_err(|e| format!("route manager: {e}"))?;
    let routes: Vec<Route> = config
        .peer_allowed_ips
        .iter()
        .filter_map(|addr| {
            let (ip, prefix) = parse_proto_cidr(addr)?;
            Some(Route::new(ip, u8::try_from(prefix).ok()?).with_if_name(interface_name.to_string()))
        })
        .collect();

    let endpoint_ips: Vec<StdIpAddr> = config.peer_endpoints.iter().filter_map(parse_proto_ip).collect();
    let filtered_routes: Vec<Route> = routes.iter().filter(|route| !endpoint_ips.iter().any(|ep| *ep == route.destination())).cloned().collect();

    Ok((mgr, filtered_routes, endpoint_routes))
}

#[cfg(not(target_os = "macos"))]
pub fn add_routes_with_predelete(mgr: &mut RouteManager, filtered_routes: &[Route], endpoint_routes: &[EndpointRoute]) -> Result<(), String> {
    for route in endpoint_routes {
        route.replace().map_err(|error| format!("failed to add endpoint route: {error}"))?;
    }
    for route in filtered_routes {
        let _ = mgr.delete(route);
        mgr.add(route).map_err(|e| format!("failed to add route: {e}"))?;
    }
    Ok(())
}

#[cfg(not(target_os = "macos"))]
fn cleanup_routes<F>(filtered_routes: &[Route], endpoint_routes: &[EndpointRoute], dns_teardown: F) -> Result<(), String>
where
    F: FnOnce() -> Result<(), String>,
{
    let mut cleanup_error: Option<String> = None;
    let mut capture_error = |result: Result<(), String>| {
        if let Err(error) = result {
            if let Some(existing) = &mut cleanup_error {
                existing.push_str("; ");
                existing.push_str(&error);
            } else {
                cleanup_error = Some(error);
            }
        }
    };

    match RouteManager::new() {
        Ok(mut mgr) => {
            for route in filtered_routes.iter().rev() {
                capture_error(mgr.delete(route).map_err(|e| e.to_string()));
            }
        }
        Err(e) => capture_error(Err(e.to_string())),
    }
    for route in endpoint_routes.iter().rev() {
        capture_error(route.delete().map_err(|error| error.to_string()));
    }
    capture_error(dns_teardown());

    match cleanup_error {
        Some(error) => Err(error),
        None => Ok(()),
    }
}

#[cfg(not(target_os = "macos"))]
pub fn into_cleanup_hook(
    setup_result: Result<(), String>,
    filtered_routes: Vec<Route>,
    endpoint_routes: Vec<EndpointRoute>,
    interface_name: String,
    dns_teardown: fn(&str) -> Result<(), String>,
) -> Result<CleanupHook, String> {
    let cleanup_interface = interface_name.clone();
    match setup_result {
        Ok(()) => Ok(Box::new(move || cleanup_routes(&filtered_routes, &endpoint_routes, || dns_teardown(&cleanup_interface)))),
        Err(setup_error) => {
            match cleanup_routes(&filtered_routes, &endpoint_routes, || dns_teardown(&interface_name)) {
                Ok(()) => Err(setup_error),
                Err(cleanup_error) => Err(format!("{setup_error}; cleanup failed: {cleanup_error}")),
            }
        }
    }
}

const COMMAND_TIMEOUT: Duration = Duration::from_secs(20);
const COMMAND_POLL_INTERVAL: Duration = Duration::from_millis(25);
const MAX_COMMAND_OUTPUT_BYTES: usize = 1024 * 1024;

#[cfg(test)]
mod route_tests;
