use crate::ip_util::{parse_proto_cidr, parse_proto_ip, proto_ip_to_string};
use daemon_proto::pb::{Ip, TunSessionConfig};
use route_manager::{Route, RouteManager};
use std::collections::HashSet;
use std::net::IpAddr as StdIpAddr;
use std::process::{Output, Stdio};
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWriteExt};
use tokio::process::Command;

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
#[cfg(target_os = "macos")]
mod dns_macos;

pub type CleanupHook = Box<dyn FnOnce() -> Result<(), String> + Send + 'static>;

pub fn required_binaries() -> &'static [&'static str] {
    #[cfg(target_os = "linux")]
    {
        return &["resolvectl"];
    }

    #[cfg(target_os = "macos")]
    {
        return &[];
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
    let mut seen = HashSet::new();
    for value in values {
        let domain = value.trim().trim_start_matches('.');
        if !domain.is_empty() && seen.insert(domain) {
            normalized.push(domain.to_string());
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
        command.uid(0);
    }

    for (key, value) in environment {
        command.env(key, value);
    }

    let output = command_output(&mut command, stdin, COMMAND_TIMEOUT)
        .map_err(|error| format!("{operation_label}: command '{program}': {error}"))?;
    let stdout = String::from_utf8_lossy(&output.stdout).to_string();
    let stderr = String::from_utf8_lossy(&output.stderr).to_string();

    if output.status.success() {
        return Ok(stdout);
    }

    let output_detail = format!("{}\n{}", stdout.to_lowercase(), stderr.to_lowercase());
    if ignored_failure_patterns.iter().any(|pattern| output_detail.contains(pattern)) {
        return Ok(stdout);
    }

    Err(format!("{operation_label}: command '{program}' failed (code {:?})\nstdout: {}\nstderr: {}", output.status.code(), stdout.trim(), stderr.trim(),))
}

fn command_output(command: &mut Command, stdin: Option<&str>, timeout: Duration) -> Result<Output, String> {
    // run_command is synchronous and runs on blocking workers. A local IO
    // driver supervises every pipe without adding detached reader threads.
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| format!("failed to create command runtime: {error}"))?;
    runtime.block_on(async {
        // Child tools must not inherit the private launcher control pipe.
        let mut child = command
            .stdin(if stdin.is_some() { Stdio::piped() } else { Stdio::null() })
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true)
            .spawn()
            .map_err(|error| format!("failed to start: {error}"))?;
        let stdin_handle = child.stdin.take();
        let stdout_handle = child.stdout.take().expect("stdout is piped");
        let stderr_handle = child.stderr.take().expect("stderr is piped");
        let mut stdout = Vec::new();
        let mut stderr = Vec::new();
        let work = async {
            let (_, _, _, status) = tokio::try_join!(
                async {
                    if let Some(payload) = stdin {
                        let mut handle = stdin_handle.expect("stdin is piped when input is provided");
                        handle.write_all(payload.as_bytes()).await.map_err(|error| format!("failed writing stdin: {error}"))?;
                    }
                    Ok(())
                },
                async {
                    read_capped(stdout_handle, &mut stdout, MAX_COMMAND_OUTPUT_BYTES).await.map_err(|error| format!("failed reading stdout: {error}"))
                },
                async {
                    read_capped(stderr_handle, &mut stderr, MAX_COMMAND_OUTPUT_BYTES).await.map_err(|error| format!("failed reading stderr: {error}"))
                },
                async { child.wait().await.map_err(|error| format!("failed waiting: {error}")) },
            )?;
            Ok(status)
        };
        let result = match tokio::time::timeout(timeout, work).await {
            Ok(result) => result,
            Err(_) => Err(format!("timed out after {}s", timeout.as_secs_f64())),
        };
        match result {
            Ok(status) => Ok(Output { status, stdout, stderr }),
            Err(error) => {
                let kill_result = child.start_kill();
                let wait_result = child.wait().await;
                let mut detail = format!(
                    "{error}\nstdout: {}\nstderr: {}",
                    String::from_utf8_lossy(&stdout).trim(),
                    String::from_utf8_lossy(&stderr).trim()
                );
                if let Err(error) = kill_result {
                    detail.push_str(&format!("\nfailed to terminate: {error}"));
                }
                if let Err(error) = wait_result {
                    detail.push_str(&format!("\nfailed to reap: {error}"));
                }
                Err(detail)
            }
        }
    })
}

async fn read_capped<R: AsyncRead + Unpin>(mut reader: R, output: &mut Vec<u8>, max_bytes: usize) -> std::io::Result<()> {
    let mut chunk = [0_u8; 8192];

    loop {
        let bytes_read = reader.read(&mut chunk).await?;
        if bytes_read == 0 {
            break;
        }

        let remaining = max_bytes.saturating_sub(output.len());
        if remaining > 0 {
            let bytes_to_copy = remaining.min(bytes_read);
            output.extend_from_slice(&chunk[..bytes_to_copy]);
        }
    }

    Ok(())
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
    let filtered_routes: Vec<Route> = routes
        .into_iter()
        .filter(|route| {
            let host_prefix = if route.destination().is_ipv4() { 32 } else { 128 };
            route.prefix() != host_prefix || !endpoint_ips.contains(&route.destination())
        })
        .collect();

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
const MAX_COMMAND_OUTPUT_BYTES: usize = 1024 * 1024;

#[cfg(test)]
mod route_tests;

#[cfg(test)]
mod command_tests;
