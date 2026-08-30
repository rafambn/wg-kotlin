use crate::session::{SessionManager, TunSession};
use crate::validation;
use daemon_proto::pb::client_message::Payload as ClientPayload;
use daemon_proto::pb::daemon_server::Daemon;
use daemon_proto::pb::server_message::Payload as ServerPayload;
use daemon_proto::pb::{ClientMessage, Packet, ServerMessage, SessionError, SessionStarted, TunSessionConfig};
use scribe_rs::Scribe;
use serde_json::Value;
use std::collections::HashSet;
use std::pin::Pin;
use std::sync::Arc;
use std::time::Instant;
use tokio::sync::{Mutex, mpsc, oneshot};
use tokio_stream::Stream;
use tokio_stream::wrappers::ReceiverStream;
use tonic::{Request, Response, Status, Streaming};

#[derive(Clone)]
pub struct DaemonGrpcService {
    scribe: Arc<Mutex<Scribe>>,
    active_sessions: Arc<Mutex<HashSet<String>>>,
}

impl DaemonGrpcService {
    pub fn new(scribe: Arc<Mutex<Scribe>>) -> Self {
        Self { scribe, active_sessions: Arc::new(Mutex::new(HashSet::new())) }
    }

    async fn seal_scroll(&self, event: &str, fields: impl IntoIterator<Item = (String, Value)>, success: bool) {
        let scribe = self.scribe.lock().await;
        let mut scroll = scribe.new_scroll(None);
        scroll.insert("event".to_string(), Value::String(event.to_string()));
        for (key, value) in fields {
            scroll.insert(key, value);
        }
        scribe.seal(scroll, success);
    }

    async fn run_session_loop(
        self,
        mut incoming: Streaming<ClientMessage>,
        session: TunSession,
        interface_name: String,
        started_at: Instant,
        session_fields: Vec<(String, Value)>,
        tx: mpsc::Sender<Result<ServerMessage, Status>>,
    ) {
        let read_session = session.clone();
        let read_tx = tx.clone();
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
                        if read_tx.blocking_send(Ok(message)).is_err() {
                            break;
                        }
                    }
                    Err(error) if error.kind() == std::io::ErrorKind::Interrupted => {
                        break;
                    }
                    Err(error) => {
                        let read_error = format!("failed reading TUN packet: {error}");
                        let status = Status::internal(read_error.clone());
                        let _ = read_tx.blocking_send(Ok(error_message(&status)));
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
                    read_done = &mut read_done_rx => {
                        return match read_done {
                            Ok(Some(error_message)) => Err(Status::internal(error_message)),
                            Ok(None) => Ok(()),
                            Err(_) => Ok(()),
                        };
                    }
                    incoming = incoming.message() => incoming?,
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
                        return Err(Status::invalid_argument("config must be sent only as the first session message"));
                    }
                    None => {}
                }
            }
        }
        .await;

        let close_result = session.close();
        let read_join_result = read_task.await;
        let mut sessions = self.active_sessions.lock().await;
        sessions.remove(&interface_name);
        drop(sessions);

        let mut success = true;
        let mut error_message_text: Option<String> = None;
        let mut outcome = "closed";
        let mut error_type: Option<String> = None;

        if let Err(status) = write_result {
            let _ = tx.send(Ok(error_message(&status))).await;
            success = false;
            error_message_text = Some(status.message().to_string());
            error_type = Some(status.code().to_string());
            outcome = "stream_failed";
        }

        if let Err(error) = close_result {
            let status = Status::internal(format!("failed closing TUN session: {error}"));
            let _ = tx.send(Ok(error_message(&status))).await;
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
            vec![("interface".to_string(), Value::String(interface_name)), ("outcome".to_string(), Value::String(outcome.to_string()))],
            success,
        )
        .await;
    }
}

#[tonic::async_trait]
impl Daemon for DaemonGrpcService {
    type SessionStream = Pin<Box<dyn Stream<Item = Result<ServerMessage, Status>> + Send + 'static>>;

    async fn session(&self, request: Request<Streaming<ClientMessage>>) -> Result<Response<Self::SessionStream>, Status> {
        let started_at = Instant::now();
        let mut incoming = request.into_inner();

        let first_message = incoming.message().await?.ok_or_else(|| Status::invalid_argument("first session message must contain config"))?;

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

        Ok(Response::new(Box::pin(ReceiverStream::new(rx))))
    }
}

#[allow(clippy::result_large_err)]
fn extract_config(message: ClientMessage) -> Result<TunSessionConfig, Status> {
    match message.payload {
        Some(ClientPayload::Config(config)) => Ok(config),
        Some(ClientPayload::OutgoingPacket(_)) => Err(Status::invalid_argument("first session message must contain config")),
        None => Err(Status::invalid_argument("first session message payload is missing")),
    }
}

fn error_message(status: &Status) -> ServerMessage {
    ServerMessage { payload: Some(ServerPayload::Error(SessionError { code: status.code().to_string(), message: status.message().to_string() })) }
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
