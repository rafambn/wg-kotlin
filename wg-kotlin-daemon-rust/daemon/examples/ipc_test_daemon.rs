//! Unprivileged transport fixture. Never creates TUN devices, routes or DNS.
use clap::Parser;
use daemon::{
    ipc::{LocalListener, PROTOCOL_VERSION, read_frame, write_frame},
    owner::OwnerProcess,
    server::DaemonService,
};
use daemon_proto::pb::{ClientMessage, DaemonReady, Packet, ServerMessage, SessionStarted, client_message, server_message};
use scribe_rs::Scribe;
use std::sync::Arc;
use tokio::sync::{Mutex, watch};

#[derive(Parser)]
struct Args {
    #[arg(long)]
    endpoint: String,
    #[arg(long)]
    owner_pid: u32,
    #[arg(long)]
    owner_start_time: u64,
    #[arg(long)]
    log_path: Option<String>,
    #[arg(long, default_value = "normal")]
    mode: String,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let args = Args::parse();
    if args.mode == "foreign-client" {
        #[cfg(unix)]
        let mut client = tokio::net::UnixStream::connect(&args.endpoint).await?;
        #[cfg(windows)]
        let mut client = tokio::net::windows::named_pipe::ClientOptions::new().open(&args.endpoint)?;
        let result = tokio::time::timeout(std::time::Duration::from_secs(2), read_frame::<ServerMessage>(&mut client)).await;
        anyhow::ensure!(matches!(result, Ok(Ok(None)) | Ok(Err(_))), "foreign process was accepted or not promptly rejected");
        return Ok(());
    }
    let owner = OwnerProcess::open(args.owner_pid, args.owner_start_time)?;
    let mut listener = LocalListener::bind(&args.endpoint, &owner)?;
    let mut scribe = Scribe::builder().build();
    scribe.hire();
    let service = DaemonService::new(Arc::new(Mutex::new(scribe)));
    let (shutdown, mut stopped) = watch::channel(false);
    let mut monitor = tokio::time::interval(std::time::Duration::from_millis(100));
    loop {
        tokio::select! {
            _ = stopped.changed() => break,
            _ = monitor.tick() => { if !owner.is_alive() { break; } },
            accepted = listener.accept() => {
                let (mut stream, pid) = accepted?;
                if !owner.authorizes(pid) { continue; }
                let mode = args.mode.clone();
                let shutdown = shutdown.clone();
                let service = service.clone();
                tokio::spawn(async move {
                    if mode == "normal" { let _ = service.serve_connection(stream, shutdown).await; return; }
                    if mode == "no-ready" { std::future::pending::<()>().await; }
                    let ready = ServerMessage { payload: Some(server_message::Payload::Ready(DaemonReady {
                        protocol_version: PROTOCOL_VERSION,
                        pid: std::process::id() + u32::from(mode == "wrong-pid"),
                    })) };
                    if write_frame(&mut stream, &ready).await.is_err() { return; }
                    while let Ok(Some(message)) = read_frame::<ClientMessage>(&mut stream).await {
                        let payload = match message.payload {
                            Some(client_message::Payload::Config(config)) => server_message::Payload::Started(SessionStarted { interface_name: config.interface_name }),
                            Some(client_message::Payload::OutgoingPacket(packet)) => server_message::Payload::IncomingPacket(Packet { data: packet.data }),
                            Some(client_message::Payload::Shutdown(true)) if mode != "stubborn" => { let _ = shutdown.send(true); return; },
                            _ => continue,
                        };
                        if write_frame(&mut stream, &ServerMessage { payload: Some(payload) }).await.is_err() { return; }
                    }
                });
            }
        }
    }
    Ok(())
}
