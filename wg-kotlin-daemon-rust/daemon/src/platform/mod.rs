use crate::validation::Config;
use anyhow::{Context, Result, anyhow, ensure};
use route_manager::Route;
#[cfg(not(target_os = "macos"))]
use route_manager::RouteManager;
use std::process::Stdio;
#[cfg(not(target_os = "macos"))]
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncReadExt};
use tokio::process::Command;

#[cfg_attr(target_os = "linux", path = "linux.rs")]
#[cfg_attr(target_os = "macos", path = "macos.rs")]
#[cfg_attr(target_os = "windows", path = "windows.rs")]
mod os;
#[cfg(windows)]
pub(crate) use os::clear_stale_nrpt_rules_once;
pub use os::{EndpointRoute, REQUIRED_BINARIES};

pub type CleanupHook = Box<dyn FnOnce() -> Result<()> + Send + 'static>;

#[derive(Debug)]
pub struct ResolvedEndpoints {
    routes: Vec<EndpointRoute>,
    #[cfg(not(target_os = "macos"))]
    pins: MutexGuard<'static, EndpointPins>,
}

#[cfg(not(target_os = "macos"))]
#[derive(Debug, Default)]
struct EndpointPins(Vec<(Arc<EndpointRoute>, usize)>);

#[cfg(not(target_os = "macos"))]
impl EndpointPins {
    fn acquire(&mut self, route: EndpointRoute, leases: &mut Vec<Arc<EndpointRoute>>) -> Result<()> {
        if let Some((saved, users)) = self.0.iter_mut().find(|(saved, _)| saved.same_slot(&route)) {
            *users += 1;
            leases.push(saved.clone());
            return Ok(());
        }
        let route = Arc::new(route);
        self.0.push((route.clone(), 1));
        leases.push(route.clone()); // Register before a request that can fail after applying.
        route.replace().context("failed to add endpoint route")
    }

    fn release(&mut self, leases: Vec<Arc<EndpointRoute>>) -> Result<()> {
        cleanup_results(leases.into_iter().rev().map(|route| {
            let index = self.0.iter().position(|(saved, _)| Arc::ptr_eq(saved, &route)).context("endpoint lease is not registered")?;
            self.0[index].1 -= 1;
            if self.0[index].1 == 0 { self.0.remove(index).0.delete() } else { Ok(()) }
        }))
    }
}

#[cfg(not(target_os = "macos"))]
fn endpoint_pins() -> Result<MutexGuard<'static, EndpointPins>> {
    static PINS: Mutex<EndpointPins> = Mutex::new(EndpointPins(Vec::new()));
    PINS.lock().map_err(|_| anyhow!("endpoint registry poisoned"))
}

pub fn configure_session(config: &Config, interface: &str, endpoints: ResolvedEndpoints) -> Result<CleanupHook> {
    let ResolvedEndpoints {
        routes: endpoints,
        #[cfg(not(target_os = "macos"))]
        mut pins,
    } = endpoints;
    let routes = vpn_routes(config, interface);
    #[cfg(not(target_os = "macos"))]
    let mut manager = RouteManager::new().context("route manager")?;
    #[cfg(not(target_os = "macos"))]
    let mut leases = Vec::new();
    #[cfg(target_os = "macos")]
    let mut saved = os::SessionRoutes::default();
    let setup = (|| {
        #[cfg(target_os = "macos")]
        saved.install(&routes, &endpoints).context("failed to configure routes")?;
        #[cfg(not(target_os = "macos"))]
        {
            endpoints.into_iter().try_for_each(|route| pins.acquire(route, &mut leases))?;
            add_routes_with_predelete(&mut manager, &routes)?;
        }
        Ok(())
    })();
    let cleanup_routes = move |#[cfg(not(target_os = "macos"))] pins: &mut EndpointPins| {
        #[cfg(target_os = "macos")]
        {
            saved.clear()
        }
        #[cfg(not(target_os = "macos"))]
        {
            let mut manager = manager;
            let vpn_results = routes.iter().rev().map(|route| manager.delete(route).map_err(Into::into));
            cleanup_results([cleanup_results(vpn_results), pins.release(leases)])
        }
    };
    if let Err(error) = setup {
        let result = cleanup_routes(
            #[cfg(not(target_os = "macos"))]
            &mut pins,
        );
        #[cfg(not(target_os = "macos"))]
        drop(pins);
        cleanup_results([result, os::clear_dns(interface)]).with_context(|| format!("{error:#}; cleanup failed"))?;
        return Err(error);
    }
    #[cfg(not(target_os = "macos"))]
    drop(pins);
    let setup = os::clear_dns(interface).and_then(|_| {
        // Validation keeps servers and domains either both empty or both populated.
        if config.dns_servers.is_empty() { Ok(()) } else { os::set_dns(interface, &config.dns_servers, &config.dns_domains) }
    });
    let interface = interface.to_string();
    let cleanup = move || {
        #[cfg(target_os = "macos")]
        let result = cleanup_routes();
        #[cfg(not(target_os = "macos"))]
        let result = endpoint_pins().and_then(|mut pins| cleanup_routes(&mut pins));
        cleanup_results([result, os::clear_dns(&interface)])
    };
    if let Err(error) = setup {
        cleanup().with_context(|| format!("{error:#}; cleanup failed"))?;
        return Err(error);
    }
    Ok(Box::new(cleanup))
}

pub(super) fn cleanup_results(results: impl IntoIterator<Item = Result<()>>) -> Result<()> {
    let errors: Vec<_> = results.into_iter().filter_map(Result::err).map(|error| format!("{error:#}")).collect();
    ensure!(errors.is_empty(), errors.join("; "));
    Ok(())
}

pub fn ensure_required_binaries(binaries: &[&str]) -> Result<()> {
    binaries.iter().try_for_each(|binary| which::which(binary).map(drop).with_context(|| format!("required binary '{binary}' not found in PATH")))
}

pub fn run_command(operation: &str, program: &str, arguments: &[&str], environment: &[(&str, &str)]) -> Result<()> {
    let mut command = Command::new(program);
    command.args(arguments).envs(environment.iter().copied());
    // Match root UIDs so child shells and network tools retain privileges.
    #[cfg(unix)]
    if unsafe { libc::geteuid() } == 0 {
        command.uid(0);
    }
    command_output(&mut command, Duration::from_secs(20)).with_context(|| format!("{operation}: command '{program}'"))
}

fn command_output(command: &mut Command, timeout: Duration) -> Result<()> {
    // Blocking setup/cleanup workers use a local driver to drain both pipes.
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build()?;
    let mut stdout = Vec::new();
    let mut stderr = Vec::new();
    let result = runtime.block_on(async {
        let mut child =
            command.stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::piped()).kill_on_drop(true).spawn().context("failed to start")?;
        let work = async {
            tokio::try_join!(
                read_capped(child.stdout.take().unwrap(), &mut stdout, "failed reading stdout"),
                read_capped(child.stderr.take().unwrap(), &mut stderr, "failed reading stderr"),
                async { child.wait().await.context("failed waiting") },
            )
        };
        let mut result = tokio::time::timeout(timeout, work).await.unwrap_or_else(|_| Err(anyhow!("timed out after {}s", timeout.as_secs_f64())));
        if result.is_err()
            && let Err(cleanup) =
                cleanup_results([child.start_kill().context("failed to terminate"), child.wait().await.map(|_| ()).context("failed to reap")])
        {
            result = result.context(format!("cleanup failed: {cleanup:#}"));
        }
        let (_, _, status) = result?;
        ensure!(status.success(), "failed (code {:?})", status.code());
        Ok(())
    });
    result.with_context(|| format!("stdout: {}\nstderr: {}", String::from_utf8_lossy(&stdout).trim(), String::from_utf8_lossy(&stderr).trim()))
}

async fn read_capped<R: AsyncRead + Unpin>(reader: R, output: &mut Vec<u8>, context: &'static str) -> Result<()> {
    let mut limited = reader.take(MAX_COMMAND_OUTPUT_BYTES.saturating_sub(output.len()) as u64);
    limited.read_to_end(output).await.context(context)?;
    tokio::io::copy(&mut limited.into_inner(), &mut tokio::io::sink()).await.map(|_| ()).context(context)
}

/// Resolve before creating the TUN: assigning its addresses can install
/// connected routes that would hide the endpoint's previous egress route.
pub fn resolve_endpoint_routes(config: &Config) -> Result<ResolvedEndpoints> {
    // Keep lookup, TUN creation and setup in one transaction with other sessions.
    #[cfg(not(target_os = "macos"))]
    let pins = endpoint_pins()?;
    #[cfg(target_os = "macos")]
    os::recover_routes()?;
    let mut routes = Vec::new();
    for &ip in &config.peer_endpoints {
        let ip = ip.context("invalid peer endpoint address")?;
        routes.push(EndpointRoute::lookup(ip).with_context(|| format!("failed to resolve route for endpoint {ip}"))?);
    }
    Ok(ResolvedEndpoints {
        routes,
        #[cfg(not(target_os = "macos"))]
        pins,
    })
}

pub(crate) fn vpn_routes(config: &Config, interface: &str) -> Vec<Route> {
    config
        .peer_allowed_ips
        .iter()
        .filter(|(ip, prefix)| *prefix != if ip.is_ipv4() { 32 } else { 128 } || !config.peer_endpoints.contains(&Some(*ip)))
        .map(|&(ip, prefix)| Route::new(ip, prefix).with_if_name(interface.to_string()))
        .collect()
}

#[cfg(not(target_os = "macos"))]
fn add_routes_with_predelete(mgr: &mut RouteManager, filtered_routes: &[Route]) -> Result<()> {
    filtered_routes.iter().try_for_each(|route| {
        let _ = mgr.delete(route);
        mgr.add(route).context("failed to add route")
    })
}

const MAX_COMMAND_OUTPUT_BYTES: usize = 1024 * 1024;

#[cfg(test)]
#[path = "../../tests/unit/platform/route_tests.rs"]
mod route_tests;

#[cfg(test)]
#[path = "../../tests/unit/platform/command_tests.rs"]
mod command_tests;
