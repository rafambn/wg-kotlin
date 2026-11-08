use super::*;
use netlink_packet_route::route::RouteNextHop;

#[test]
#[ignore = "isolated Linux netns with NET_ADMIN; run serially"]
fn deleting_an_absent_endpoint_preserves_the_native_netlink_error() {
    struct Installed(EndpointRoute);
    impl Drop for Installed {
        fn drop(&mut self) {
            let _ = self.0.delete();
        }
    }
    let output = std::process::Command::new("ip").args(["route", "show", "exact", "198.51.100.241/32"]).output().unwrap();
    assert!(output.status.success());
    assert!(output.stdout.is_empty(), "test endpoint is already routed");
    let route = Installed(EndpointRoute::lookup("198.51.100.241".parse().unwrap()).unwrap());
    route.0.replace().unwrap();
    route.0.delete().unwrap();
    let error = route.0.delete().unwrap_err();
    let Some(rtnetlink::Error::NetlinkError(native)) = error.downcast_ref::<rtnetlink::Error>() else {
        panic!("expected native netlink error: {error:#}");
    };
    assert_eq!(native.to_io().raw_os_error(), Some(libc::ESRCH));
    assert!(format!("{error:#}").contains(&native.to_io().to_string()));
}

#[test]
fn host_exception_preserves_onlink_and_full_table_id() {
    let mut original = RouteMessage::default();
    original.header.kind = RouteType::Unicast;
    original.header.flags = RouteFlags::Onlink | RouteFlags::Cloned;
    original.attributes = vec![
        RouteAttribute::Table(1000),
        RouteAttribute::Oif(7),
        RouteAttribute::Gateway("192.0.2.1".parse::<IpAddr>().unwrap().into()),
        RouteAttribute::Priority(71),
    ];
    let host = EndpointRoute::from_route("198.51.100.10".parse().unwrap(), original).unwrap().0;
    assert_eq!(host.header.flags, RouteFlags::Onlink);
    assert_eq!(host.header.destination_prefix_length, 32);
    assert!(host.attributes.contains(&RouteAttribute::Table(1000)));
    assert!(host.attributes.contains(&RouteAttribute::Oif(7)));
    assert!(host.attributes.contains(&RouteAttribute::Priority(71)));
}

#[test]
fn multipath_omits_dead_hops_and_replays_only_creation_flags() {
    let mut live = RouteNextHop::default();
    live.interface_index = 7;
    live.hops = 3;
    live.flags = RouteNextHopFlags::Onlink | RouteNextHopFlags::Linkdown | RouteNextHopFlags::Offload | RouteNextHopFlags::Trap;
    live.attributes = vec![RouteAttribute::Gateway("192.0.2.1".parse::<IpAddr>().unwrap().into())];
    let mut dead = live.clone();
    dead.interface_index = 8;
    dead.flags |= RouteNextHopFlags::Dead;
    let mut original = RouteMessage::default();
    original.header.kind = RouteType::Unicast;
    original.attributes = vec![RouteAttribute::MultiPath(vec![dead.clone(), live.clone()])];
    let host = EndpointRoute::from_route("198.51.100.10".parse().unwrap(), original.clone()).unwrap().0;
    live.flags = RouteNextHopFlags::Onlink;
    assert_eq!(host.attributes[0], RouteAttribute::MultiPath(vec![live]));
    original.attributes = vec![RouteAttribute::MultiPath(vec![dead])];
    assert!(EndpointRoute::from_route("198.51.100.10".parse().unwrap(), original).is_err());
}

fn slot_route(endpoint: &str) -> EndpointRoute {
    let ip: IpAddr = endpoint.parse().unwrap();
    let mut route = RouteMessage::default();
    route.header.address_family = ip.into();
    route.header.kind = RouteType::Unicast;
    route.header.table = 254;
    route.attributes = vec![RouteAttribute::Oif(7), RouteAttribute::Table(1000), RouteAttribute::Priority(71)];
    EndpointRoute::from_route(ip, route).unwrap()
}

#[test]
fn pin_ownership_tracks_replace_slots_without_discarding_native_payload() {
    for endpoint in ["198.51.100.10", "2001:db8::10"] {
        let original = slot_route(endpoint);
        let mut reordered = original.clone();
        reordered.0.attributes.reverse();
        reordered.0.attributes.retain(|attr| !matches!(attr, RouteAttribute::Oif(_)));
        reordered.0.attributes.extend([
            RouteAttribute::Oif(99),
            RouteAttribute::PrefSource(endpoint.parse::<IpAddr>().unwrap().into()),
            RouteAttribute::Metrics(vec![netlink_packet_route::route::RouteMetric::Mtu(1300)]),
        ]);
        assert!(original.same_slot(&reordered));
        assert_ne!(original.0, reordered.0, "ownership must not rewrite either payload");

        for attribute in [RouteAttribute::Table(1001), RouteAttribute::Priority(72)] {
            let mut other = original.clone();
            other.0.attributes.retain(|attr| {
                !matches!(
                    (&attribute, attr),
                    (RouteAttribute::Table(_), RouteAttribute::Table(_)) | (RouteAttribute::Priority(_), RouteAttribute::Priority(_))
                )
            });
            other.0.attributes.push(attribute);
            assert!(!original.same_slot(&other));
        }
        let mut header_table = original.clone();
        header_table.0.attributes.retain(|attr| !matches!(attr, RouteAttribute::Table(_)));
        header_table.0.header.table = 100;
        let mut attribute_table = header_table.clone();
        attribute_table.0.header.table = 0;
        attribute_table.0.attributes.push(RouteAttribute::Table(100));
        assert!(header_table.same_slot(&attribute_table));
    }
}

#[test]
fn ipv6_default_priority_and_source_specific_slots_are_normalized() {
    let mut route = slot_route("2001:db8::10");
    route.0.attributes.retain(|attr| !matches!(attr, RouteAttribute::Priority(_)));
    let mut explicit = route.clone();
    explicit.0.attributes.push(RouteAttribute::Priority(1024));
    assert!(route.same_slot(&explicit));
    explicit.0.header.source_prefix_length = 64;
    explicit.0.attributes.push(RouteAttribute::Source("2001:db8:1::".parse::<IpAddr>().unwrap().into()));
    assert!(!route.same_slot(&explicit));
    let mut other_source = explicit.clone();
    other_source.0.header.source_prefix_length = 48;
    assert!(!explicit.same_slot(&other_source));

    let mut v4 = slot_route("198.51.100.10");
    let mut other_tos = v4.clone();
    other_tos.0.header.tos = 16;
    assert!(!v4.same_slot(&other_tos));
    v4.0.attributes.retain(|attr| !matches!(attr, RouteAttribute::Priority(_)));
    let mut explicit = v4.clone();
    explicit.0.attributes.push(RouteAttribute::Priority(1024));
    assert!(!v4.same_slot(&explicit));
}
