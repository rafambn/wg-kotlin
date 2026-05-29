use anyhow::{Context, bail};
use axum::{Router, response::IntoResponse, routing::get};
use clap::Parser;
use daemon::{logging, platform, server};
use daemon_proto::pb::daemon_server::DaemonServer;
use serde_json::Value;
use server::DaemonGrpcService;
use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::Mutex;
use tower::ServiceExt;

#[derive(Parser, Debug)]
#[command(name = "wg-kotlin-daemon", version)]
struct Cli {
    #[arg(long, default_value = "127.0.0.1")]
    host: String,

    #[arg(long, default_value_t = 8787, value_parser = clap::value_parser!(u16).range(1..=65535))]
    port: u16,

    #[arg(long, help = "Path to the JSONL log file. Defaults to <exe_dir>/vpn-daemon.jsonl")]
    log_path: Option<PathBuf>,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();
    let bind_ip = resolve_host(&cli.host)?;
    ensure_root()?;
    platform::ensure_required_binaries(platform::required_binaries()).map_err(anyhow::Error::msg)?;

    let log_path = cli.log_path.unwrap_or_else(|| {
        let mut dir =
            std::env::current_exe().expect("failed to get executable path").parent().expect("executable has no parent directory").to_path_buf();
        dir.push("vpn-daemon.jsonl");
        dir
    });

    let scribe = logging::create_daemon_scribe(&log_path)?;
    let scribe = Arc::new(Mutex::new(scribe));

    {
        let mut guard = scribe.lock().await;
        guard.hire();
        let mut scroll = guard.new_scroll(None);
        scroll.insert("event".to_string(), Value::String("daemon_startup".to_string()));
        scroll.insert("host".to_string(), Value::String(bind_ip.to_string()));
        scroll.insert("port".to_string(), Value::Number((cli.port as u64).into()));
        scroll.insert("pid".to_string(), Value::Number((std::process::id() as u64).into()));
        guard.seal(scroll, true);
    }

    let grpc_service = DaemonGrpcService::new(Arc::clone(&scribe));
    let addr = SocketAddr::new(bind_ip, cli.port);

    let grpc_svc = DaemonServer::new(grpc_service);
    let fallback = grpc_svc.map_request(|req: axum::extract::Request| req.map(tonic::body::Body::new));

    let app = Router::new().route("/version", get(version_handler)).fallback_service(fallback);

    let listener = tokio::net::TcpListener::bind(addr).await?;
    let server_result = axum::serve(listener, app.into_make_service()).with_graceful_shutdown(shutdown_signal()).await;

    if let Err(error) = &server_result {
        let guard = scribe.lock().await;
        let mut scroll = guard.new_scroll(None);
        scroll.insert("event".to_string(), Value::String("daemon_error".to_string()));
        scroll.insert("error".to_string(), Value::String(error.to_string()));
        guard.seal(scroll, false);
    }

    {
        let mut guard = scribe.lock().await;
        let mut scroll = guard.new_scroll(None);
        scroll.insert("event".to_string(), Value::String("daemon_shutdown".to_string()));
        guard.seal(scroll, server_result.is_ok());
        guard.retire().await;
    }

    server_result.context("daemon gRPC server failed")?;
    Ok(())
}

fn resolve_host(host: &str) -> anyhow::Result<IpAddr> {
    let normalized = host.trim().trim_start_matches('[').trim_end_matches(']');
    let ip = normalized.parse::<IpAddr>().with_context(|| format!("'{host}' is not a valid IP address"))?;

    if !ip.is_loopback() {
        bail!("daemon refuses to bind to non-loopback host '{host}'");
    }

    Ok(ip)
}

async fn version_handler() -> impl IntoResponse {
    env!("CARGO_PKG_VERSION")
}

#[cfg(target_family = "unix")]
fn ensure_root() -> anyhow::Result<()> {
    let effective_uid = unsafe { libc::geteuid() };
    if effective_uid != 0 {
        bail!("wg-kotlin-daemon requires root privileges on Unix (effective uid = {effective_uid})");
    }
    Ok(())
}

#[cfg(target_os = "windows")]
fn ensure_root() -> anyhow::Result<()> {
    use windows::Win32::Foundation::{CloseHandle, HANDLE};
    use windows::Win32::Security::{GetTokenInformation, TOKEN_ELEVATION, TOKEN_QUERY};
    use windows::Win32::System::Threading::{GetCurrentProcess, OpenProcessToken};

    unsafe {
        let mut token = HANDLE::default();
        OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token)?;

        let mut elevation = TOKEN_ELEVATION::default();
        let mut return_length = 0u32;

        GetTokenInformation(
            token,
            windows::Win32::Security::TokenElevation,
            Some(&mut elevation as *mut _ as *mut _),
            std::mem::size_of::<TOKEN_ELEVATION>() as u32,
            &mut return_length,
        )?;

        CloseHandle(token)?;

        if elevation.TokenIsElevated != 0 { Ok(()) } else { bail!("wg-kotlin-daemon requires Administrator privileges on Windows") }
    }
}

#[cfg(all(not(target_family = "unix"), not(target_os = "windows")))]
fn ensure_root() -> anyhow::Result<()> {
    bail!("wg-kotlin-daemon does not support this operating system")
}

async fn shutdown_signal() {
    let _ = tokio::signal::ctrl_c().await;
}
