use anyhow::bail;
use clap::Parser;
use daemon::logging::{LogComponent, LogLevel, LogOptions};
#[cfg(unix)]
use daemon::owner::allow_owner_signals;
use daemon::{ipc::LocalListener, logging, owner::OwnerProcess, platform, server::DaemonService};
#[cfg(windows)]
use daemon::{session, wintun::WintunDll};
use serde_json::Value;
use std::{sync::Arc, time::Duration};
use tokio::sync::{Mutex, watch};

#[derive(Parser, Debug)]
#[command(name = "wg-kotlin-daemon", version)]
struct Cli {
    #[arg(long)]
    endpoint: String,
    #[arg(long)]
    owner_pid: u32,
    #[arg(long)]
    owner_start_time: u64,
    #[command(flatten)]
    logging: LogOptions,
}

fn main() -> std::process::ExitCode {
    match run_main() {
        Ok(()) => std::process::ExitCode::SUCCESS,
        Err(error) => {
            logging::report_fatal_error(format!("{error:#}"));
            std::process::ExitCode::FAILURE
        }
    }
}

fn run_main() -> anyhow::Result<()> {
    let cli = Cli::parse();
    ensure_root()?;
    let owner = OwnerProcess::open(cli.owner_pid, cli.owner_start_time)?;
    #[cfg(unix)]
    allow_owner_signals(owner.uid)?;
    tokio::runtime::Builder::new_multi_thread().enable_all().build()?.block_on(run(cli, Arc::new(owner)))
}

async fn run(cli: Cli, owner: Arc<OwnerProcess>) -> anyhow::Result<()> {
    platform::ensure_required_binaries(platform::required_binaries()).map_err(anyhow::Error::msg)?;
    #[cfg(windows)]
    let wintun = Arc::new(WintunDll::prepare()?);
    #[cfg(windows)]
    session::set_wintun_dll(&wintun).map_err(anyhow::Error::msg)?;
    let mut scribe = logging::create_daemon_scribe(&cli.logging);
    scribe.hire();
    let service = DaemonService::new(Arc::new(Mutex::new(scribe)));
    let mut listener = match LocalListener::bind(&cli.endpoint, &owner) {
        Ok(listener) => listener,
        Err(error) => {
            service
                .seal_scroll(LogComponent::Daemon, LogLevel::Error, "daemon_error", [("error".into(), Value::String(error.to_string()))], false)
                .await;
            service.retire_logs().await;
            return Err(error.into());
        }
    };
    service
        .seal_scroll(
            LogComponent::Daemon,
            LogLevel::Info,
            "daemon_startup",
            [
                ("endpoint".into(), Value::String(cli.endpoint)),
                ("pid".into(), Value::from(std::process::id())),
                ("owner_pid".into(), Value::from(owner.pid)),
            ],
            true,
        )
        .await;
    let (shutdown_tx, mut shutdown_rx) = watch::channel(false);
    let mut connections = tokio::task::JoinSet::new();
    let mut monitor = tokio::time::interval(Duration::from_millis(100));
    let signal = shutdown_signal();
    tokio::pin!(signal);
    let result: std::io::Result<()> = loop {
        tokio::select! {
            _ = &mut signal => break Ok(()),
            _ = shutdown_rx.changed() => break Ok(()),
            _ = monitor.tick() => { if !owner.is_alive() { break Ok(()); } },
            Some(_) = connections.join_next(), if !connections.is_empty() => {},
            connection = listener.accept() => {
                let (stream, peer_pid) = match connection { Ok(value) => value, Err(error) => break Err(error) };
                if !owner.authorizes(peer_pid) {
                    service.seal_scroll(LogComponent::Ipc, LogLevel::Warn, "daemon_connection_rejected", [("peer_pid".into(), Value::from(peer_pid))], false).await;
                    drop(stream);
                    continue;
                }
                service.seal_scroll(LogComponent::Ipc, LogLevel::Debug, "daemon_connection_accepted", [("peer_pid".into(), Value::from(peer_pid))], true).await;
                let service = service.clone();
                let shutdown = shutdown_tx.clone();
                connections.spawn(async move {
                    if let Err(error) = service.serve_connection(stream, shutdown).await {
                        service.seal_scroll(LogComponent::Ipc, LogLevel::Error, "daemon_connection_error", [("error".into(), Value::String(error.to_string()))], false).await;
                    }
                });
            }
        }
    };
    let _ = shutdown_tx.send(true);
    if let Err(error) = &result {
        service.seal_scroll(LogComponent::Daemon, LogLevel::Error, "daemon_error", [("error".into(), Value::String(error.to_string()))], false).await;
    }
    drop(listener);
    let drained = tokio::time::timeout(Duration::from_secs(4), async {
        while connections.join_next().await.is_some() {}
        service.wait_for_sessions().await;
    })
    .await
    .is_ok();
    let success = result.is_ok() && drained;
    service
        .seal_scroll(
            LogComponent::Daemon,
            if success { LogLevel::Info } else { LogLevel::Error },
            "daemon_shutdown",
            [("sessions_drained".into(), Value::Bool(drained))],
            success,
        )
        .await;
    service.retire_logs().await;
    if !drained {
        std::process::exit(1);
    }
    result?;
    Ok(())
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
    #[cfg(unix)]
    {
        let mut terminate = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()).expect("failed to install SIGTERM handler");
        tokio::select! { _ = tokio::signal::ctrl_c() => {}, _ = terminate.recv() => {} }
    }
    #[cfg(not(unix))]
    let _ = tokio::signal::ctrl_c().await;
}
