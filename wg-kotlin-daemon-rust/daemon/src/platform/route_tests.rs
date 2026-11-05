use super::{normalize_domains, resolve_endpoint_routes};
use daemon_proto::pb::{Ip, TunSessionConfig};

#[test]
fn dns_normalization_deduplicates_without_reordering() {
    let domains = [" .z.corp.test ", "a.corp.test", "z.corp.test", "..m.corp.test", "a.corp.test", " ", "..."].map(str::to_string);
    assert_eq!(normalize_domains(&domains), ["z.corp.test", "a.corp.test", "m.corp.test"]);
}

#[test]
fn no_endpoints_require_no_route_lookup() {
    assert!(resolve_endpoint_routes(&TunSessionConfig::default()).unwrap().is_empty());
}

#[test]
fn malformed_endpoint_is_rejected_before_startup() {
    let config = TunSessionConfig { peer_endpoints: vec![Ip { value: None }], ..Default::default() };
    assert!(resolve_endpoint_routes(&config).unwrap_err().contains("invalid peer endpoint"));
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
        let endpoint_routes = resolve_endpoint_routes(&config).unwrap();
        let device = DeviceBuilder::new().name("utun0").ipv4(Ipv4Addr::new(198, 51, 100, 20), 24, None).build_sync().unwrap();
        let name = device.name().unwrap();
        let (mut manager, vpn_routes, endpoint_routes) = super::super::build_and_filter_routes(&config, &name, endpoint_routes).unwrap();
        let setup = super::super::add_routes_with_predelete(&mut manager, &vpn_routes, &endpoint_routes);
        let selected_source = source();
        let selected_decision = decision();
        let cleanup: Vec<_> = vpn_routes.iter().rev().map(|route| manager.delete(route)).collect();
        let endpoint_cleanup: Vec<_> = endpoint_routes.iter().rev().map(|route| route.delete()).collect();
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
            let routes = resolve_endpoint_routes(&config).unwrap();
            routes[0].replace().unwrap();
            let selected = decision();
            let host: serde_json::Value =
                serde_json::from_slice(&ip(&["-j", "route", "show", "table", "1000", "exact", "198.51.100.10/32"])).unwrap();
            let main: serde_json::Value =
                serde_json::from_slice(&ip(&["-j", "route", "show", "table", "main", "exact", "198.51.100.10/32"])).unwrap();
            routes[0].delete().unwrap();
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
        let endpoint_routes = resolve_endpoint_routes(&config).unwrap();
        let device = DeviceBuilder::new().name("utun0").ipv6("2001:db8:2::20".parse::<Ipv6Addr>().unwrap(), 64).build_sync().unwrap();
        let (mut manager, vpn_routes, endpoint_routes) =
            super::super::build_and_filter_routes(&config, &device.name().unwrap(), endpoint_routes).unwrap();
        let setup = super::super::add_routes_with_predelete(&mut manager, &vpn_routes, &endpoint_routes);
        let selected_source = source();
        let selected: serde_json::Value = serde_json::from_slice(&ip(&["-6", "-j", "route", "get", "2001:db8:2::10", "ipproto", "17"])).unwrap();
        let cleanup: Vec<_> = vpn_routes.iter().map(|route| manager.delete(route)).collect();
        let endpoint_cleanup: Vec<_> = endpoint_routes.iter().map(|route| route.delete()).collect();
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
