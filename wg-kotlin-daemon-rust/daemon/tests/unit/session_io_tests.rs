use super::*;
use daemon_proto::pb::{Cidr, DnsConfig, Ip, TunSessionConfig, ip};
use tokio::{
    net::UdpSocket,
    time::{Duration, timeout},
};

fn proto_ip(bytes: &[u8]) -> Ip {
    Ip { value: Some(ip::Value::V4(bytes.to_vec())) }
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
    packet[header_len + 6..header_len + 8].fill(0); // IPv4 permits a zero UDP checksum.
    packet[10..12].fill(0);
    let mut sum: u32 = packet[..header_len].as_chunks::<2>().0.iter().map(|b| u32::from(u16::from_be_bytes(*b))).sum();
    while sum > 65535 {
        sum = (sum & 65535) + (sum >> 16);
    }
    packet[10..12].copy_from_slice(&(!(sum as u16)).to_be_bytes());
    packet
}

#[cfg(target_os = "linux")]
#[tokio::test]
#[ignore = "isolated Linux container with NET_ADMIN, /dev/net/tun and systemd-resolved"]
async fn native_async_tun_exchanges_packets_and_cleans_routes_and_dns() {
    let requested = TunSessionConfig {
        interface_name: "utun77".into(),
        mtu: 1400,
        addresses: vec![Cidr { ip: Some(proto_ip(&[198, 18, 0, 1])), prefix: 32 }],
        peer_allowed_ips: vec![Cidr { ip: Some(proto_ip(&[198, 19, 0, 0])), prefix: 24 }],
        peer_endpoints: vec![proto_ip(&[203, 0, 113, 7])],
        dns: Some(DnsConfig { servers: vec![proto_ip(&[9, 9, 9, 9])], search_domains: vec!["wg-session.test".into()] }),
    };
    let config = crate::validation::validate_config(&requested).unwrap();
    let session = tokio::task::spawn_blocking(move || factory().and_then(|create| create(&config))).await.unwrap().unwrap();
    assert_eq!(session.interface_name, "utun77");
    assert_eq!(session.device.mtu().unwrap(), 1400);
    let dns = tokio::task::spawn_blocking(|| std::process::Command::new("resolvectl").args(["status", "utun77"]).output().unwrap()).await.unwrap();
    assert!(dns.status.success());
    let dns = String::from_utf8(dns.stdout).unwrap();
    assert!(dns.contains("9.9.9.9") && dns.contains("~wg-session.test"), "{dns}");
    exchange_packets(&session).await;
    tokio::task::spawn_blocking(move || session.close()).await.unwrap().unwrap();
    assert!(!std::path::Path::new("/sys/class/net/utun77").exists());
    for route in ["198.19.0.0/24", "203.0.113.7/32"] {
        let output = std::process::Command::new("ip").args(["route", "show", "exact", route]).output().unwrap();
        assert!(output.status.success());
        assert!(output.stdout.is_empty(), "{route}: {}", String::from_utf8_lossy(&output.stdout));
    }
}

async fn exchange_packets(session: &TunSession) {
    let socket = UdpSocket::bind("0.0.0.0:0").await.unwrap();
    socket.connect("198.19.0.1:45000").await.unwrap();
    socket.send(b"request").await.unwrap();
    let mut packet = vec![0; 65536];
    let count = timeout(Duration::from_secs(2), async {
        loop {
            let count = session.device.recv(&mut packet).await.unwrap();
            // Link creation also causes IPv6 discovery traffic in this namespace.
            if count >= 28 && packet[0] >> 4 == 4 && packet[9] == 17 && packet[..count].ends_with(b"request") {
                break count;
            }
        }
    })
    .await
    .unwrap();
    assert_eq!(packet[0] >> 4, 4);
    assert!(packet[..count].ends_with(b"request"));
    session.device.send(&reply(&packet[..count], b"response")).await.unwrap();
    let count = timeout(Duration::from_secs(2), socket.recv(&mut packet)).await.unwrap().unwrap();
    assert_eq!(&packet[..count], b"response");
}

#[cfg(target_os = "macos")]
fn unused_macos_test_interface() -> String {
    // Darwin limits the interface unit to SHRT_MAX, so a PID is not a valid unit allocator.
    (64..256)
        .map(|index| format!("utun{index}"))
        .find(|name| unsafe { libc::if_nametoindex(std::ffi::CString::new(name.as_str()).unwrap().as_ptr()) } == 0)
        .expect("no unused test interface in utun64..utun255")
}

#[cfg(target_os = "macos")]
fn diagnose_macos_device_creation() {
    for configured in [false, true] {
        let name = unused_macos_test_interface();
        let mut builder = DeviceBuilder::new().name(name.clone());
        if configured {
            builder = builder.mtu(1400).ipv4(std::net::Ipv4Addr::new(198, 18, 0, 1), 32, None);
        }
        match builder.build_sync() {
            Err(error) => eprintln!("creation diagnostic name={name} configured={configured} sync_error={error}"),
            Ok(device) => {
                eprintln!("creation diagnostic name={name} configured={configured} sync=ok");
                match AsyncDevice::new(device) {
                    Ok(_) => eprintln!("creation diagnostic name={name} async=ok"),
                    Err(error) => eprintln!("creation diagnostic name={name} async_error={error}"),
                }
            }
        }
    }
}

#[cfg(target_os = "macos")]
#[tokio::test]
#[ignore = "root on macOS; temporarily creates a TUN, benchmark routes and a a resolver for a .test domain"]
async fn native_macos_tun_exchanges_packets_after_cancelling_an_idle_read_and_cleans_up() {
    use std::{ffi::CString, net::IpAddr, time::Instant};
    assert_eq!(unsafe { libc::geteuid() }, 0, "run this ignored test as root");
    let name = unused_macos_test_interface();
    let c_name = CString::new(name.as_str()).unwrap();
    assert_eq!(unsafe { libc::if_nametoindex(c_name.as_ptr()) }, 0, "test interface already exists");
    eprintln!("native TUN test interface={name}");
    let reserved: [(IpAddr, u8); 3] =
        [("198.18.0.1".parse().unwrap(), 32), ("198.19.0.0".parse().unwrap(), 24), ("203.0.113.7".parse().unwrap(), 32)];
    let matching_routes = move || {
        route_manager::RouteManager::new()
            .unwrap()
            .list()
            .unwrap()
            .into_iter()
            .filter(|route| reserved.contains(&(route.network(), route.prefix())))
            .collect::<Vec<_>>()
    };
    assert!(matching_routes().is_empty(), "benchmark routes are already in use; refusing to replace them");
    let config = crate::validation::validate_config(&TunSessionConfig {
        interface_name: name.clone(),
        mtu: 1400,
        addresses: vec![Cidr { ip: Some(proto_ip(&[198, 18, 0, 1])), prefix: 32 }],
        peer_allowed_ips: vec![Cidr { ip: Some(proto_ip(&[198, 19, 0, 0])), prefix: 24 }],
        peer_endpoints: vec![proto_ip(&[203, 0, 113, 7])],
        dns: Some(DnsConfig { servers: vec![proto_ip(&[9, 9, 9, 9])], search_domains: vec![format!("wg-session-{}.test", std::process::id())] }),
    })
    .unwrap();
    struct Cleanup(Option<TunSession>);
    impl Drop for Cleanup {
        fn drop(&mut self) {
            if let Some(session) = self.0.take() {
                // Also clean up after a failed assertion, outside the Tokio worker.
                let _ = std::thread::spawn(move || session.close()).join();
            }
        }
    }
    let session = tokio::task::spawn_blocking(move || {
        let result = factory().and_then(|create| create(&config));
        if let Err(error) = &result {
            eprintln!("session startup failed: {error}");
            diagnose_macos_device_creation();
        }
        result
    })
    .await
    .unwrap()
    .unwrap();
    let mut cleanup = Cleanup(Some(session));
    let session = cleanup.0.as_ref().unwrap();
    assert_eq!(session.interface_name, name);
    assert_eq!(session.device.mtu().unwrap(), 1400);
    let idle_deadline = Instant::now() + Duration::from_secs(2);
    let mut packet = vec![0; 65536];
    loop {
        // Drain link notifications, then cancel a receive while no packet is ready.
        match timeout(Duration::from_millis(20), session.device.recv(&mut packet)).await {
            Err(_) => break,
            Ok(result) => {
                result.unwrap();
            }
        }
        assert!(Instant::now() < idle_deadline, "test interface did not become idle");
    }
    exchange_packets(session).await;
    let session = cleanup.0.take().unwrap();
    tokio::task::spawn_blocking(move || session.close()).await.unwrap().unwrap();
    timeout(Duration::from_secs(2), async {
        while unsafe { libc::if_nametoindex(c_name.as_ptr()) } != 0 {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await
    .unwrap();
    assert!(matching_routes().is_empty(), "session routes remain after cleanup");
}
