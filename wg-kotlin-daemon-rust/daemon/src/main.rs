mod logging;
mod platform;
mod server;
mod session;
mod validation;

use anyhow::{anyhow, bail, Context};
use bytes::Bytes;
use clap::Parser;
use http_body_util::{BodyExt, Full};
use serde_json::Value;
use server::DaemonGrpcService;
use scribe_rs::Scribe;
use std::future::Future;
use std::net::{IpAddr, SocketAddr, ToSocketAddrs};
use std::path::PathBuf;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context as TaskContext, Poll};
use std::time::Instant;
use tokio::sync::Mutex;
use tonic::codegen::{http, Body as HttpBody, Service};
use tonic::body::Body as TonicBody;
use tonic::transport::Server;
use tower_layer::Layer;
use daemon_proto::pb::daemon_server::DaemonServer;

#[derive(Parser, Debug)]
#[command(name = "wg-kotlin-daemon", version)]
struct Cli {
    #[arg(long, default_value = "127.0.0.1")]
    host: String,

    #[arg(long, default_value_t = 8787, value_parser = clap::value_parser!(u16).range(1..=65535))]
    port: u16,

    #[arg(long, default_value = "/var/log/vpn-daemon.jsonl")]
    log_path: PathBuf,

    #[arg(long, hide = true)]
    allow_non_root: bool,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();
    ensure_root_privileges(cli.allow_non_root)?;
    ensure_loopback_host(&cli.host)?;
    platform::ensure_required_binaries(platform::required_binaries())
        .map_err(anyhow::Error::msg)?;

    let scribe = logging::create_daemon_scribe(&cli.log_path)?;
    let scribe = Arc::new(Mutex::new(scribe));

    {
        let mut guard = scribe.lock().await;
        guard.hire();
        logging::log_startup(&guard, &cli.host, cli.port, std::process::id());
    }

    let service = DaemonGrpcService::new(Arc::clone(&scribe));
    let addr = parse_bind_addr(&cli.host, cli.port)?;

    let server_result = Server::builder()
        .layer(VersionEndpointLayer {
            version: env!("CARGO_PKG_VERSION").to_string(),
            scribe: Arc::clone(&scribe),
        })
        .add_service(DaemonServer::new(service))
        .serve_with_shutdown(addr, shutdown_signal())
        .await;

    if let Err(error) = &server_result {
        let guard = scribe.lock().await;
        logging::log_error(&guard, &error.to_string());
    }

    {
        let mut guard = scribe.lock().await;
        logging::log_shutdown(&guard, server_result.is_ok());
        guard.retire().await;
    }

    server_result.context("daemon gRPC server failed")?;
    Ok(())
}

fn parse_bind_addr(host: &str, port: u16) -> anyhow::Result<SocketAddr> {
    let addr = format!("{host}:{port}");
    addr.to_socket_addrs()
        .with_context(|| format!("failed to resolve daemon bind address: {addr}"))?
        .next()
        .ok_or_else(|| anyhow!("no socket address resolved for {addr}"))
}

fn ensure_loopback_host(host: &str) -> anyhow::Result<()> {
    let normalized = host.trim().trim_start_matches('[').trim_end_matches(']');
    let ip = normalized
        .parse::<IpAddr>()
        .with_context(|| format!("daemon host '{host}' is not a valid bind address"))?;

    if !ip.is_loopback() {
        bail!("refusing to bind daemon to non-loopback host '{host}'");
    }

    Ok(())
}

#[cfg(target_family = "unix")]
fn ensure_root_privileges(allow_non_root: bool) -> anyhow::Result<()> {
    if allow_non_root {
        return Ok(());
    }
    let effective_uid = unsafe { libc::geteuid() };
    if effective_uid != 0 {
        bail!(
            "wg-kotlin-daemon requires root privileges on Unix (effective uid = {effective_uid})"
        );
    }
    Ok(())
}

#[cfg(target_os = "windows")]
fn ensure_root_privileges(allow_non_root: bool) -> anyhow::Result<()> {
    if allow_non_root {
        return Ok(());
    }

    let status = std::process::Command::new("powershell.exe")
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            "$i=[Security.Principal.WindowsIdentity]::GetCurrent();$p=[Security.Principal.WindowsPrincipal]::new($i); if($i.IsSystem -or $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){exit 0}else{exit 1}",
        ])
        .status()
        .context("failed to run Windows privilege check")?;

    if status.success() {
        Ok(())
    } else {
        bail!("wg-kotlin-daemon requires Administrator privileges on Windows")
    }
}

#[cfg(all(not(target_family = "unix"), not(target_os = "windows")))]
fn ensure_root_privileges(_allow_non_root: bool) -> anyhow::Result<()> {
    Ok(())
}

async fn shutdown_signal() {
    let _ = tokio::signal::ctrl_c().await;
}

#[derive(Clone)]
struct VersionEndpointLayer {
    version: String,
    scribe: Arc<Mutex<Scribe>>,
}

impl<S> Layer<S> for VersionEndpointLayer {
    type Service = VersionEndpointService<S>;

    fn layer(&self, inner: S) -> Self::Service {
        VersionEndpointService {
            inner,
            version: self.version.clone(),
            scribe: Arc::clone(&self.scribe),
        }
    }
}

#[derive(Clone)]
struct VersionEndpointService<S> {
    inner: S,
    version: String,
    scribe: Arc<Mutex<Scribe>>,
}

impl<S, B> Service<http::Request<B>> for VersionEndpointService<S>
where
    S: Service<http::Request<B>, Response = http::Response<TonicBody>>
        + Clone
        + Send
        + 'static,
    S::Error: Send + 'static,
    S::Future: Send + 'static,
    B: HttpBody<Data = Bytes> + Send + 'static,
    B::Error: Into<tonic::codegen::StdError> + Send + 'static,
{
    type Response = http::Response<TonicBody>;
    type Error = S::Error;
    type Future = Pin<Box<dyn Future<Output = Result<Self::Response, Self::Error>> + Send>>;

    fn poll_ready(&mut self, cx: &mut TaskContext<'_>) -> Poll<Result<(), Self::Error>> {
        self.inner.poll_ready(cx)
    }

    fn call(&mut self, request: http::Request<B>) -> Self::Future {
        let method = request.method().to_string();
        let path = request.uri().path().to_string();
        let started_at = Instant::now();
        let scribe = Arc::clone(&self.scribe);

        if path == "/version" && request.method() == http::Method::GET {
            let version_bytes = Bytes::from(self.version.clone());
            let body = TonicBody::new(
                Full::new(version_bytes).map_err(|never| -> tonic::Status { match never {} }),
            );
            let response = http::Response::builder()
                .status(http::StatusCode::OK)
                .header(http::header::CONTENT_TYPE, "text/plain; charset=utf-8")
                .body(body)
                .expect("version response is valid");
            return Box::pin(async move {
                log_http_request(
                    scribe,
                    method,
                    path,
                    http::StatusCode::OK.as_u16(),
                    started_at.elapsed().as_millis(),
                    None,
                )
                .await;
                Ok(response)
            });
        }

        let future = self.inner.call(request);
        Box::pin(async move {
            let response = future.await;
            let duration_ms = started_at.elapsed().as_millis();
            match &response {
                Ok(http_response) => {
                    log_http_request(
                        scribe,
                        method,
                        path,
                        http_response.status().as_u16(),
                        duration_ms,
                        None,
                    )
                    .await;
                }
                Err(_) => {
                    log_http_request(
                        scribe,
                        method,
                        path,
                        0,
                        duration_ms,
                        Some("service_error".to_string()),
                    )
                    .await;
                }
            }
            response
        })
    }
}

async fn log_http_request(
    scribe: Arc<Mutex<Scribe>>,
    method: String,
    path: String,
    status: u16,
    duration_ms: u128,
    error_type: Option<String>,
) {
    let guard = scribe.lock().await;
    let mut scroll = guard.new_scroll(None);
    scroll.insert(
        "event".to_string(),
        Value::String("daemon_http_request".to_string()),
    );
    scroll.insert("method".to_string(), Value::String(method));
    scroll.insert("path".to_string(), Value::String(path));
    scroll.insert("status".to_string(), Value::Number((status as u64).into()));
    scroll.insert(
        "duration_ms".to_string(),
        Value::Number((duration_ms as u64).into()),
    );
    if let Some(error_type) = error_type {
        scroll.insert("error_type".to_string(), Value::String(error_type));
    }
    let success = status < 500 && scroll.get("error_type").is_none();
    guard.seal(scroll, success);
}
