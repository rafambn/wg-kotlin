use super::*;
use std::{future::Future, pin::Pin, sync::Arc};

pub(super) type PacketDevice = dyn PacketIo;
type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;
pub(super) trait PacketIo: Send + Sync {
    fn recv<'a>(&'a self, bytes: &'a mut [u8]) -> BoxFuture<'a, io::Result<usize>>;
    fn send<'a>(&'a self, bytes: &'a [u8]) -> BoxFuture<'a, io::Result<usize>>;
}
impl PacketIo for tun_rs::AsyncDevice {
    fn recv<'a>(&'a self, bytes: &'a mut [u8]) -> BoxFuture<'a, io::Result<usize>> {
        Box::pin(tun_rs::AsyncDevice::recv(self, bytes))
    }
    fn send<'a>(&'a self, bytes: &'a [u8]) -> BoxFuture<'a, io::Result<usize>> {
        Box::pin(tun_rs::AsyncDevice::send(self, bytes))
    }
}

use daemon_proto::pb::{Cidr, Ip, TunSessionConfig, ip};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::mpsc;

fn session_factory() -> SessionFactory {
    #[cfg(not(windows))]
    return crate::session::factory().unwrap();
    #[cfg(windows)]
    Arc::new(|_| anyhow::bail!("Wintun DLL is not initialized"))
}

#[tokio::test]
async fn first_message_requires_config_but_accepts_a_separate_shutdown_request() {
    // An unknown protobuf field gives a nonempty, valid frame with no known payload.
    let mut frames = vec![vec![0x78, 0x01]];
    for payload in [ClientPayload::OutgoingPacket(Packet { data: vec![1] }), ClientPayload::Shutdown(false), ClientPayload::Shutdown(true)] {
        frames.push(prost::Message::encode_to_vec(&ClientMessage { payload: Some(payload) }));
    }
    for (index, bytes) in frames.into_iter().enumerate() {
        let service = Arc::new(DaemonService::new(Logger::new(None).unwrap(), session_factory()));
        let connection_service = service.clone();
        let (shutdown, receiver) = watch::channel(false);
        let (mut client, stream) = tokio::io::duplex(4096);
        let connection = tokio::spawn(async move { connection_service.serve_connection(stream, shutdown).await });
        let ready = read_frame::<ServerMessage>(&mut client).await.unwrap().unwrap();
        assert!(matches!(ready.payload, Some(ServerPayload::Ready(_))));
        client.write_all(&(bytes.len() as u32).to_be_bytes()).await.unwrap();
        client.write_all(&bytes).await.unwrap();
        let response = tokio::time::timeout(Duration::from_secs(2), read_frame::<ServerMessage>(&mut client)).await.unwrap().unwrap();
        if index == 3 {
            assert!(response.is_none());
            assert!(*receiver.borrow());
        } else {
            let Some(ServerPayload::Error(error)) = response.unwrap().payload else { panic!("expected configuration error") };
            assert_eq!(error.code, tonic::Code::InvalidArgument.to_string());
            assert!(error.message.contains("config"));
            assert!(!*receiver.borrow());
        }
        tokio::time::timeout(Duration::from_secs(2), connection).await.unwrap().unwrap().unwrap();
        assert!(service.active_sessions.lock().await.is_empty());
    }
}

#[tokio::test]
async fn immediately_ready_packet_io_does_not_starve_cancellation() {
    use std::sync::atomic::{AtomicUsize, Ordering};

    struct ReadyDevice {
        calls: AtomicUsize,
        receive: bool,
    }
    impl PacketIo for ReadyDevice {
        fn recv<'a>(&'a self, _: &'a mut [u8]) -> BoxFuture<'a, io::Result<usize>> {
            Box::pin(async move {
                if !self.receive {
                    return std::future::pending().await;
                }
                // A finite guard makes a regressed implementation fail instead of hanging the test.
                if self.calls.fetch_add(1, Ordering::Relaxed) >= 10_000 {
                    return Err(io::Error::other("read loop never yielded"));
                }
                Ok(0)
            })
        }
        fn send<'a>(&'a self, bytes: &'a [u8]) -> BoxFuture<'a, io::Result<usize>> {
            Box::pin(async move {
                self.calls.fetch_add(1, Ordering::Relaxed);
                Ok(bytes.len())
            })
        }
    }

    let mut frame = Vec::new();
    write_frame(&mut frame, &ClientMessage { payload: Some(ClientPayload::OutgoingPacket(Packet { data: vec![1] })) }).await.unwrap();
    let frames = frame.repeat(10_000);
    for receive in [false, true] {
        let device = ReadyDevice { calls: AtomicUsize::new(0), receive };
        let (sender, _responses) = mpsc::channel(64);
        let (waiting, _peer) = tokio::io::duplex(1);
        let mut reader: Box<dyn AsyncRead + Unpin> = if receive { Box::new(waiting) } else { Box::new(&frames[..]) };
        let cancelled = tokio::select! {
            biased;
            _ = tokio::task::yield_now() => true,
            _ = super::transfer(&mut reader, &device, &sender) => false,
        };
        assert!(cancelled, "packet loop did not yield, receive={receive}");
        assert!(device.calls.load(Ordering::Relaxed) < 10_000);
    }
}

async fn transfer<S: AsyncRead + AsyncWrite + Unpin>(stream: &mut S, device: &PacketDevice) -> Result<(), Status> {
    let (mut reader, mut writer) = tokio::io::split(stream);
    let (outgoing, mut responses) = mpsc::channel(64);
    let forwarding = async move { super::transfer(&mut reader, device, &outgoing).await };
    let writing = async move {
        while let Some(payload) = responses.recv().await {
            send(&mut writer, payload).await.map_err(|e| Status::internal(e.to_string()))?;
        }
        Ok(())
    };
    let (forwarding, writing) = tokio::join!(forwarding, writing);
    forwarding.and(writing)
}

fn config(name: &str) -> TunSessionConfig {
    TunSessionConfig {
        interface_name: name.into(),
        addresses: vec![Cidr { ip: Some(Ip { value: Some(ip::Value::V4(vec![10, 0, 0, 1])) }), prefix: 24 }],
        peer_endpoints: vec![Ip { value: None }], // Fail before creating a TUN.
        ..Default::default()
    }
}

async fn request(service: &Arc<DaemonService>, config: TunSessionConfig) -> SessionError {
    let (mut client, stream) = tokio::io::duplex(4096);
    let service = service.clone();
    let (shutdown, _receiver) = watch::channel(false);
    let connection = tokio::spawn(async move { service.serve_connection(stream, shutdown).await });
    read_frame::<ServerMessage>(&mut client).await.unwrap().unwrap();
    write_frame(&mut client, &ClientMessage { payload: Some(ClientPayload::Config(config)) }).await.unwrap();
    let response = tokio::time::timeout(Duration::from_secs(2), read_frame::<ServerMessage>(&mut client)).await.unwrap().unwrap().unwrap();
    connection.await.unwrap().unwrap();
    let Some(ServerPayload::Error(error)) = response.payload else { panic!("expected an error") };
    error
}

#[tokio::test]
async fn invalid_mtu_reports_the_applicable_range_without_starting_a_session() {
    let service = Arc::new(DaemonService::new(Logger::new(None).unwrap(), Arc::new(|_| panic!("invalid MTU must not initialize a session"))));
    let ipv4 = Cidr { ip: Some(Ip { value: Some(ip::Value::V4(vec![10, 0, 0, 1])) }), prefix: 24 };
    let ipv6 = Cidr { ip: Some(Ip { value: Some(ip::Value::V6(vec![0; 16])) }), prefix: 64 };
    let cases = [
        (vec![ipv4.clone()], vec![i32::MIN, -1, 1, 575, 65536, i32::MAX], "MTU must be between 576 and 65535"),
        (vec![ipv6.clone()], vec![i32::MIN, -1, 1, 575, 576, 1279, 65536, i32::MAX], "MTU must be between 1280 and 65535"),
        (vec![ipv4, ipv6], vec![i32::MIN, -1, 1, 575, 576, 1279, 65536, i32::MAX], "MTU must be between 1280 and 65535"),
    ];
    for (addresses, values, expected_message) in cases {
        for mtu in values {
            let mut config = config("utun0");
            config.addresses = addresses.clone();
            config.mtu = mtu;
            let error = request(&service, config).await;
            assert_eq!(error.code, tonic::Code::InvalidArgument.to_string());
            assert_eq!(error.message, expected_message);
            assert!(service.active_sessions.lock().await.is_empty());
        }
    }
}

#[tokio::test]
async fn shutdown_closes_idle_and_backpressured_connections() {
    use tokio::io::AsyncReadExt;

    for phase in ["before_ready", "idle", "blocked_error"] {
        let service = Arc::new(DaemonService::new(Logger::new(None).unwrap(), session_factory()));
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
async fn failed_or_disconnected_start_releases_the_reservation() {
    let service = Arc::new(DaemonService::new(Logger::new(None).unwrap(), session_factory()));
    let error = request(&service, config("utun7")).await;
    assert_eq!(error.code, tonic::Code::FailedPrecondition.to_string());
    let expected_cause = if cfg!(windows) { "Wintun DLL is not initialized" } else { "invalid peer endpoint" };
    assert!(error.message.contains(expected_cause), "{error:?}");
    assert!(service.active_sessions.lock().await.is_empty());
    let (mut client, stream) = tokio::io::duplex(4096);
    let connection_service = service.clone();
    let (shutdown, _receiver) = watch::channel(false);
    let connection = tokio::spawn(async move { connection_service.serve_connection(stream, shutdown).await });
    read_frame::<ServerMessage>(&mut client).await.unwrap().unwrap();
    write_frame(&mut client, &ClientMessage { payload: Some(ClientPayload::Config(config("utun7"))) }).await.unwrap();
    drop(client);
    let _ = tokio::time::timeout(Duration::from_secs(2), connection).await.unwrap().unwrap();
    assert!(service.active_sessions.lock().await.is_empty());
}

#[tokio::test]
async fn occupied_interfaces_and_capacity_are_rejected_before_starting_a_tun() {
    let service = Arc::new(DaemonService::new(Logger::new(None).unwrap(), session_factory()));
    service.active_sessions.lock().await.extend((0..16).map(|index| format!("utun{index}")));
    assert_eq!(request(&service, config("utun0")).await.code, tonic::Code::FailedPrecondition.to_string());
    assert_eq!(request(&service, config("utun16")).await.code, tonic::Code::ResourceExhausted.to_string());
    #[cfg(target_os = "macos")]
    for name in ["utun00", "utun00000000000", "utun01"] {
        assert_eq!(request(&service, config(name)).await.code, tonic::Code::FailedPrecondition.to_string());
    }
    for name in ["wg0", "utun000000000001"] {
        assert_eq!(request(&service, config(name)).await.code, tonic::Code::InvalidArgument.to_string());
    }
    assert_eq!(service.active_sessions.lock().await.len(), 16);
}

#[tokio::test]
async fn initialization_is_awaited_after_shutdown_or_client_disconnect() {
    use std::sync::{Condvar, Mutex as StdMutex};

    struct ReleaseOnDrop(Arc<(StdMutex<bool>, Condvar)>);
    impl Drop for ReleaseOnDrop {
        fn drop(&mut self) {
            *self.0.0.lock().unwrap() = true;
            self.0.1.notify_all();
        }
    }

    for disconnect in [false, true] {
        let gate = Arc::new((StdMutex::new(false), Condvar::new()));
        let release = ReleaseOnDrop(gate.clone());
        let entered = Arc::new(tokio::sync::Notify::new());
        let entered_by_worker = entered.clone();
        let factory: SessionFactory = Arc::new(move |config| {
            assert_eq!(config.interface_name, "utun7");
            entered_by_worker.notify_one();
            let (lock, changed) = &*gate;
            let _released = changed.wait_while(lock.lock().unwrap(), |released| !*released).unwrap();
            anyhow::bail!("initialization released by the test")
        });
        let service = Arc::new(DaemonService::new(Logger::new(None).unwrap(), factory));
        let connection_service = service.clone();
        let (shutdown, _receiver) = watch::channel(false);
        let connection_shutdown = shutdown.clone();
        let (mut client, stream) = tokio::io::duplex(4096);
        let connection = tokio::spawn(async move { connection_service.serve_connection(stream, connection_shutdown).await });
        read_frame::<ServerMessage>(&mut client).await.unwrap().unwrap();
        write_frame(&mut client, &ClientMessage { payload: Some(ClientPayload::Config(config("utun7"))) }).await.unwrap();
        tokio::time::timeout(Duration::from_secs(2), entered.notified()).await.unwrap();
        assert!(service.active_sessions.lock().await.contains("utun7"));
        if disconnect {
            drop(client);
        } else {
            shutdown.send(true).unwrap();
        }
        tokio::task::yield_now().await;
        assert!(!connection.is_finished(), "the connection abandoned its initialization worker");
        assert!(service.active_sessions.lock().await.contains("utun7"));
        drop(release);
        let _ = tokio::time::timeout(Duration::from_secs(2), connection).await.unwrap().unwrap();
        assert!(service.active_sessions.lock().await.is_empty());
    }
}

#[tokio::test]
async fn initialization_failures_preserve_codes_causes_and_release_the_reservation() {
    let factories: [(SessionFactory, &[&str]); 2] = [
        (
            Arc::new(|_| {
                Err(anyhow::Error::new(io::Error::new(io::ErrorKind::PermissionDenied, "native resource failure")).context("resource preparation"))
            }),
            &["resource preparation", "native resource failure"],
        ),
        (Arc::new(|_| panic!("injected initialization panic")), &["failed joining session initialization", "injected initialization panic"]),
    ];
    for (factory, causes) in factories {
        let service = Arc::new(DaemonService::new(Logger::new(None).unwrap(), factory));
        let error = request(&service, config("utun7")).await;
        assert_eq!(error.code, tonic::Code::FailedPrecondition.to_string());
        for cause in causes {
            assert!(error.message.contains(cause), "missing {cause:?} in {error:?}");
        }
        assert!(service.active_sessions.lock().await.is_empty());
    }
}

struct TestDevice {
    incoming: Mutex<mpsc::Receiver<io::Result<Vec<u8>>>>,
    outgoing: mpsc::Sender<Vec<u8>>,
}
impl PacketIo for TestDevice {
    fn recv<'a>(&'a self, bytes: &'a mut [u8]) -> BoxFuture<'a, io::Result<usize>> {
        Box::pin(async move {
            let packet = self.incoming.lock().await.recv().await.unwrap_or_else(|| Err(io::Error::other("device closed")))?;
            bytes[..packet.len()].copy_from_slice(&packet);
            Ok(packet.len())
        })
    }
    fn send<'a>(&'a self, bytes: &'a [u8]) -> BoxFuture<'a, io::Result<usize>> {
        Box::pin(async move {
            self.outgoing.send(bytes.to_vec()).await.map_err(io::Error::other)?;
            Ok(bytes.len())
        })
    }
}
type TestDeviceChannels = (TestDevice, mpsc::Sender<io::Result<Vec<u8>>>, mpsc::Receiver<Vec<u8>>);

fn test_device() -> TestDeviceChannels {
    let (incoming_tx, incoming) = mpsc::channel(8);
    let (outgoing, outgoing_rx) = mpsc::channel(8);
    (TestDevice { incoming: Mutex::new(incoming), outgoing }, incoming_tx, outgoing_rx)
}

#[tokio::test]
async fn forwards_both_directions_without_losing_a_partial_ipc_frame() {
    let (device, incoming, mut outgoing) = test_device();
    let (mut client, mut stream) = tokio::io::duplex(128);
    let task = tokio::spawn(async move { transfer(&mut stream, &device).await });
    let message = ClientMessage { payload: Some(ClientPayload::OutgoingPacket(Packet { data: vec![4, 5, 6] })) };
    let payload = prost::Message::encode_to_vec(&message);
    let mut frame = (payload.len() as u32).to_be_bytes().to_vec();
    frame.extend(payload);
    client.write_all(&frame[..2]).await.unwrap();
    incoming.send(Ok(vec![1, 2, 3])).await.unwrap();
    let response = read_frame::<ServerMessage>(&mut client).await.unwrap().unwrap();
    assert_eq!(response.payload, Some(ServerPayload::IncomingPacket(Packet { data: vec![1, 2, 3] })));
    client.write_all(&frame[2..]).await.unwrap();
    assert_eq!(outgoing.recv().await.unwrap(), [4, 5, 6]);
    drop(client);
    task.await.unwrap().unwrap();
}

#[tokio::test]
async fn ignores_empty_and_oversized_packets_and_accepts_the_maximum_size() {
    let (device, incoming, mut outgoing) = test_device();
    let (mut client, mut stream) = tokio::io::duplex(128);
    let task = tokio::spawn(async move { transfer(&mut stream, &device).await });
    for size in [0, 65536, 65535] {
        let packet = vec![7; size];
        incoming.send(Ok(packet.clone())).await.unwrap();
        write_frame(&mut client, &ClientMessage { payload: Some(ClientPayload::OutgoingPacket(Packet { data: packet })) }).await.unwrap();
    }
    assert_eq!(outgoing.recv().await.unwrap().len(), 65535);
    let response = read_frame::<ServerMessage>(&mut client).await.unwrap().unwrap();
    let Some(ServerPayload::IncomingPacket(packet)) = response.payload else { panic!("expected packet") };
    assert_eq!(packet.data.len(), 65535);
    assert!(outgoing.try_recv().is_err());
    drop(client);
    task.await.unwrap().unwrap();
}

#[tokio::test]
async fn disconnect_releases_a_backpressured_tun_to_ipc_writer() {
    let (device, incoming, _outgoing) = test_device();
    let (mut client, mut stream) = tokio::io::duplex(1);
    let task = tokio::spawn(async move { transfer(&mut stream, &device).await });
    incoming.send(Ok(vec![1; 65535])).await.unwrap();
    client.read_u8().await.unwrap(); // The packet write is still blocked.
    drop(client);
    assert!(tokio::time::timeout(Duration::from_secs(2), task).await.unwrap().is_ok());
}

#[tokio::test]
async fn rejects_reconfiguration_shutdown_and_malformed_frames_during_transfer() {
    for payload in [ClientPayload::Config(config("utun0")), ClientPayload::Shutdown(true)] {
        let (device, _incoming, _outgoing) = test_device();
        let (mut client, mut stream) = tokio::io::duplex(4096);
        let task = tokio::spawn(async move { transfer(&mut stream, &device).await });
        write_frame(&mut client, &ClientMessage { payload: Some(payload) }).await.unwrap();
        assert_eq!(task.await.unwrap().unwrap_err().code(), tonic::Code::InvalidArgument);
    }
    let (device, _incoming, _outgoing) = test_device();
    let (mut client, mut stream) = tokio::io::duplex(128);
    let task = tokio::spawn(async move { transfer(&mut stream, &device).await });
    client.write_all(&[0, 0, 0, 1, 255]).await.unwrap();
    assert_eq!(task.await.unwrap().unwrap_err().code(), tonic::Code::InvalidArgument);
}

#[tokio::test]
async fn propagates_device_errors_in_both_directions() {
    let (device, incoming, _outgoing) = test_device();
    let (_client, mut stream) = tokio::io::duplex(128);
    let task = tokio::spawn(async move { transfer(&mut stream, &device).await });
    incoming.send(Err(io::Error::other("read fixture"))).await.unwrap();
    assert!(task.await.unwrap().unwrap_err().message().contains("read fixture"));
    let (device, _incoming, outgoing) = test_device();
    let (mut client, mut stream) = tokio::io::duplex(128);
    drop(outgoing);
    let task = tokio::spawn(async move { transfer(&mut stream, &device).await });
    write_frame(&mut client, &ClientMessage { payload: Some(ClientPayload::OutgoingPacket(Packet { data: vec![1] })) }).await.unwrap();
    assert!(task.await.unwrap().unwrap_err().message().contains("failed writing TUN"));
}

#[tokio::test]
async fn buffers_a_burst_while_the_response_pipe_is_blocked() {
    let (device, incoming, _outgoing) = test_device();
    let (client, mut stream) = tokio::io::duplex(1);
    let task = tokio::spawn(async move { transfer(&mut stream, &device).await });
    tokio::time::timeout(Duration::from_secs(2), async {
        for _ in 0..65 {
            incoming.send(Ok(vec![1; 1024])).await.unwrap();
        }
    })
    .await
    .expect("the bounded response queue must absorb the burst");
    drop(client);
    tokio::time::timeout(Duration::from_secs(2), task).await.unwrap().unwrap().unwrap_err();
}
