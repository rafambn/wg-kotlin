use anyhow::bail;
use clap::Parser;
use daemon::logging::Logger;
#[cfg(unix)]
use daemon::owner::allow_owner_signals;
use daemon::{ipc::LocalListener, owner::OwnerProcess, platform, server::DaemonService};
#[cfg(windows)]
use daemon::{session, wintun::WintunDll};
use std::{path::PathBuf, sync::Arc, time::Duration};
use tokio::sync::watch;

#[derive(Parser, Debug)]
#[command(name = "wg-kotlin-daemon", version)]
struct Cli {
    #[arg(long)]
    endpoint: String,
    #[arg(long)]
    owner_pid: u32,
    #[arg(long)]
    owner_start_time: u64,
    #[arg(long)]
    log_path: Option<PathBuf>,
}

fn main() -> std::process::ExitCode {
    let cli = Cli::parse();
    // Configure process credentials before starting the log worker or Tokio.
    let owner = prepare_owner(&cli);
    let logger = match Logger::new(cli.log_path.as_deref()) {
        Ok(logger) => logger,
        Err(error) => {
            if let Ok(logger) = Logger::new(None) {
                logger.log(format_args!("cannot open log output: {error}"));
                logger.flush();
            }
            return std::process::ExitCode::FAILURE;
        }
    };
    let result =
        owner.and_then(|owner| tokio::runtime::Builder::new_multi_thread().enable_all().build()?.block_on(run(cli, Arc::new(owner), logger.clone())));
    if let Err(error) = &result {
        logger.log(format_args!("daemon failed: {error:#}"));
    }
    logger.flush();
    if result.is_ok() { std::process::ExitCode::SUCCESS } else { std::process::ExitCode::FAILURE }
}

fn prepare_owner(cli: &Cli) -> anyhow::Result<OwnerProcess> {
    ensure_root()?;
    let owner = OwnerProcess::open(cli.owner_pid, cli.owner_start_time)?;
    #[cfg(unix)]
    allow_owner_signals(owner.uid)?;
    Ok(owner)
}

async fn run(cli: Cli, owner: Arc<OwnerProcess>, logger: Logger) -> anyhow::Result<()> {
    platform::ensure_required_binaries(platform::required_binaries()).map_err(anyhow::Error::msg)?;
    #[cfg(windows)]
    let wintun = Arc::new(WintunDll::prepare()?);
    #[cfg(windows)]
    session::set_wintun_dll(&wintun).map_err(anyhow::Error::msg)?;
    let service = DaemonService::new(logger.clone());
    let mut listener = LocalListener::bind(&cli.endpoint, &owner)?;
    logger.log(format_args!("daemon started endpoint={} owner_pid={}", cli.endpoint, owner.pid));
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
                    logger.log(format_args!("connection rejected peer_pid={peer_pid}"));
                    drop(stream);
                    continue;
                }
                logger.log(format_args!("connection accepted peer_pid={peer_pid}"));
                let service = service.clone();
                let shutdown = shutdown_tx.clone();
                let logger = logger.clone();
                connections.spawn(async move {
                    if let Err(error) = service.serve_connection(stream, shutdown).await {
                        logger.log(format_args!("connection failed peer_pid={peer_pid}: {error}"));
                    }
                });
            }
        }
    };
    let _ = shutdown_tx.send(true);
    if let Err(error) = &result {
        logger.log(format_args!("listener failed: {error}"));
    }
    drop(listener);
    let drained = tokio::time::timeout(Duration::from_secs(4), async {
        while connections.join_next().await.is_some() {}
        service.wait_for_sessions().await;
    })
    .await
    .is_ok();
    logger.log(format_args!("daemon stopped sessions_drained={drained}"));
    if !drained {
        logger.flush();
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
