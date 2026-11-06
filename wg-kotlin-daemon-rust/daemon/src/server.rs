use crate::ipc::{PROTOCOL_VERSION, read_frame, write_frame};
use crate::logging::Logger;
use crate::session::{SessionManager, TunSession};
use crate::validation;
use daemon_proto::pb::client_message::Payload as ClientPayload;
use daemon_proto::pb::server_message::Payload as ServerPayload;
use daemon_proto::pb::{ClientMessage, DaemonReady, Packet, ServerMessage, SessionError, SessionStarted, TunSessionConfig};
use std::collections::HashSet;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::sync::{Mutex, mpsc, oneshot, watch};
use tonic::Status;

#[derive(Clone)]
pub struct DaemonService {
    logger: Logger,
    active_sessions: Arc<Mutex<HashSet<String>>>,
}

impl DaemonService {
    /// Only the IPC accept loop calls this, after kernel peer authorization.
    pub async fn serve_connection<S>(&self, mut stream: S, shutdown: watch::Sender<bool>) -> std::io::Result<()>
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        let ready =
            ServerMessage { payload: Some(ServerPayload::Ready(DaemonReady { protocol_version: PROTOCOL_VERSION, pid: std::process::id() })) };
        let mut stopping = shutdown.subscribe();
        let first: Option<ClientMessage> = tokio::select! {
            biased;
            _ = stopping.wait_for(|stopped| *stopped) => return Ok(()),
            result = async {
                tokio::time::timeout(Duration::from_secs(5), write_frame(&mut stream, &ready)).await??;
                tokio::time::timeout(Duration::from_secs(5), read_frame(&mut stream)).await?
            } => result?,
        };
        let Some(first) = first else {
            return Ok(());
        }; // Readiness probe.
        if matches!(first.payload, Some(ClientPayload::Shutdown(true))) {
            let _ = shutdown.send(true);
            return Ok(());
        }
        let (mut reader, mut writer) = tokio::io::split(stream);
        let (tx, rx) = mpsc::channel(PACKET_CHANNEL_CAPACITY);
        tx.send(Ok(first)).await.map_err(std::io::Error::other)?;
        let service = self.clone();
        let mut read_shutdown = stopping.clone();
        let read = async move {
            loop {
                let result = tokio::select! {
                    biased;
                    _ = read_shutdown.wait_for(|stopped| *stopped) => return Ok(()),
                    result = read_frame(&mut reader) => result,
                };
                match result {
                    Ok(Some(message)) => {
                        if tx.send(Ok(message)).await.is_err() {
                            return Ok::<_, std::io::Error>(());
                        }
                    }
                    Ok(None) => return Ok(()),
                    Err(error) => {
                        let _ = tx.send(Err(Status::invalid_argument("invalid IPC frame"))).await;
                        return Err(error);
                    }
                }
            }
        };
        let write = async move {
            // Initialization keeps its own task until teardown; cancelling
            // socket writes only drops the response receiver.
            let responses = service.session(rx, stopping.clone()).await;
            tokio::select! {
                biased;
                _ = stopping.wait_for(|stopped| *stopped) => Ok(()),
                result = async {
                    let mut responses = match responses {
                        Ok(responses) => responses,
                        Err(status) => {
                            tokio::time::timeout(Duration::from_secs(5), write_frame(&mut writer, &error_message(&status))).await??;
                            return Ok(());
                        }
                    };
                    while let Some(message) = responses.recv().await {
                        let message = message.unwrap_or_else(|status| error_message(&status));
                        tokio::time::timeout(Duration::from_secs(5), write_frame(&mut writer, &message)).await??;
                    }
                    Ok::<_, std::io::Error>(())
                } => result,
            }
        };
        tokio::try_join!(read, write)?;
        Ok(())
    }

    pub fn new(logger: Logger) -> Self {
        Self { logger, active_sessions: Arc::new(Mutex::new(HashSet::new())) }
    }

    pub async fn wait_for_sessions(&self) {
        while !self.active_sessions.lock().await.is_empty() {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    }

    async fn run_session_loop(
        self,
        mut incoming: mpsc::Receiver<Result<ClientMessage, Status>>,
        session: TunSession,
        mut shutdown: watch::Receiver<bool>,
        started_at: Instant,
        tx: mpsc::Sender<Result<ServerMessage, Status>>,
    ) {
        let interface_name = session.interface_name().to_string();
        let read_session = session.clone();
        let read_tx = tx.clone();
        let (stop_read_tx, mut stop_read_rx) = oneshot::channel();
        let (read_done_tx, mut read_done_rx) = oneshot::channel::<Option<String>>();
        let read_task = tokio::task::spawn_blocking(move || {
            let mut read_error_message: Option<String> = None;
            loop {
                match read_session.read_packet() {
                    Ok(packet_bytes) => {
                        if packet_bytes.len() > MAX_PACKET_FRAME_SIZE || packet_bytes.is_empty() {
                            continue;
                        }
                        let message = ServerMessage { payload: Some(ServerPayload::IncomingPacket(Packet { data: packet_bytes })) };
                        if !tokio::runtime::Handle::current().block_on(send_response(&read_tx, message, &mut stop_read_rx)) {
                            break;
                        }
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::Interrupted => {
                        break;
                    }
                    Err(error) => {
                        let read_error = format!("failed reading TUN packet: {error}");
                        let status = Status::internal(read_error.clone());
                        let _ = tokio::runtime::Handle::current().block_on(send_response(&read_tx, error_message(&status), &mut stop_read_rx));
                        read_error_message = Some(read_error);
                        break;
                    }
                }
            }

            let _ = read_done_tx.send(read_error_message);
        });

        let write_result = async {
            loop {
                let next_message = tokio::select! {
                    biased;
                    _ = shutdown.wait_for(|stopped| *stopped) => return Ok(()),
                    _ = tx.closed() => return Ok(()),
                    read_done = &mut read_done_rx => {
                        return match read_done {
                            Ok(Some(error_message)) => Err(Status::internal(error_message)),
                            Ok(None) => Ok(()),
                            Err(_) => Ok(()),
                        };
                    }
                    incoming = async { incoming.recv().await.transpose() } => incoming?,
                };

                let Some(message) = next_message else {
                    return Ok(());
                };

                match message.payload {
                    Some(ClientPayload::OutgoingPacket(packet)) => {
                        if packet.data.len() > MAX_PACKET_FRAME_SIZE || packet.data.is_empty() {
                            continue;
                        }
                        session.write_packet(&packet.data).map_err(|error| Status::internal(format!("failed writing TUN packet: {error}")))?;
                    }
                    Some(ClientPayload::Config(_)) => {
                        return Err(Status::invalid_argument("config must be sent only once, at session start"));
                    }
                    Some(ClientPayload::Shutdown(_)) => {
                        return Err(Status::invalid_argument("shutdown requires a separate control connection"));
                    }
                    None => {}
                }
            }
        }
        .await;

        let _ = stop_read_tx.send(());
        // Route/DNS teardown is blocking; keep the daemon's shutdown timer free.
        let close_result = tokio::task::spawn_blocking(move || session.close())
            .await
            .unwrap_or_else(|error| Err(format!("failed joining session cleanup: {error}")));
        let read_join_result = read_task.await;

        // A full response queue must not hold up the remaining cleanup.
        if let Err(status) = write_result {
            self.logger.log(format_args!("session failed interface={interface_name}: {status}"));
            let _ = tx.try_send(Ok(error_message(&status)));
        }
        if let Err(error) = close_result {
            self.logger.log(format_args!("session cleanup failed interface={interface_name}: {error}"));
            let status = Status::internal(format!("failed closing TUN session: {error}"));
            let _ = tx.try_send(Ok(error_message(&status)));
        }
        if let Err(error) = read_join_result {
            self.logger.log(format_args!("session reader failed interface={interface_name}: {error}"));
        }
        self.logger.log(format_args!("session stopped interface={interface_name} duration_ms={}", started_at.elapsed().as_millis()));
        self.active_sessions.lock().await.remove(&interface_name);
    }
}

impl DaemonService {
    async fn session(
        &self,
        mut incoming: mpsc::Receiver<Result<ClientMessage, Status>>,
        shutdown: watch::Receiver<bool>,
    ) -> Result<mpsc::Receiver<Result<ServerMessage, Status>>, Status> {
        if *shutdown.borrow() {
            return Err(Status::unavailable("daemon is shutting down"));
        }
        let started_at = Instant::now();
        let first_message =
            incoming.recv().await.transpose()?.ok_or_else(|| Status::invalid_argument("first session message must contain config"))?;

        let config = extract_config(first_message)?;
        if let Err(error_message) = validation::validate_config(&config) {
            self.logger.log(format_args!("session rejected interface={}: {error_message}", config.interface_name));
            return Err(Status::invalid_argument(error_message));
        }

        #[cfg(target_os = "macos")]
        let config = {
            let index = config.interface_name[4..].trim_start_matches('0');
            TunSessionConfig { interface_name: format!("utun{}", if index.is_empty() { "0" } else { index }), ..config }
        };

        let mut duplicate_session = false;
        let mut session_limit_reached = false;
        {
            let mut sessions = self.active_sessions.lock().await;
            if sessions.contains(&config.interface_name) {
                duplicate_session = true;
            } else if sessions.len() >= MAX_ACTIVE_SESSIONS {
                session_limit_reached = true;
            } else {
                sessions.insert(config.interface_name.clone());
            }
        }

        if duplicate_session {
            let error_message = format!("Session already active for {}", config.interface_name);
            self.logger.log(format_args!("session rejected interface={}: {error_message}", config.interface_name));
            return Err(Status::failed_precondition(error_message));
        }

        if session_limit_reached {
            let error_message = format!("Daemon session limit reached ({MAX_ACTIVE_SESSIONS})");
            self.logger.log(format_args!("session rejected interface={}: {error_message}", config.interface_name));
            return Err(Status::resource_exhausted(error_message));
        }

        let (tx, rx) = mpsc::channel::<Result<ServerMessage, Status>>(PACKET_CHANNEL_CAPACITY);
        let service = self.clone();
        // This task owns initialization through teardown, even if the client
        // disconnects while the blocking worker is still configuring the OS.
        tokio::spawn(async move {
            let interface_name = config.interface_name.clone();
            if tx.is_closed() || *shutdown.borrow() {
                service.active_sessions.lock().await.remove(&interface_name);
                return;
            }
            let start_result = tokio::task::spawn_blocking(move || SessionManager::start(&config))
                .await
                .unwrap_or_else(|error| Err(format!("failed joining session initialization: {error}")));
            let session = match start_result {
                Ok(session) => session,
                Err(error_message) => {
                    service.logger.log(format_args!("session start failed interface={interface_name}: {error_message}"));
                    service.active_sessions.lock().await.remove(&interface_name);
                    let _ = tx.try_send(Err(Status::failed_precondition(error_message)));
                    return;
                }
            };
            let _ = tx
                .send(Ok(ServerMessage {
                    payload: Some(ServerPayload::Started(SessionStarted { interface_name: session.interface_name().to_string() })),
                }))
                .await;
            service.logger.log(format_args!("session started interface={}", session.interface_name()));
            service.run_session_loop(incoming, session, shutdown, started_at, tx).await;
        });

        Ok(rx)
    }
}

#[allow(clippy::result_large_err)]
fn extract_config(message: ClientMessage) -> Result<TunSessionConfig, Status> {
    match message.payload {
        Some(ClientPayload::Config(config)) => Ok(config),
        Some(ClientPayload::OutgoingPacket(_) | ClientPayload::Shutdown(_)) => {
            Err(Status::invalid_argument("first session message must contain config"))
        }
        None => Err(Status::invalid_argument("first session message payload is missing")),
    }
}

fn error_message(status: &Status) -> ServerMessage {
    ServerMessage { payload: Some(ServerPayload::Error(SessionError { code: status.code().to_string(), message: status.message().to_string() })) }
}

async fn send_response(tx: &mpsc::Sender<Result<ServerMessage, Status>>, message: ServerMessage, stop: &mut oneshot::Receiver<()>) -> bool {
    tokio::select! {
        biased;
        _ = stop => false,
        result = tx.send(Ok(message)) => result.is_ok(),
    }
}

const MAX_PACKET_FRAME_SIZE: usize = 65535;
const MAX_ACTIVE_SESSIONS: usize = 16;
const PACKET_CHANNEL_CAPACITY: usize = 64;

#[cfg(test)]
mod tests {
    use super::*;
    use std::future::{Future, poll_fn};
    use std::task::Poll;

    #[tokio::test]
    async fn blocked_reader_sends_stop_without_draining_the_response_queue() {
        for message in [
            ServerMessage { payload: Some(ServerPayload::IncomingPacket(Packet { data: vec![1] })) },
            error_message(&Status::internal("TUN read failed")),
        ] {
            let (tx, rx) = mpsc::channel(1);
            tx.try_send(Ok(ServerMessage::default())).unwrap();
            let (stop_tx, mut stop_rx) = oneshot::channel();
            let (waiting_tx, waiting_rx) = oneshot::channel();
            let mut reader = tokio::task::spawn_blocking(move || {
                tokio::runtime::Handle::current().block_on(async {
                    let send = send_response(&tx, message, &mut stop_rx);
                    tokio::pin!(send);
                    poll_fn(|context| {
                        assert!(send.as_mut().poll(context).is_pending());
                        Poll::Ready(())
                    })
                    .await;
                    let _ = waiting_tx.send(());
                    send.await
                })
            });
            let waiting = tokio::time::timeout(Duration::from_secs(2), waiting_rx).await;
            let _ = stop_tx.send(());
            let completed = tokio::time::timeout(Duration::from_secs(2), &mut reader).await;
            // Release a failed implementation before asserting, so a blocked
            // worker cannot hang the test runtime during shutdown.
            drop(rx);
            let result = match completed {
                Ok(result) => result.unwrap(),
                Err(error) => {
                    let _ = reader.await;
                    panic!("reader send did not stop: {error}");
                }
            };
            waiting.unwrap().unwrap();
            assert!(!result);
        }
    }

    #[tokio::test]
    async fn stopped_reader_does_not_enqueue_another_response() {
        let (tx, mut rx) = mpsc::channel(1);
        let (stop_tx, mut stop_rx) = oneshot::channel();
        stop_tx.send(()).unwrap();
        assert!(!send_response(&tx, ServerMessage::default(), &mut stop_rx).await);
        assert!(rx.try_recv().is_err());
    }

    #[tokio::test]
    async fn shutdown_closes_idle_and_backpressured_connections() {
        use tokio::io::AsyncReadExt;

        for phase in ["before_ready", "idle", "blocked_error"] {
            let service = DaemonService::new(Logger::new(None).unwrap());
            let (shutdown, _receiver) = watch::channel(false);
            if phase == "before_ready" {
                shutdown.send_replace(true);
            }
            let (mut client, stream) = tokio::io::duplex(1);
            let connection_service = service.clone();
            let connection_shutdown = shutdown.clone();
            let mut connection = tokio::spawn(async move { connection_service.serve_connection(stream, connection_shutdown).await });
            if phase != "before_ready" {
                let ready = read_frame::<ServerMessage>(&mut client).await.unwrap().unwrap();
                assert!(matches!(ready.payload, Some(ServerPayload::Ready(_))));
                if phase == "blocked_error" {
                    write_frame(&mut client, &ClientMessage { payload: Some(ClientPayload::Config(TunSessionConfig::default())) }).await.unwrap();
                    // Read only one byte: the error frame cannot fit in this
                    // stream, so its write remains blocked until shutdown.
                    client.read_u8().await.unwrap();
                }
                shutdown.send(true).unwrap();
            }
            let result = tokio::time::timeout(Duration::from_secs(2), &mut connection).await;
            if result.is_err() {
                connection.abort();
                let _ = connection.await;
            }
            drop(client);
            result.unwrap().unwrap().unwrap();
            assert!(service.active_sessions.lock().await.is_empty());
        }
    }

    #[tokio::test]
    async fn failed_or_cancelled_start_releases_the_reservation() {
        use daemon_proto::pb::{Cidr, Ip, ip};

        for cancel in [false, true] {
            let service = DaemonService::new(Logger::new(None).unwrap());
            let (_shutdown_tx, shutdown) = watch::channel(false);
            let config = TunSessionConfig {
                interface_name: "utun7".to_string(),
                addresses: vec![Cidr { ip: Some(Ip { value: Some(ip::Value::V4(vec![10, 0, 0, 1])) }), prefix: 24 }],
                // Route resolution fails before any TUN or OS configuration.
                peer_endpoints: vec![Ip { value: None }],
                ..Default::default()
            };
            let (tx, rx) = mpsc::channel(1);
            tx.send(Ok(ClientMessage { payload: Some(ClientPayload::Config(config)) })).await.unwrap();
            let mut responses = service.session(rx, shutdown).await.unwrap();
            if cancel {
                drop(responses);
            } else {
                let error = tokio::time::timeout(Duration::from_secs(2), responses.recv()).await.unwrap().unwrap().unwrap_err();
                assert_eq!(error.code(), tonic::Code::FailedPrecondition);
            }
            tokio::time::timeout(Duration::from_secs(2), service.wait_for_sessions()).await.unwrap();
        }
    }

    #[cfg(target_os = "macos")]
    #[tokio::test]
    async fn macos_name_validation_and_normalization_precede_reservation() {
        use daemon_proto::pb::{Cidr, Ip, ip};

        let service = DaemonService::new(Logger::new(None).unwrap());
        let (_shutdown_tx, shutdown) = watch::channel(false);
        // Fill the reservations so even an unfixed implementation cannot reach
        // TUN creation. An alias must be rejected as a duplicate, not capacity.
        service.active_sessions.lock().await.extend((0..MAX_ACTIVE_SESSIONS).map(|index| format!("utun{index}")));
        for (name, expected_code) in [
            ("utun01", tonic::Code::FailedPrecondition),
            ("utun00000000001", tonic::Code::FailedPrecondition),
            ("utun000", tonic::Code::FailedPrecondition),
            ("utun00000000000", tonic::Code::FailedPrecondition),
            ("utun000000000001", tonic::Code::InvalidArgument),
            ("utun4294967295", tonic::Code::InvalidArgument),
            ("utun04294967295", tonic::Code::InvalidArgument),
            ("utun4294967296", tonic::Code::InvalidArgument),
            ("utun99999999999", tonic::Code::InvalidArgument),
            ("utun4294967294", tonic::Code::ResourceExhausted),
            ("utun04294967294", tonic::Code::ResourceExhausted),
        ] {
            let config = TunSessionConfig {
                interface_name: name.to_string(),
                addresses: vec![Cidr { ip: Some(Ip { value: Some(ip::Value::V4(vec![10, 0, 0, 1])) }), prefix: 24 }],
                ..Default::default()
            };
            let (tx, rx) = mpsc::channel(1);
            tx.send(Ok(ClientMessage { payload: Some(ClientPayload::Config(config)) })).await.unwrap();
            let error = service.session(rx, shutdown.clone()).await.unwrap_err();
            assert_eq!(error.code(), expected_code, "{name}: {error}");
            assert_eq!(service.active_sessions.lock().await.len(), MAX_ACTIVE_SESSIONS);
        }
    }
}
