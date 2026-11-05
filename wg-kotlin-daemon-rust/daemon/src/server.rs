use crate::ipc::{PROTOCOL_VERSION, read_frame, write_frame};
use crate::session::{SessionManager, TunSession};
use crate::validation;
use daemon_proto::pb::client_message::Payload as ClientPayload;
use daemon_proto::pb::server_message::Payload as ServerPayload;
use daemon_proto::pb::{ClientMessage, DaemonReady, Packet, ServerMessage, SessionError, SessionStarted, TunSessionConfig};
use scribe_rs::Scribe;
use serde_json::Value;
use std::collections::HashSet;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::sync::{Mutex, mpsc, oneshot};
use tonic::Status;

#[derive(Clone)]
pub struct DaemonService {
    scribe: Arc<Mutex<Scribe>>,
    active_sessions: Arc<Mutex<HashSet<String>>>,
}

impl DaemonService {
    /// Only the IPC accept loop calls this, after kernel peer authorization.
    pub async fn serve_connection<S>(&self, mut stream: S, shutdown: tokio::sync::watch::Sender<bool>) -> std::io::Result<()>
    where
        S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        let ready =
            ServerMessage { payload: Some(ServerPayload::Ready(DaemonReady { protocol_version: PROTOCOL_VERSION, pid: std::process::id() })) };
        tokio::time::timeout(Duration::from_secs(5), write_frame(&mut stream, &ready)).await??;
        let first: Option<ClientMessage> = tokio::time::timeout(Duration::from_secs(5), read_frame(&mut stream)).await??;
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
        let read = async move {
            loop {
                match read_frame(&mut reader).await {
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
            let mut responses = match service.session(rx).await {
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
        };
        tokio::try_join!(read, write)?;
        Ok(())
    }

    pub fn new(scribe: Arc<Mutex<Scribe>>) -> Self {
        Self { scribe, active_sessions: Arc::new(Mutex::new(HashSet::new())) }
    }

    pub async fn seal_scroll(&self, event: &str, fields: impl IntoIterator<Item = (String, Value)>, success: bool) {
        let scribe = self.scribe.lock().await;
        let mut scroll = scribe.new_scroll(None);
        scroll.insert("event".to_string(), Value::String(event.to_string()));
        for (key, value) in fields {
            scroll.insert(key, value);
        }
        scribe.seal(scroll, success);
    }

    pub async fn wait_for_sessions(&self) {
        while !self.active_sessions.lock().await.is_empty() {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    }

    pub async fn retire_logs(&self) {
        self.scribe.lock().await.retire().await;
    }

    async fn run_session_loop(
        self,
        mut incoming: mpsc::Receiver<Result<ClientMessage, Status>>,
        session: TunSession,
        interface_name: String,
        started_at: Instant,
        session_fields: Vec<(String, Value)>,
        tx: mpsc::Sender<Result<ServerMessage, Status>>,
    ) {
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
        let close_result = session.close();
        let read_join_result = read_task.await;

        let mut success = true;
        let mut error_message_text: Option<String> = None;
        let mut outcome = "closed";
        let mut error_type: Option<String> = None;

        // A full response queue must not hold up the remaining cleanup.
        if let Err(status) = write_result {
            let _ = tx.try_send(Ok(error_message(&status)));
            success = false;
            error_message_text = Some(status.message().to_string());
            error_type = Some(status.code().to_string());
            outcome = "stream_failed";
        }

        if let Err(error) = close_result {
            let status = Status::internal(format!("failed closing TUN session: {error}"));
            let _ = tx.try_send(Ok(error_message(&status)));
            success = false;
            error_type = Some("close_failed".to_string());
            outcome = "close_failed";
            if error_message_text.is_none() {
                error_message_text = Some(status.message().to_string());
            }
        }

        if let Err(join_error) = read_join_result {
            success = false;
            error_type = Some("read_task_join_failed".to_string());
            outcome = "read_task_failed";
            if error_message_text.is_none() {
                error_message_text = Some(format!("failed joining read task: {join_error}"));
            }
        }

        let mut fields = session_fields;
        fields.push(("interface".to_string(), Value::String(interface_name.clone())));
        fields.push(("outcome".to_string(), Value::String(outcome.to_string())));
        fields.push(("duration_ms".to_string(), Value::Number((started_at.elapsed().as_millis() as u64).into())));
        if let Some(error_type) = &error_type {
            fields.push(("error_type".to_string(), Value::String(error_type.clone())));
        }
        if let Some(error_message_text) = &error_message_text {
            fields.push(("error".to_string(), Value::String(error_message_text.clone())));
        }

        self.seal_scroll("daemon_session", fields, success).await;

        self.seal_scroll(
            "daemon_session_stopped",
            vec![("interface".to_string(), Value::String(interface_name.clone())), ("outcome".to_string(), Value::String(outcome.to_string()))],
            success,
        )
        .await;
        self.active_sessions.lock().await.remove(&interface_name);
    }
}

impl DaemonService {
    async fn session(
        &self,
        mut incoming: mpsc::Receiver<Result<ClientMessage, Status>>,
    ) -> Result<mpsc::Receiver<Result<ServerMessage, Status>>, Status> {
        let started_at = Instant::now();
        let first_message =
            incoming.recv().await.transpose()?.ok_or_else(|| Status::invalid_argument("first session message must contain config"))?;

        let config = extract_config(first_message)?;
        let session_fields = session_config_fields(&config);
        if let Err(error_message) = validation::validate_config(&config) {
            let mut fields = session_fields.clone();
            fields.push(("outcome".to_string(), Value::String("rejected".to_string())));
            fields.push(("error".to_string(), Value::String(error_message.clone())));
            fields.push(("error_type".to_string(), Value::String("invalid_argument".to_string())));
            fields.push(("duration_ms".to_string(), Value::Number((started_at.elapsed().as_millis() as u64).into())));
            self.seal_scroll("daemon_session", fields, false).await;
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
            let mut fields = session_fields.clone();
            fields.push(("outcome".to_string(), Value::String("rejected".to_string())));
            fields.push(("error".to_string(), Value::String(error_message.clone())));
            fields.push(("error_type".to_string(), Value::String("failed_precondition".to_string())));
            fields.push(("duration_ms".to_string(), Value::Number((started_at.elapsed().as_millis() as u64).into())));
            self.seal_scroll("daemon_session", fields, false).await;
            return Err(Status::failed_precondition(error_message));
        }

        if session_limit_reached {
            let error_message = format!("Daemon session limit reached ({MAX_ACTIVE_SESSIONS})");
            let mut fields = session_fields.clone();
            fields.push(("outcome".to_string(), Value::String("rejected".to_string())));
            fields.push(("error".to_string(), Value::String(error_message.clone())));
            fields.push(("error_type".to_string(), Value::String("resource_exhausted".to_string())));
            fields.push(("duration_ms".to_string(), Value::Number((started_at.elapsed().as_millis() as u64).into())));
            self.seal_scroll("daemon_session", fields, false).await;
            return Err(Status::resource_exhausted(error_message));
        }

        let session = match SessionManager::start(&config) {
            Ok(session) => session,
            Err(error_message) => {
                self.active_sessions.lock().await.remove(&config.interface_name);
                let mut fields = session_fields.clone();
                fields.push(("outcome".to_string(), Value::String("start_failed".to_string())));
                fields.push(("error".to_string(), Value::String(error_message.clone())));
                fields.push(("error_type".to_string(), Value::String("start_failed".to_string())));
                fields.push(("duration_ms".to_string(), Value::Number((started_at.elapsed().as_millis() as u64).into())));
                self.seal_scroll("daemon_session", fields, false).await;
                return Err(Status::failed_precondition(error_message));
            }
        };

        let interface_name = session.interface_name().to_string();

        let (tx, rx) = mpsc::channel::<Result<ServerMessage, Status>>(PACKET_CHANNEL_CAPACITY);
        let _ = tx
            .send(Ok(ServerMessage {
                payload: Some(ServerPayload::Started(SessionStarted { interface_name: session.interface_name().to_string() })),
            }))
            .await;
        let mut started_fields = session_fields.clone();
        started_fields.push(("interface".to_string(), Value::String(session.interface_name().to_string())));
        self.seal_scroll("daemon_session_started", started_fields, true).await;

        let service = self.clone();
        tokio::spawn(async move {
            service.run_session_loop(incoming, session, interface_name, started_at, session_fields, tx).await;
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

fn session_config_fields(config: &TunSessionConfig) -> Vec<(String, Value)> {
    let (dns_server_count, dns_domain_count) = match &config.dns {
        Some(dns) => (dns.servers.len(), dns.search_domains.len()),
        None => (0, 0),
    };

    vec![
        ("requested_interface".to_string(), Value::String(config.interface_name.clone())),
        ("platform".to_string(), Value::String(std::env::consts::OS.to_string())),
        ("address_count".to_string(), Value::Number((config.addresses.len() as u64).into())),
        ("route_count".to_string(), Value::Number((config.peer_allowed_ips.len() as u64).into())),
        ("endpoint_count".to_string(), Value::Number((config.peer_endpoints.len() as u64).into())),
        ("dns_server_count".to_string(), Value::Number((dns_server_count as u64).into())),
        ("dns_domain_count".to_string(), Value::Number((dns_domain_count as u64).into())),
        ("mtu_configured".to_string(), Value::Bool(config.mtu != 0)),
    ]
}

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

    #[cfg(target_os = "macos")]
    #[tokio::test]
    async fn equivalent_macos_names_share_the_reservation_even_at_capacity() {
        use daemon_proto::pb::{Cidr, Ip, ip};

        let mut scribe = Scribe::builder().build();
        scribe.hire();
        let service = DaemonService::new(Arc::new(Mutex::new(scribe)));
        // Fill the reservations so even an unfixed implementation cannot reach
        // TUN creation. An alias must be rejected as a duplicate, not capacity.
        service.active_sessions.lock().await.extend((0..MAX_ACTIVE_SESSIONS).map(|index| format!("utun{index}")));
        for (name, expected_code) in [
            ("utun01", tonic::Code::FailedPrecondition),
            ("utun00000000001", tonic::Code::FailedPrecondition),
            ("utun000", tonic::Code::FailedPrecondition),
            ("utun00000000000", tonic::Code::FailedPrecondition),
            ("utun000000000001", tonic::Code::InvalidArgument),
        ] {
            let config = TunSessionConfig {
                interface_name: name.to_string(),
                addresses: vec![Cidr { ip: Some(Ip { value: Some(ip::Value::V4(vec![10, 0, 0, 1])) }), prefix: 24 }],
                ..Default::default()
            };
            let (tx, rx) = mpsc::channel(1);
            tx.send(Ok(ClientMessage { payload: Some(ClientPayload::Config(config)) })).await.unwrap();
            let error = service.session(rx).await.unwrap_err();
            assert_eq!(error.code(), expected_code, "{name}: {error}");
            assert_eq!(service.active_sessions.lock().await.len(), MAX_ACTIVE_SESSIONS);
        }
        service.retire_logs().await;
    }
}
