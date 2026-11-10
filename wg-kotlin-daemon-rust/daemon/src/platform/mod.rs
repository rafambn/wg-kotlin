use crate::validation::Config;
use anyhow::{Context, Result, anyhow, ensure};
use std::{process::Stdio, time::Duration};
use tokio::{
    io::{AsyncRead, AsyncReadExt},
    process::Command,
};

#[cfg_attr(target_os = "linux", path = "linux.rs")]
#[cfg_attr(target_os = "macos", path = "macos.rs")]
#[cfg_attr(target_os = "windows", path = "windows.rs")]
mod os;
mod routes;
pub use os::REQUIRED_BINARIES;
#[cfg(windows)]
pub(crate) use os::clear_stale_nrpt_rules_once;
pub use routes::{ResolvedEndpoints, resolve_endpoint_routes};

pub type CleanupHook = Box<dyn FnOnce() -> Result<()> + Send + 'static>;

pub fn configure_session(config: &Config, interface: &str, endpoints: ResolvedEndpoints) -> Result<CleanupHook> {
    let ResolvedEndpoints { routes: endpoints, mut registry } = endpoints;
    let mut leases = Vec::new();
    let setup = routes::vpn_routes(config, interface).and_then(|vpn| {
        if !endpoints.is_empty() || !vpn.is_empty() {
            registry.restart()?;
        }
        endpoints.into_iter().chain(vpn).try_for_each(|route| registry.acquire(route, &mut leases))
    });
    if let Err(error) = setup {
        let result = registry.release(leases);
        drop(registry);
        cleanup_results([result, os::clear_dns(interface)]).with_context(|| format!("{error:#}; cleanup failed"))?;
        return Err(error);
    }
    drop(registry);
    let setup = os::clear_dns(interface)
        .and_then(|_| if config.dns_servers.is_empty() { Ok(()) } else { os::set_dns(interface, &config.dns_servers, &config.dns_domains) });
    let interface = interface.to_owned();
    let cleanup = move || {
        let result = routes::registry().and_then(|mut registry| registry.release(leases));
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

const MAX_COMMAND_OUTPUT_BYTES: usize = 1024 * 1024;

#[cfg(test)]
#[path = "../../tests/unit/platform/command_tests.rs"]
mod command_tests;
