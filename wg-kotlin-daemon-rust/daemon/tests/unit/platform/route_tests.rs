use daemon_proto::pb::{Ip, TunSessionConfig};

fn validated(config: &TunSessionConfig) -> crate::validation::Config {
    let mut config = config.clone();
    if config.interface_name.is_empty() {
        config.interface_name = "utun0".into();
    }
    if config.addresses.is_empty() {
        config.addresses =
            vec![daemon_proto::pb::Cidr { ip: Some(Ip { value: Some(daemon_proto::pb::ip::Value::V4(vec![10, 0, 0, 1])) }), prefix: 24 }];
    }
    crate::validation::validate_config(&config).unwrap()
}

#[test]
fn dns_normalization_deduplicates_without_reordering() {
    let domains = [" .z.corp.test ", "a.corp.test", "z.corp.test", "..m.corp.test", "a.corp.test"].map(str::to_string);
    let config = TunSessionConfig {
        dns: Some(daemon_proto::pb::DnsConfig {
            search_domains: domains.to_vec(),
            servers: vec![Ip { value: Some(daemon_proto::pb::ip::Value::V4(vec![9, 9, 9, 9])) }],
        }),
        ..Default::default()
    };
    assert_eq!(validated(&config).dns_domains, ["z.corp.test", "a.corp.test", "m.corp.test"]);
}

#[test]
fn no_endpoints_require_no_route_lookup() {
    assert!(super::resolve_endpoint_routes(&validated(&TunSessionConfig::default())).unwrap().routes.is_empty());
}

#[test]
fn malformed_endpoint_is_rejected_before_startup() {
    let config = TunSessionConfig { peer_endpoints: vec![Ip { value: None }], ..Default::default() };
    assert!(super::resolve_endpoint_routes(&validated(&config)).unwrap_err().to_string().contains("invalid peer endpoint"));
}

#[cfg(not(target_os = "macos"))]
#[test]
fn resolved_batch_keeps_other_setup_and_cleanup_out_until_it_is_dropped() {
    use std::{sync::mpsc, time::Duration};
    let resolved = super::resolve_endpoint_routes(&validated(&TunSessionConfig::default())).unwrap();
    let (entered_tx, entered_rx) = mpsc::channel();
    let (locked_tx, locked_rx) = mpsc::channel();
    let worker = std::thread::spawn(move || {
        entered_tx.send(()).unwrap();
        let pins = super::endpoint_pins().unwrap();
        locked_tx.send(pins.0.len()).unwrap();
    });
    entered_rx.recv_timeout(Duration::from_secs(2)).unwrap();
    let blocked = locked_rx.recv_timeout(Duration::from_millis(100));
    drop(resolved); // Also covers dropping a resolved batch after TUN creation fails.
    assert!(matches!(blocked, Err(mpsc::RecvTimeoutError::Timeout)));
    assert_eq!(locked_rx.recv_timeout(Duration::from_secs(2)).unwrap(), 0);
    worker.join().unwrap();
}

#[test]
fn endpoint_filter_preserves_subnets_and_other_hosts() {
    use daemon_proto::pb::{Cidr, ip};
    use std::net::IpAddr;

    let proto_ip = |value: IpAddr| Ip {
        value: Some(match value {
            IpAddr::V4(address) => ip::Value::V4(address.octets().to_vec()),
            IpAddr::V6(address) => ip::Value::V6(address.octets().to_vec()),
        }),
    };
    for (endpoint, other_host, prefix) in
        [("203.0.113.0", "203.0.113.1", 24), ("2001:db8::", "2001:db8::1", 64), ("0.0.0.0", "0.0.0.1", 0), ("::", "::1", 0)]
    {
        let endpoint: IpAddr = endpoint.parse().unwrap();
        let other_host: IpAddr = other_host.parse().unwrap();
        let host_prefix = if endpoint.is_ipv4() { 32 } else { 128 };
        let mut config = TunSessionConfig {
            peer_endpoints: vec![proto_ip(endpoint)],
            peer_allowed_ips: [(endpoint, prefix), (endpoint, host_prefix), (other_host, host_prefix)]
                .into_iter()
                .map(|(address, prefix)| Cidr { ip: Some(proto_ip(address)), prefix })
                .collect(),
            ..Default::default()
        };
        let routes = super::vpn_routes(&validated(&config), "utun0");
        let destinations: Vec<_> = routes.iter().map(|route| (route.destination(), route.prefix())).collect();
        assert_eq!(destinations, [(endpoint, prefix as u8), (other_host, host_prefix as u8)]);

        config.peer_endpoints.clear();
        let routes = super::vpn_routes(&validated(&config), "utun0");
        assert_eq!(routes.len(), 3);
    }
}

#[cfg(target_os = "linux")]
mod linux {
    use super::*;
    use daemon_proto::pb::{Cidr, ip};
    use std::net::{IpAddr, Ipv4Addr, UdpSocket};
    use std::process::Command;
    use tun_rs::DeviceBuilder;

    fn ip(args: &[&str]) -> Vec<u8> {
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

    fn dummy(name: &'static str, address: &str) -> Link {
        ip(&["link", "add", name, "type", "dummy"]);
        let link = Link(name);
        ip(&["addr", "add", address, "dev", name]);
        ip(&["link", "set", name, "up"]);
        link
    }

    fn assert_path_preserved() {
        let endpoint = Ipv4Addr::new(198, 51, 100, 10);
        let source = || -> std::io::Result<IpAddr> {
            let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0))?;
            socket.connect((endpoint, 51820))?;
            Ok(socket.local_addr()?.ip())
        };
        let decision = || -> serde_json::Value { serde_json::from_slice(&ip(&["-j", "route", "get", "198.51.100.10", "ipproto", "17"])).unwrap() };
        let original_source = source().unwrap();
        let original_decision = decision();
        let config = TunSessionConfig {
            peer_endpoints: vec![Ip { value: Some(ip::Value::V4(endpoint.octets().to_vec())) }],
            peer_allowed_ips: vec![Cidr { ip: Some(Ip { value: Some(ip::Value::V4(vec![128, 0, 0, 0])) }), prefix: 1 }],
            ..Default::default()
        };
        let endpoint_routes = super::super::resolve_endpoint_routes(&validated(&config)).unwrap();
        let device = DeviceBuilder::new().name("utun0").ipv4(Ipv4Addr::new(198, 51, 100, 20), 24, None).build_sync().unwrap();
        let name = device.name().unwrap();
        let mut manager = route_manager::RouteManager::new().unwrap();
        let vpn_routes = super::super::vpn_routes(&validated(&config), &name);
        let setup = endpoint_routes
            .routes
            .iter()
            .try_for_each(|route| route.replace())
            .and_then(|_| super::super::add_routes_with_predelete(&mut manager, &vpn_routes));
        let selected_source = source();
        let selected_decision = decision();
        let cleanup: Vec<anyhow::Result<()>> = vpn_routes.iter().rev().map(|route| manager.delete(route).map_err(Into::into)).collect();
        let endpoint_cleanup: Vec<_> = endpoint_routes.routes.iter().rev().map(|route| route.delete()).collect();
        drop(device);
        setup.unwrap();
        for result in cleanup.into_iter().chain(endpoint_cleanup) {
            result.unwrap();
        }
        assert_eq!(selected_source.unwrap(), original_source);
        assert_eq!(selected_decision[0]["dev"], original_decision[0]["dev"]);
        assert_eq!(selected_decision[0]["gateway"], original_decision[0]["gateway"]);
        assert_ne!(selected_decision[0]["dev"], "utun0");
    }

    #[test]
    #[ignore = "isolated Linux netns, NET_ADMIN, /dev/net/tun and iproute2; run serially"]
    fn endpoint_exception_bypasses_connected_and_vpn_routes() {
        assert_path_preserved();
    }

    #[test]
    #[ignore = "isolated Linux netns, NET_ADMIN, /dev/net/tun and iproute2; run serially"]
    fn ignores_more_specific_route_in_unselected_table() {
        let _link = dummy("spare0", "192.0.2.2/32");
        ip(&["route", "add", "198.51.100.0/24", "dev", "spare0", "table", "100"]);
        assert_path_preserved();
    }

    #[test]
    #[ignore = "isolated Linux netns, NET_ADMIN, /dev/net/tun and iproute2; run serially"]
    fn preserves_egress_through_previous_vpn_selected_by_rules() {
        let _link = dummy("utun1", "192.0.2.2/32");
        ip(&["route", "add", "default", "dev", "utun1", "table", "1000"]);
        ip(&["rule", "add", "to", "198.51.100.10/32", "table", "1000", "priority", "1000"]);
        let result = std::panic::catch_unwind(assert_path_preserved);
        ip(&["rule", "del", "priority", "1000"]);
        result.unwrap();
    }

    #[test]
    #[ignore = "isolated Linux netns, NET_ADMIN and iproute2; run serially"]
    fn uid_policy_exception_stays_in_its_selected_table() {
        let _link = dummy("utun1", "192.0.2.2/32");
        let owner = unsafe { libc::getuid() };
        let owner_range = format!("{owner}-{owner}");
        let other = (owner + 1).to_string();
        let decision = || -> serde_json::Value {
            serde_json::from_slice(&ip(&["-j", "route", "get", "198.51.100.10", "uid", &other, "ipproto", "17"])).unwrap()
        };
        let original = decision();
        ip(&["route", "add", "default", "dev", "utun1", "table", "1000"]);
        ip(&["rule", "add", "to", "198.51.100.10/32", "uidrange", &owner_range, "table", "1000", "priority", "1000"]);
        let config = TunSessionConfig { peer_endpoints: vec![Ip { value: Some(ip::Value::V4(vec![198, 51, 100, 10])) }], ..Default::default() };
        let result = std::panic::catch_unwind(|| {
            let routes = super::super::resolve_endpoint_routes(&validated(&config)).unwrap();
            routes.routes[0].replace().unwrap();
            let selected = decision();
            let host: serde_json::Value =
                serde_json::from_slice(&ip(&["-j", "route", "show", "table", "1000", "exact", "198.51.100.10/32"])).unwrap();
            let main: serde_json::Value =
                serde_json::from_slice(&ip(&["-j", "route", "show", "table", "main", "exact", "198.51.100.10/32"])).unwrap();
            routes.routes[0].delete().unwrap();
            assert_eq!(host[0]["dev"], "utun1");
            assert!(main.as_array().unwrap().is_empty());
            assert_eq!(selected[0]["dev"], original[0]["dev"]);
            assert_eq!(selected[0]["gateway"], original[0]["gateway"]);
        });
        ip(&["rule", "del", "priority", "1000"]);
        result.unwrap();
    }

    #[test]
    #[ignore = "isolated Linux netns, NET_ADMIN, /dev/net/tun and iproute2; run serially"]
    fn multipath_with_one_dead_nexthop_keeps_the_live_path() {
        let _live = dummy("uplink1", "192.0.2.2/24");
        let _dead = dummy("uplink2", "192.0.3.2/24");
        ip(&[
            "route",
            "add",
            "198.51.100.0/24",
            "nexthop",
            "via",
            "192.0.2.1",
            "dev",
            "uplink1",
            "weight",
            "1",
            "nexthop",
            "via",
            "192.0.3.1",
            "dev",
            "uplink2",
            "weight",
            "1",
        ]);
        ip(&["link", "set", "uplink2", "down"]);
        assert_path_preserved();
    }

    #[test]
    #[ignore = "isolated Linux netns, NET_ADMIN, /dev/net/tun and iproute2; run serially"]
    fn preserves_onlink_gateway_outside_the_interface_prefix() {
        let _link = dummy("uplink1", "192.0.2.2/32");
        ip(&["route", "add", "198.51.100.0/24", "via", "192.0.2.1", "dev", "uplink1", "onlink", "metric", "10"]);
        assert_path_preserved();
    }

    #[test]
    #[ignore = "isolated Linux netns, IPv6 enabled, NET_ADMIN, /dev/net/tun and iproute2; run serially"]
    fn ipv6_host_exception_keeps_link_local_gateway_and_egress() {
        use std::net::Ipv6Addr;
        ip(&["link", "add", "uplink6", "type", "dummy"]);
        let _link = Link("uplink6");
        ip(&["link", "set", "uplink6", "up"]);
        ip(&["-6", "addr", "add", "2001:db8:1::2/128", "dev", "uplink6", "nodad"]);
        ip(&["-6", "addr", "add", "fe80::2/64", "dev", "uplink6", "nodad"]);
        ip(&["-6", "route", "add", "2001:db8:2::/64", "via", "fe80::1", "dev", "uplink6", "onlink", "src", "2001:db8:1::2", "metric", "1024"]);
        let endpoint: Ipv6Addr = "2001:db8:2::10".parse().unwrap();
        let source = || -> std::io::Result<IpAddr> {
            let socket = UdpSocket::bind((Ipv6Addr::UNSPECIFIED, 0))?;
            socket.connect((endpoint, 51820))?;
            Ok(socket.local_addr()?.ip())
        };
        let original_source = source().unwrap();
        let config = TunSessionConfig {
            peer_endpoints: vec![Ip { value: Some(ip::Value::V6(endpoint.octets().to_vec())) }],
            peer_allowed_ips: vec![Cidr { ip: Some(Ip { value: Some(ip::Value::V6(vec![0; 16])) }), prefix: 0 }],
            ..Default::default()
        };
        let endpoint_routes = super::super::resolve_endpoint_routes(&validated(&config)).unwrap();
        let device = DeviceBuilder::new().name("utun0").ipv6("2001:db8:2::20".parse::<Ipv6Addr>().unwrap(), 64).build_sync().unwrap();
        let mut manager = route_manager::RouteManager::new().unwrap();
        let vpn_routes = super::super::vpn_routes(&validated(&config), &device.name().unwrap());
        let setup = endpoint_routes
            .routes
            .iter()
            .try_for_each(|route| route.replace())
            .and_then(|_| super::super::add_routes_with_predelete(&mut manager, &vpn_routes));
        let selected_source = source();
        let selected: serde_json::Value = serde_json::from_slice(&ip(&["-6", "-j", "route", "get", "2001:db8:2::10", "ipproto", "17"])).unwrap();
        let cleanup: Vec<anyhow::Result<()>> = vpn_routes.iter().map(|route| manager.delete(route).map_err(Into::into)).collect();
        let endpoint_cleanup: Vec<_> = endpoint_routes.routes.iter().map(|route| route.delete()).collect();
        drop(device);
        setup.unwrap();
        for result in cleanup.into_iter().chain(endpoint_cleanup) {
            result.unwrap();
        }
        assert_eq!(selected_source.unwrap(), original_source);
        assert_eq!(selected[0]["dev"], "uplink6");
        assert_eq!(selected[0]["gateway"], "fe80::1");
    }
}
