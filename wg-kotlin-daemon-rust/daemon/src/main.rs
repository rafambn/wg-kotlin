use clap::Parser;
use daemon::logging::Logger;
use daemon::{ipc::LocalListener, owner::OwnerProcess, platform, server::DaemonService};
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
    let owner = OwnerProcess::prepare(cli.owner_pid, cli.owner_start_time);
    let Ok(logger) = Logger::new(cli.log_path.as_deref()).inspect_err(|error| {
        if let Ok(logger) = Logger::new(None) {
            logger.log(format_args!("cannot open log output: {error}"));
            logger.flush();
        }
    }) else {
        return std::process::ExitCode::FAILURE;
    };
    let result = owner.and_then(|owner| tokio::runtime::Builder::new_multi_thread().enable_all().build()?.block_on(run(cli, owner, logger.clone())));
    if let Err(error) = &result {
        logger.log(format_args!("daemon failed: {error:#}"));
    }
    logger.flush();
    if result.is_ok() { std::process::ExitCode::SUCCESS } else { std::process::ExitCode::FAILURE }
}

async fn run(cli: Cli, owner: OwnerProcess, logger: Logger) -> anyhow::Result<()> {
    platform::ensure_required_binaries(platform::REQUIRED_BINARIES)?;
    let service = Arc::new(DaemonService::new(logger.clone(), daemon::session::factory()?));
    let mut listener = LocalListener::bind(&cli.endpoint, &owner)?;
    logger.log(format_args!("daemon started endpoint={} owner_pid={}", cli.endpoint, owner.pid));
    let (shutdown_tx, mut shutdown_rx) = watch::channel(false);
    let mut connections = tokio::task::JoinSet::new();
    let mut monitor = tokio::time::interval(Duration::from_millis(100));
    let mut signal = std::pin::pin!(shutdown_signal());
    let result: anyhow::Result<()> = loop {
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
    let drained = tokio::time::timeout(Duration::from_secs(4), async { while connections.join_next().await.is_some() {} }).await.is_ok();
    logger.log(format_args!("daemon stopped sessions_drained={drained}"));
    if !drained {
        logger.flush();
        std::process::exit(1);
    }
    result
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
