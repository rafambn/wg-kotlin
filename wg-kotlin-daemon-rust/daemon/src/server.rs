use crate::ipc::{PROTOCOL_VERSION, read_frame, write_frame};
use crate::logging::Logger;
use crate::session::SessionFactory;
use crate::validation;
use daemon_proto::pb::client_message::Payload as ClientPayload;
use daemon_proto::pb::server_message::Payload as ServerPayload;
use daemon_proto::pb::{ClientMessage, DaemonReady, Packet, ServerMessage, SessionError, SessionStarted};
use std::{
    collections::HashSet,
    io,
    time::{Duration, Instant},
};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::sync::{Mutex, mpsc, watch};
use tonic::Status;
#[cfg(not(test))]
use tun_rs::AsyncDevice as PacketDevice;

pub struct DaemonService {
    logger: Logger,
    session_factory: SessionFactory,
    active_sessions: Mutex<HashSet<String>>,
}

impl DaemonService {
    pub fn new(logger: Logger, session_factory: SessionFactory) -> Self {
        Self { logger, session_factory, active_sessions: Mutex::new(HashSet::new()) }
    }

    /// The accept loop authenticates the peer. This task owns the entire session,
    /// including blocking initialization and cleanup after the client disconnects.
    pub async fn serve_connection(&self, mut stream: impl AsyncRead + AsyncWrite + Unpin, shutdown: watch::Sender<bool>) -> io::Result<()> {
        let mut stopping = shutdown.subscribe();
        let first = tokio::select! {
            biased;
            _ = stopping.wait_for(|value| *value) => return Ok(()),
            result = async {
                send(&mut stream, ServerPayload::Ready(DaemonReady { protocol_version: PROTOCOL_VERSION, pid: std::process::id() })).await?;
                tokio::time::timeout(Duration::from_secs(5), read_frame::<ClientMessage>(&mut stream)).await?
            } => result?,
        };
        let Some(first) = first else { return Ok(()) }; // Readiness probe.
        if matches!(first.payload, Some(ClientPayload::Shutdown(true))) {
            let _ = shutdown.send(true);
            return Ok(());
        }
        let (mut reader, mut writer) = tokio::io::split(stream);
        let (outgoing, mut responses) = mpsc::channel(64);
        let mut write_stop = stopping.clone();
        let session = async move {
            let (requested, config) = match first.payload {
                Some(ClientPayload::Config(config)) => (config.interface_name.clone(), Ok(config)),
                _ => (String::new(), Err(Status::invalid_argument("first session message must contain config"))),
            };
            let result = async {
                let config = config?;
                let config = validation::validate_config(&config).map_err(|error| Status::invalid_argument(error.to_string()))?;
                let reserved_name = config.interface_name.clone();
                {
                    let mut sessions = self.active_sessions.lock().await;
                    if sessions.contains(&reserved_name) {
                        return Err(Status::failed_precondition(format!("Session already active for {reserved_name}")));
                    }
                    if sessions.len() >= 16 {
                        return Err(Status::resource_exhausted("Daemon session limit reached (16)"));
                    }
                    sessions.insert(reserved_name.clone());
                }
                // Errors in this scope still return through reservation removal below.
                let lifecycle_result = async {
                    let mut stopping = stopping; // Release this receiver before removing the reservation.
                    if *stopping.borrow() || outgoing.is_closed() {
                        return Ok(());
                    }
                    let started_at = Instant::now();
                    let create_session = self.session_factory.clone();
                    let session = tokio::task::spawn_blocking(move || create_session(&config))
                        .await
                        .unwrap_or_else(|error| Err(anyhow::Error::new(error).context("failed joining session initialization")))
                        .map_err(|error| Status::failed_precondition(format!("{error:#}")))?;
                    let name = session.interface_name.clone();
                    let _ = outgoing.try_send(ServerPayload::Started(SessionStarted { interface_name: name.clone() }));
                    self.logger.log(format_args!("session started interface={name}"));
                    let transfer_result = tokio::select! {
                        biased;
                        _ = stopping.wait_for(|value| *value) => Ok(()),
                        _ = outgoing.closed() => Ok(()),
                        result = transfer(&mut reader, &session.device, &outgoing) => result,
                    };
                    self.report_failure(&name, transfer_result, &outgoing);
                    // Neither a full response queue nor a disconnected client can abandon cleanup.
                    let cleanup_result = tokio::task::spawn_blocking(move || session.close())
                        .await
                        .unwrap_or_else(|error| Err(anyhow::Error::new(error).context("failed joining session cleanup")))
                        .map_err(|error| Status::internal(format!("failed closing TUN session: {error:#}")));
                    self.logger.log(format_args!("session stopped interface={name} duration_ms={}", started_at.elapsed().as_millis()));
                    cleanup_result
                }
                .await;
                self.active_sessions.lock().await.remove(&reserved_name);
                lifecycle_result
            }
            .await;
            self.report_failure(&requested, result, &outgoing);
        };
        let writing = async move {
            tokio::select! {
                biased;
                _ = write_stop.wait_for(|value| *value) => Ok(()),
                result = async {
                    while let Some(payload) = responses.recv().await { send(&mut writer, payload).await?; }
                    Ok(())
                } => result,
            } // Completing this future drops its receiver and wakes a backpressured session.
        };
        let (_, result) = tokio::join!(session, writing);
        result
    }

    fn report_failure(&self, interface: &str, result: Result<(), Status>, outgoing: &mpsc::Sender<ServerPayload>) {
        if let Err(error) = result {
            self.logger.log(format_args!("session failed interface={interface}: {error}"));
            let _ = outgoing.try_send(ServerPayload::Error(SessionError { code: error.code().to_string(), message: error.message().to_string() }));
        }
    }
}

// Keep one reader and one writer alive for each direction. Cancelling a frame
// read is safe only when the entire connection is closing, never per packet.
async fn transfer<R: AsyncRead + Unpin>(reader: &mut R, device: &PacketDevice, outgoing: &mpsc::Sender<ServerPayload>) -> Result<(), Status> {
    tokio::select! {
        result = async {
            while let Some(message) = read_frame::<ClientMessage>(reader).await.map_err(|_| Status::invalid_argument("invalid IPC frame"))? {
                tokio::task::consume_budget().await;
                match message.payload {
                    Some(ClientPayload::OutgoingPacket(packet)) if !packet.data.is_empty() && packet.data.len() <= 65535 => {
                        device.send(&packet.data).await.map_err(|e| Status::internal(format!("failed writing TUN packet: {e}")))?;
                    }
                    Some(ClientPayload::Config(_)) => return Err(Status::invalid_argument("config must be sent only once, at session start")),
                    Some(ClientPayload::Shutdown(_)) => return Err(Status::invalid_argument("shutdown requires a separate control connection")),
                    _ => {},
                }
            }
            Ok(())
        } => result,
        result = async {
            let mut bytes = vec![0; 65536];
            loop {
                // Keep shutdown responsive even when a device repeatedly returns immediately.
                tokio::task::consume_budget().await;
                let size = match device.recv(&mut bytes).await {
                    Ok(size) => size,
                    Err(error) if error.kind() == io::ErrorKind::Interrupted => return Ok(()),
                    Err(error) => return Err(Status::internal(format!("failed reading TUN packet: {error}"))),
                };
                if (1..=65535).contains(&size) && outgoing.send(ServerPayload::IncomingPacket(Packet { data: bytes[..size].to_vec() })).await.is_err() {
                    return Ok(());
                }
            }
        } => result,
    }
}

async fn send<W: AsyncWrite + Unpin>(writer: &mut W, payload: ServerPayload) -> io::Result<()> {
    tokio::time::timeout(Duration::from_secs(5), write_frame(writer, &ServerMessage { payload: Some(payload) })).await?
}

#[cfg(test)]
use tests::PacketDevice;

#[cfg(test)]
#[path = "../tests/unit/server_tests.rs"]
mod tests;
