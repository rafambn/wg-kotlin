#![cfg(target_os = "linux")]

use daemon::ipc::{read_frame, write_frame};
use daemon_proto::pb::{Cidr, ClientMessage, DnsConfig, Ip, Packet, ServerMessage, TunSessionConfig, client_message, ip, server_message};
use std::{
    fs,
    path::PathBuf,
    process::{Child, Command, Stdio},
    time::{SystemTime, UNIX_EPOCH},
};
use tokio::{
    net::{UdpSocket, UnixStream},
    time::{Duration, sleep, timeout},
};

struct Daemon {
    child: Child,
    endpoint: PathBuf,
    directory: PathBuf,
}
impl Drop for Daemon {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
        let _ = fs::remove_dir_all(&self.directory);
    }
}
impl Daemon {
    fn start(dns_script: Option<&str>) -> Self {
        let suffix = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let directory = PathBuf::from(format!("/tmp/wg-native-test-{suffix}"));
        fs::create_dir(&directory).unwrap();
        let endpoint = PathBuf::from(format!("/tmp/wg-kotlin-native-{suffix}/ipc.sock"));
        let pid = std::process::id();
        let stat = fs::read_to_string(format!("/proc/{pid}/stat")).unwrap();
        let started = stat.rsplit_once(')').unwrap().1.split_whitespace().nth(19).unwrap();
        let mut command = Command::new(env!("CARGO_BIN_EXE_wg-kotlin-daemon"));
        command.args(["--endpoint", endpoint.to_str().unwrap(), "--owner-pid", &pid.to_string(), "--owner-start-time", started]);
        if let Some(script) = dns_script {
            use std::os::unix::fs::PermissionsExt;
            let wrapper = directory.join("resolvectl");
            fs::write(&wrapper, format!("#!/bin/sh\n{script}\n")).unwrap();
            fs::set_permissions(wrapper, fs::Permissions::from_mode(0o700)).unwrap();
            command.env("PATH", format!("{}:{}", directory.display(), std::env::var("PATH").unwrap()));
            command.env("WG_KOTLIN_DNS_FIXTURE_MARKER", directory.join("dns-started"));
        }
        let child = command.stdout(Stdio::piped()).stderr(Stdio::piped()).spawn().unwrap();
        Self { child, endpoint, directory }
    }
    async fn connect(&self) -> UnixStream {
        timeout(Duration::from_secs(3), async {
            loop {
                if let Ok(mut stream) = UnixStream::connect(&self.endpoint).await {
                    let ready = read_frame::<ServerMessage>(&mut stream).await.unwrap().unwrap();
                    assert!(matches!(ready.payload, Some(server_message::Payload::Ready(_))));
                    break stream;
                }
                sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .unwrap()
    }
    async fn open(&self, config: TunSessionConfig) -> UnixStream {
        timeout(Duration::from_secs(3), async {
            loop {
                let mut stream = self.connect().await;
                write_frame(&mut stream, &ClientMessage { payload: Some(client_message::Payload::Config(config.clone())) }).await.unwrap();
                match read_frame::<ServerMessage>(&mut stream).await.unwrap().unwrap().payload {
                    Some(server_message::Payload::Started(started)) => {
                        assert_eq!(started.interface_name, config.interface_name);
                        break stream;
                    }
                    Some(server_message::Payload::Error(error)) if error.message.contains("Session already active") => {
                        sleep(Duration::from_millis(10)).await
                    }
                    other => panic!("unexpected session response: {other:?}"),
                }
            }
        })
        .await
        .unwrap()
    }
    async fn stop(&mut self) {
        let mut stream = self.connect().await;
        write_frame(&mut stream, &ClientMessage { payload: Some(client_message::Payload::Shutdown(true)) }).await.unwrap();
        self.wait_for_successful_exit().await;
    }
    async fn wait_for_successful_exit(&mut self) {
        timeout(Duration::from_secs(5), async {
            loop {
                if let Some(status) = self.child.try_wait().unwrap() {
                    assert!(status.success());
                    break;
                }
                sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .unwrap();
    }
}
fn ip(bytes: &[u8]) -> Ip {
    Ip { value: Some(ip::Value::V4(bytes.to_vec())) }
}
fn config(index: u8) -> TunSessionConfig {
    TunSessionConfig {
        interface_name: format!("utun{index}"),
        mtu: 1400,
        addresses: vec![Cidr { ip: Some(ip(&[198, 18, index, 1])), prefix: 32 }],
        peer_allowed_ips: vec![Cidr { ip: Some(ip(&[198, 19, index, 0])), prefix: 24 }],
        peer_endpoints: vec![ip(&[203, 0, 113, index])],
        dns: Some(DnsConfig { servers: vec![ip(&[9, 9, 9, 9])], search_domains: vec!["wg-ipc.test".into()] }),
    }
}

#[tokio::test]
#[ignore = "isolated Linux netns, NET_ADMIN, /dev/net/tun and systemd-resolved; run serially"]
async fn unix_signals_wait_for_session_cleanup_before_exit() {
    for signal in [libc::SIGTERM, libc::SIGINT] {
        let mut daemon = Daemon::start(None);
        let first = daemon.open(config(0)).await;
        let second = daemon.open(config(1)).await;
        // Do not race an unpolled signal future with the kernel's default action.
        timeout(Duration::from_secs(3), async {
            loop {
                let status = fs::read_to_string(format!("/proc/{}/status", daemon.child.id())).unwrap();
                let caught = status.lines().find_map(|line| line.strip_prefix("SigCgt:")).unwrap().trim();
                if u64::from_str_radix(caught, 16).unwrap() & (1 << (signal - 1)) != 0 {
                    break;
                }
                sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .unwrap();
        assert_eq!(unsafe { libc::kill(daemon.child.id() as i32, signal) }, 0);
        daemon.wait_for_successful_exit().await;
        wait_removed(0).await;
        wait_removed(1).await;
        assert!(!daemon.endpoint.exists());
        assert!(!daemon.endpoint.parent().unwrap().exists());
        drop((first, second));
    }
}

async fn wait_removed(index: u8) {
    timeout(Duration::from_secs(3), async {
        while PathBuf::from(format!("/sys/class/net/utun{index}")).exists() {
            sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap();
    let host = format!("203.0.113.{index}/32");
    let output = Command::new("ip").args(["route", "show", "exact", &host]).output().unwrap();
    assert!(output.stdout.is_empty(), "{}", String::from_utf8_lossy(&output.stdout));
}
fn reply(request: &[u8], payload: &[u8]) -> Vec<u8> {
    let header_len = usize::from(request[0] & 15) * 4;
    let mut packet = request[..header_len + 8].to_vec();
    packet.extend(payload);
    packet[12..16].copy_from_slice(&request[16..20]);
    packet[16..20].copy_from_slice(&request[12..16]);
    packet[header_len..header_len + 2].copy_from_slice(&request[header_len + 2..header_len + 4]);
    packet[header_len + 2..header_len + 4].copy_from_slice(&request[header_len..header_len + 2]);
    let total = packet.len() as u16;
    packet[2..4].copy_from_slice(&total.to_be_bytes());
    packet[header_len + 4..header_len + 6].copy_from_slice(&((8 + payload.len()) as u16).to_be_bytes());
    packet[header_len + 6..header_len + 8].fill(0);
    packet[10..12].fill(0);
    let mut sum: u32 = packet[..header_len].as_chunks::<2>().0.iter().map(|b| u32::from(u16::from_be_bytes(*b))).sum();
    while sum > 65535 {
        sum = (sum & 65535) + (sum >> 16);
    }
    packet[10..12].copy_from_slice(&(!(sum as u16)).to_be_bytes());
    packet
}

#[tokio::test]
#[ignore = "isolated Linux container with NET_ADMIN and /dev/net/tun; run serially"]
async fn mtu_zero_uses_the_platform_default_and_explicit_values_reach_the_kernel() {
    for index in [87, 88] {
        assert!(!PathBuf::from(format!("/sys/class/net/utun{index}")).exists());
    }
    let control = tokio::task::spawn_blocking(|| {
        tun_rs::DeviceBuilder::new().name("utun88").ipv4(std::net::Ipv4Addr::new(198, 18, 88, 1), 32, None).build_async()
    })
    .await
    .unwrap()
    .unwrap();
    let default_mtu = control.mtu().unwrap();
    drop(control);
    wait_removed(88).await;

    let mut daemon = Daemon::start(Some("exit 0"));
    for mtu in [0, 576, 1400] {
        let mut requested = config(87);
        requested.mtu = mtu;
        requested.peer_allowed_ips.clear();
        requested.peer_endpoints.clear();
        requested.dns = None;
        let stream = daemon.open(requested).await;
        let actual = fs::read_to_string("/sys/class/net/utun87/mtu").unwrap().trim().parse::<u16>().unwrap();
        assert_eq!(actual, if mtu == 0 { default_mtu } else { mtu as u16 });
        drop(stream);
        wait_removed(87).await;
    }
    daemon.stop().await;
}

#[tokio::test]
#[ignore = "isolated Linux container with NET_ADMIN, /dev/net/tun and systemd-resolved; run serially"]
async fn sessions_exchange_real_packets_reuse_interfaces_and_shutdown_independently() {
    let mut daemon = Daemon::start(None);
    let mut first = daemon.open(config(80)).await;
    let second = daemon.open(config(81)).await;
    let socket = UdpSocket::bind("0.0.0.0:0").await.unwrap();
    socket.connect("198.19.80.1:45000").await.unwrap();
    socket.send(b"ipc-request").await.unwrap();
    let packet = timeout(Duration::from_secs(2), async {
        loop {
            let response = read_frame::<ServerMessage>(&mut first).await.unwrap().unwrap();
            if let Some(server_message::Payload::IncomingPacket(packet)) = response.payload
                && packet.data.len() >= 28
                && packet.data[0] >> 4 == 4
                && packet.data[9] == 17
                && packet.data.ends_with(b"ipc-request")
            {
                break packet.data;
            }
        }
    })
    .await
    .unwrap();
    write_frame(
        &mut first,
        &ClientMessage { payload: Some(client_message::Payload::OutgoingPacket(Packet { data: reply(&packet, b"ipc-response") })) },
    )
    .await
    .unwrap();
    let mut bytes = [0; 128];
    let count = timeout(Duration::from_secs(2), socket.recv(&mut bytes)).await.unwrap().unwrap();
    assert_eq!(&bytes[..count], b"ipc-response");
    drop(first);
    wait_removed(80).await;
    assert!(PathBuf::from("/sys/class/net/utun81").exists());
    let first = daemon.open(config(80)).await;
    // Saturate the process's unread stdout with ordinary probe logs.
    for _ in 0..400 {
        drop(daemon.connect().await);
    }
    daemon.stop().await;
    drop((first, second));
    wait_removed(80).await;
    wait_removed(81).await;
}

#[tokio::test]
#[ignore = "isolated Linux container with NET_ADMIN, /dev/net/tun and systemd-resolved; run serially"]
async fn disconnect_during_blocking_setup_still_cleans_the_partial_configuration() {
    let mut daemon = Daemon::start(Some(
        r#"
if [ "$1" = revert ] && [ ! -e "$WG_KOTLIN_DNS_FIXTURE_MARKER" ]; then
    touch "$WG_KOTLIN_DNS_FIXTURE_MARKER"
    sleep 0.3
fi
exec /usr/bin/resolvectl "$@"
"#,
    ));
    let mut stream = daemon.connect().await;
    write_frame(&mut stream, &ClientMessage { payload: Some(client_message::Payload::Config(config(82))) }).await.unwrap();
    timeout(Duration::from_secs(2), async {
        while !daemon.directory.join("dns-started").exists() {
            sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap();
    assert!(PathBuf::from("/sys/class/net/utun82").exists());
    drop(stream);
    wait_removed(82).await;
    let session = daemon.open(config(82)).await;
    daemon.stop().await;
    drop(session);
    wait_removed(82).await;
}

#[tokio::test]
#[ignore = "isolated Linux container with NET_ADMIN, /dev/net/tun and systemd-resolved; run serially"]
async fn setup_and_cleanup_failures_reach_the_client_with_both_causes_and_release_the_interface() {
    let mut daemon = Daemon::start(Some(
        r#"
if [ "$1" = dns ]; then
    echo setup-cause >&2
    exit 7
fi
if [ "$1" = revert ] && [ -e "$WG_KOTLIN_DNS_FIXTURE_MARKER" ]; then
    echo cleanup-cause >&2
    exit 8
fi
touch "$WG_KOTLIN_DNS_FIXTURE_MARKER"
exec /usr/bin/resolvectl "$@"
"#,
    ));
    for _ in 0..2 {
        // The initial revert must succeed so this attempt reaches the DNS setter.
        let _ = fs::remove_file(daemon.directory.join("dns-started"));
        let mut stream = daemon.connect().await;
        write_frame(&mut stream, &ClientMessage { payload: Some(client_message::Payload::Config(config(83))) }).await.unwrap();
        let response = timeout(Duration::from_secs(3), read_frame::<ServerMessage>(&mut stream)).await.unwrap().unwrap().unwrap();
        let Some(server_message::Payload::Error(error)) = response.payload else { panic!("expected setup failure") };
        assert_eq!(error.code, tonic::Code::FailedPrecondition.to_string());
        for cause in ["setup-cause", "cleanup-cause", "Some(7)", "Some(8)"] {
            assert!(error.message.contains(cause), "{error:?}");
        }
        assert!(read_frame::<ServerMessage>(&mut stream).await.unwrap().is_none());
        wait_removed(83).await;
    }
    daemon.stop().await;
}

#[tokio::test]
#[ignore = "isolated Linux container with NET_ADMIN, /dev/net/tun and systemd-resolved; run serially"]
async fn cleanup_failure_keeps_its_internal_code_after_a_transfer_error() {
    let mut daemon = Daemon::start(Some(
        r#"
if [ "$1" = revert ] && [ -e "$WG_KOTLIN_DNS_FIXTURE_MARKER" ]; then
    /usr/bin/resolvectl "$@" || exit $?
    echo cleanup-cause >&2
    exit 8
fi
touch "$WG_KOTLIN_DNS_FIXTURE_MARKER"
exec /usr/bin/resolvectl "$@"
"#,
    ));
    let mut stream = daemon.open(config(84)).await;
    write_frame(&mut stream, &ClientMessage { payload: Some(client_message::Payload::Config(config(84))) }).await.unwrap();
    for code in [tonic::Code::InvalidArgument, tonic::Code::Internal] {
        let error = timeout(Duration::from_secs(3), async {
            loop {
                match read_frame::<ServerMessage>(&mut stream).await.unwrap().unwrap().payload {
                    Some(server_message::Payload::Error(error)) => break error,
                    Some(server_message::Payload::IncomingPacket(_)) => {} // The kernel can send packets as soon as the interface is up.
                    other => panic!("expected session error, got {other:?}"),
                }
            }
        })
        .await
        .unwrap();
        assert_eq!(error.code, code.to_string());
        if code == tonic::Code::Internal {
            assert!(error.message.contains("cleanup-cause") && error.message.contains("Some(8)"), "{error:?}");
        }
    }
    assert!(read_frame::<ServerMessage>(&mut stream).await.unwrap().is_none());
    wait_removed(84).await;
    daemon.stop().await;
}

#[tokio::test]
#[ignore = "isolated Linux container with NET_ADMIN, /dev/net/tun and systemd-resolved; run serially"]
async fn a_missing_vpn_route_does_not_skip_endpoint_dns_or_device_cleanup() {
    let mut daemon = Daemon::start(Some(
        r#"
if [ "$1" = revert ]; then echo revert >> "$WG_KOTLIN_DNS_FIXTURE_MARKER"; fi
exec /usr/bin/resolvectl "$@"
"#,
    ));
    let mut stream = daemon.open(config(85)).await;
    let output = Command::new("ip").args(["route", "del", "198.19.85.0/24", "dev", "utun85"]).output().unwrap();
    assert!(output.status.success(), "{}", String::from_utf8_lossy(&output.stderr));
    write_frame(&mut stream, &ClientMessage { payload: Some(client_message::Payload::Config(config(85))) }).await.unwrap();
    for code in [tonic::Code::InvalidArgument] {
        let error = timeout(Duration::from_secs(3), async {
            loop {
                match read_frame::<ServerMessage>(&mut stream).await.unwrap().unwrap().payload {
                    Some(server_message::Payload::Error(error)) => break error,
                    Some(server_message::Payload::IncomingPacket(_)) => {}
                    other => panic!("expected session error, got {other:?}"),
                }
            }
        })
        .await
        .unwrap();
        assert_eq!(error.code, code.to_string());
    }
    assert!(read_frame::<ServerMessage>(&mut stream).await.unwrap().is_none());
    wait_removed(85).await;
    let dns_calls = fs::read_to_string(daemon.directory.join("dns-started")).unwrap();
    assert_eq!(dns_calls.lines().count(), 2, "initial reset and cleanup must both run");
    drop(daemon.open(config(85)).await);
    wait_removed(85).await;
    daemon.stop().await;
}

#[tokio::test]
#[ignore = "isolated Linux container with NET_ADMIN, /dev/net/tun and systemd-resolved; run serially"]
async fn failed_response_writer_cleans_the_session_without_client_eof() {
    use std::os::fd::AsRawFd;

    let mut daemon = Daemon::start(None);
    let stream = daemon.open(config(86)).await;
    // Stop receiving without closing the write side. The daemon's frame reader
    // stays pending, so the failed response writer must wake the session itself.
    assert_eq!(unsafe { libc::shutdown(stream.as_raw_fd(), libc::SHUT_RD) }, 0);
    let socket = UdpSocket::bind("0.0.0.0:0").await.unwrap();
    socket.connect("198.19.86.1:45000").await.unwrap();
    socket.send(b"response-write-failure").await.unwrap();
    wait_removed(86).await;
    let reopened = daemon.open(config(86)).await;
    daemon.stop().await;
    drop((stream, reopened));
    wait_removed(86).await;
}

fn endpoint_decision(endpoint: &str) -> serde_json::Value {
    let output = Command::new("ip").args(["-j", "route", "get", endpoint, "ipproto", "17"]).output().unwrap();
    assert!(output.status.success(), "{}", String::from_utf8_lossy(&output.stderr));
    serde_json::from_slice::<serde_json::Value>(&output.stdout).unwrap()[0].clone()
}

fn assert_same_endpoint_path(endpoint: &str, original: &serde_json::Value) {
    let selected = endpoint_decision(endpoint);
    for field in ["dev", "gateway", "table"] {
        assert_eq!(selected[field], original[field], "endpoint changed path: {selected}");
    }
}

fn assert_no_endpoint_pin(endpoint: &str) {
    let output = Command::new("ip").args(["route", "show", "exact", &format!("{endpoint}/32")]).output().unwrap();
    assert!(output.status.success());
    assert!(output.stdout.is_empty(), "{}", String::from_utf8_lossy(&output.stdout));
}

#[tokio::test]
#[ignore = "isolated Linux container with NET_ADMIN, /dev/net/tun and systemd-resolved; run serially"]
async fn shared_endpoint_pin_survives_all_three_session_cleanup_orders() {
    let endpoint = "203.0.113.77";
    assert_no_endpoint_pin(endpoint);
    let original = endpoint_decision(endpoint);
    for order in [[0, 1, 2], [0, 2, 1], [1, 0, 2], [1, 2, 0], [2, 0, 1], [2, 1, 0]] {
        let mut daemon = Daemon::start(None);
        let mut sessions = Vec::new();
        for index in 61..=63 {
            let mut requested = config(index);
            requested.peer_endpoints = vec![ip(&[203, 0, 113, 77])];
            // One VPN covers the endpoint, making premature pin deletion observable.
            if index == 62 {
                requested.peer_allowed_ips = vec![Cidr { ip: Some(ip(&[203, 0, 113, 0])), prefix: 24 }];
            }
            sessions.push(Some(daemon.open(requested).await));
            assert_same_endpoint_path(endpoint, &original);
        }
        for (step, index) in order.into_iter().enumerate() {
            drop(sessions[index].take());
            wait_removed(61 + index as u8).await;
            if step != 2 {
                assert_same_endpoint_path(endpoint, &original);
            }
        }
        daemon.stop().await;
        assert_no_endpoint_pin(endpoint);
    }
}

#[tokio::test]
#[ignore = "isolated Linux container with NET_ADMIN, /dev/net/tun and systemd-resolved; run serially"]
async fn failed_shared_endpoint_setup_keeps_the_other_sessions_pin() {
    let endpoint = "203.0.113.77";
    assert_no_endpoint_pin(endpoint);
    let original = endpoint_decision(endpoint);
    let mut daemon = Daemon::start(Some(
        "if [ \"$1\" = dns ] && [ \"$2\" = utun65 ]; then echo forced-DNS-failure >&2; exit 7; fi\nexec /usr/bin/resolvectl \"$@\"",
    ));
    let mut first = config(64);
    first.peer_endpoints = vec![ip(&[203, 0, 113, 77])];
    first.peer_allowed_ips = vec![Cidr { ip: Some(ip(&[203, 0, 113, 0])), prefix: 24 }];
    let active = daemon.open(first).await;
    let mut second = config(65);
    second.peer_endpoints = vec![ip(&[203, 0, 113, 77])];
    for _ in 0..2 {
        let mut failing = daemon.connect().await;
        write_frame(&mut failing, &ClientMessage { payload: Some(client_message::Payload::Config(second.clone())) }).await.unwrap();
        let response = timeout(Duration::from_secs(3), read_frame::<ServerMessage>(&mut failing)).await.unwrap().unwrap().unwrap();
        let Some(server_message::Payload::Error(error)) = response.payload else { panic!("expected setup failure") };
        assert_eq!(error.code, tonic::Code::FailedPrecondition.to_string());
        assert!(error.message.contains("forced-DNS-failure") && error.message.contains("Some(7)"), "{error:?}");
        wait_removed(65).await;
        assert!(PathBuf::from("/sys/class/net/utun64").exists());
        assert_same_endpoint_path(endpoint, &original);
    }
    drop(active);
    wait_removed(64).await;
    daemon.stop().await;
    assert_no_endpoint_pin(endpoint);
}

#[tokio::test]
#[ignore = "isolated Linux container with NET_ADMIN, /dev/net/tun and systemd-resolved; run serially"]
async fn slow_dns_setup_and_cleanup_do_not_hold_other_sessions_routes() {
    struct Release(PathBuf);
    impl Drop for Release {
        fn drop(&mut self) {
            let _ = fs::write(&self.0, "release");
        }
    }
    for phase in ["setup", "cleanup"] {
        let script = r#"
if [ "$2" = utun67 ]; then
    if [ "$1" = domain ]; then touch "$WG_KOTLIN_DNS_FIXTURE_MARKER.armed"; fi
    if { [ "BLOCK_PHASE" = setup ] && [ "$1" = domain ]; } ||
       { [ "BLOCK_PHASE" = cleanup ] && [ "$1" = revert ] && [ -e "$WG_KOTLIN_DNS_FIXTURE_MARKER.armed" ]; }; then
        touch "$WG_KOTLIN_DNS_FIXTURE_MARKER"
        while [ ! -e "$WG_KOTLIN_DNS_FIXTURE_MARKER.release" ]; do sleep 0.01; done
    fi
fi
exec /usr/bin/resolvectl "$@"
"#
        .replace("BLOCK_PHASE", phase);
        let mut daemon = Daemon::start(Some(&script));
        let release = Release(daemon.directory.join("dns-started.release"));
        let first = daemon.open(config(66)).await;
        let mut second = daemon.connect().await;
        write_frame(&mut second, &ClientMessage { payload: Some(client_message::Payload::Config(config(67))) }).await.unwrap();
        if phase == "cleanup" {
            let started = read_frame::<ServerMessage>(&mut second).await.unwrap().unwrap();
            assert!(matches!(started.payload, Some(server_message::Payload::Started(_))));
            drop(second);
        }
        timeout(Duration::from_secs(3), async {
            while !daemon.directory.join("dns-started").exists() {
                sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .unwrap();
        drop(first);
        // DNS remains blocked until the first session has actually cleaned its routes.
        wait_removed(66).await;
        drop(release);
        if phase == "setup" {
            // Shutdown must also await the pending setup.
            daemon.stop().await;
        } else {
            wait_removed(67).await;
            daemon.stop().await;
        }
        wait_removed(67).await;
    }
}

#[tokio::test]
#[ignore = "isolated Linux container with NET_ADMIN, IPv6, /dev/net/tun and iproute2; run serially"]
async fn original_talpid_rejects_ipv6_multipath_and_shares_single_path_endpoints() {
    fn ip_command(args: &[&str]) -> Vec<u8> {
        let output = Command::new("ip").args(args).output().unwrap();
        assert!(output.status.success(), "ip {args:?}: {}", String::from_utf8_lossy(&output.stderr));
        output.stdout
    }
    struct Link(&'static str);
    impl Drop for Link {
        fn drop(&mut self) {
            let _ = Command::new("ip").args(["link", "del", self.0]).output();
        }
    }
    ip_command(&["link", "add", "wgshare6a", "type", "dummy"]);
    let _first_link = Link("wgshare6a");
    ip_command(&["link", "add", "wgshare6b", "type", "dummy"]);
    let _second_link = Link("wgshare6b");
    for (name, address) in [("wgshare6a", "2001:db8:77::1/64"), ("wgshare6b", "2001:db8:79::1/64")] {
        ip_command(&["link", "set", name, "up"]);
        ip_command(&["-6", "addr", "add", address, "dev", name, "nodad"]);
    }
    ip_command(&[
        "-6",
        "route",
        "add",
        "2001:db8:78::/64",
        "metric",
        "1024",
        "nexthop",
        "via",
        "2001:db8:77::2",
        "dev",
        "wgshare6a",
        "weight",
        "1",
        "nexthop",
        "via",
        "2001:db8:79::2",
        "dev",
        "wgshare6b",
        "weight",
        "2",
    ]);
    let endpoint: std::net::Ipv6Addr = "2001:db8:78::77".parse().unwrap();
    let proto_ip = |address: &str| Ip { value: Some(ip::Value::V6(address.parse::<std::net::Ipv6Addr>().unwrap().octets().to_vec())) };
    let host =
        || -> serde_json::Value { serde_json::from_slice(&ip_command(&["-6", "-j", "route", "show", "exact", "2001:db8:78::77/128"])).unwrap() };
    let mut daemon = Daemon::start(Some("exit 0"));
    let mut rejected = config(68);
    rejected.peer_endpoints = vec![Ip { value: Some(ip::Value::V6(endpoint.octets().to_vec())) }];
    rejected.dns = None;
    let mut stream = daemon.connect().await;
    write_frame(&mut stream, &ClientMessage { payload: Some(client_message::Payload::Config(rejected)) }).await.unwrap();
    let response = read_frame::<ServerMessage>(&mut stream).await.unwrap().unwrap();
    let Some(server_message::Payload::Error(error)) = response.payload else { panic!("expected upstream multipath lookup failure") };
    assert_eq!(error.code, tonic::Code::FailedPrecondition.to_string());
    assert!(error.message.contains("Route without a valid node"), "{error:?}");
    assert!(!PathBuf::from("/sys/class/net/utun68").exists(), "failed lookup must happen before TUN creation");
    assert!(host().as_array().unwrap().is_empty());
    daemon.stop().await;

    // Keep the original upstream behavior and verify the supported single-path case.
    ip_command(&["-6", "route", "replace", "2001:db8:78::/64", "via", "2001:db8:77::2", "dev", "wgshare6a", "metric", "1024"]);
    for first_to_close in [0, 1] {
        assert!(host().as_array().unwrap().is_empty());
        let mut daemon = Daemon::start(Some("exit 0"));
        let mut sessions = Vec::new();
        for index in 68..=69 {
            let mut requested = config(index);
            requested.addresses = vec![Cidr { ip: Some(proto_ip(&format!("2001:db8:{index}::1"))), prefix: 128 }];
            requested.peer_endpoints = vec![Ip { value: Some(ip::Value::V6(endpoint.octets().to_vec())) }];
            requested.peer_allowed_ips = vec![Cidr {
                ip: Some(proto_ip(if index == 68 { "2001:db8:78::" } else { "2001:db8:80::" })),
                prefix: if index == 68 { 65 } else { 64 },
            }];
            requested.dns = None;
            sessions.push(Some(daemon.open(requested).await));
        }
        let installed = host();
        assert_eq!(installed[0]["metric"], 1024);
        assert_eq!(installed[0]["dev"], "wgshare6a", "{installed}");
        assert!(installed[0].get("nexthops").is_none(), "{installed}");
        drop(sessions[first_to_close].take());
        wait_removed(68 + first_to_close as u8).await;
        assert_eq!(host(), installed);
        drop(sessions[1 - first_to_close].take());
        wait_removed(69 - first_to_close as u8).await;
        assert!(host().as_array().unwrap().is_empty());
        daemon.stop().await;
    }
}

#[tokio::test]
#[ignore = "isolated Linux container with NET_ADMIN, /dev/net/tun and systemd-resolved; run serially"]
async fn conflicting_prefix_is_rejected_and_the_active_session_keeps_its_routes() {
    let mut daemon = Daemon::start(None);
    let first_config = config(72);
    let first = daemon.open(first_config.clone()).await;
    let mut rejected_config = config(73);
    rejected_config.peer_allowed_ips = first_config.peer_allowed_ips;
    let mut rejected = daemon.connect().await;
    write_frame(&mut rejected, &ClientMessage { payload: Some(client_message::Payload::Config(rejected_config)) }).await.unwrap();
    let message = timeout(Duration::from_secs(3), read_frame::<ServerMessage>(&mut rejected)).await.unwrap().unwrap().unwrap();
    let Some(server_message::Payload::Error(error)) = message.payload else { panic!("expected a route conflict") };
    assert_eq!(error.code, tonic::Code::FailedPrecondition.to_string());
    assert!(error.message.contains("already owned"), "{error:?}");
    wait_removed(73).await;
    let output = Command::new("ip").args(["-j", "route", "show", "exact", "198.19.72.0/24"]).output().unwrap();
    let routes: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(routes[0]["dev"], "utun72");
    drop(first);
    wait_removed(72).await;
    daemon.stop().await;
}

#[tokio::test]
#[ignore = "isolated Linux container with NET_ADMIN, /dev/net/tun and iproute2; run serially"]
async fn a_replaced_route_is_not_restored_after_session_cleanup() {
    fn ip_command(args: &[&str]) -> Vec<u8> {
        let output = Command::new("ip").args(args).output().unwrap();
        assert!(output.status.success(), "ip {args:?}: {}", String::from_utf8_lossy(&output.stderr));
        output.stdout
    }
    struct Link;
    impl Drop for Link {
        fn drop(&mut self) {
            let _ = Command::new("ip").args(["link", "del", "wgprevious"]).output();
        }
    }
    ip_command(&["link", "add", "wgprevious", "type", "dummy"]);
    let _previous = Link;
    ip_command(&["link", "set", "wgprevious", "up"]);
    ip_command(&["route", "add", "198.19.74.0/24", "dev", "wgprevious"]);
    let mut daemon = Daemon::start(None);
    let session = daemon.open(config(74)).await;
    let routes: serde_json::Value = serde_json::from_slice(&ip_command(&["-j", "route", "show", "exact", "198.19.74.0/24"])).unwrap();
    assert_eq!(routes[0]["dev"], "utun74");
    drop(session);
    wait_removed(74).await;
    assert!(ip_command(&["route", "show", "exact", "198.19.74.0/24"]).is_empty(), "replaced route was restored");
    daemon.stop().await;
}
