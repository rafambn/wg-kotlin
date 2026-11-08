use super::*;
use crate::platform::os::reference_tests::{append, field as reference_field};
use std::mem::offset_of;
use std::net::IpAddr;

pub(super) type TestRouteTable = dyn RouteTable;

pub(super) trait RouteTable {
    fn find(&mut self, key: &RouteKey) -> Result<Option<RouteMessage>>;
    fn request(&mut self, route: &RouteMessage, operation: i32) -> Result<()>;
}

impl RouteTable for SystemRoutes {
    fn find(&mut self, key: &RouteKey) -> Result<Option<RouteMessage>> {
        SystemRoutes::find(self, key)
    }

    fn request(&mut self, route: &RouteMessage, operation: i32) -> Result<()> {
        SystemRoutes::request(self, route, operation)
    }
}

fn route(destination: &str, prefix: u8, interface: u16, gateway: Option<&str>) -> RouteMessage {
    let destination: IpAddr = destination.parse().unwrap();
    let mask = Route::new(destination, prefix).mask();
    let mut bytes = vec![0; size_of::<libc::rt_msghdr>()];
    append(&mut bytes, &ip_address(destination));
    append(&mut bytes, &gateway.map(|ip| ip_address(ip.parse().unwrap())).unwrap_or_else(|| link_address(interface)));
    append(&mut bytes, &ip_address(mask));
    reference_field!(bytes, rtm_msglen, bytes.len() as u16);
    reference_field!(bytes, rtm_version, libc::RTM_VERSION as u8);
    reference_field!(bytes, rtm_index, interface);
    reference_field!(bytes, rtm_addrs, libc::RTA_DST | libc::RTA_GATEWAY | libc::RTA_NETMASK);
    let host = if prefix == if destination.is_ipv4() { 32 } else { 128 } { libc::RTF_HOST } else { 0 };
    reference_field!(bytes, rtm_flags, libc::RTF_UP | libc::RTF_STATIC | host | if gateway.is_some() { libc::RTF_GATEWAY } else { 0 });
    RouteMessage(bytes)
}

#[derive(Default)]
struct FakeRoutes {
    routes: Vec<RouteMessage>,
    fail: Option<(i32, RouteKey)>,
    after_apply: Option<(i32, RouteKey)>,
    find_error: Option<RouteKey>,
    delete_error: Option<i32>,
    calls: Vec<(i32, RouteKey)>,
}

impl RouteTable for FakeRoutes {
    fn find(&mut self, key: &RouteKey) -> Result<Option<RouteMessage>> {
        ensure!(self.find_error.as_ref() != Some(key), io::Error::from_raw_os_error(libc::EIO));
        Ok(self.routes.iter().find(|route| route_key(&route.0).unwrap() == *key).cloned())
    }

    fn request(&mut self, route: &RouteMessage, operation: i32) -> Result<()> {
        let key = route_key(&route.0)?;
        self.calls.push((operation, key.clone()));
        if operation == libc::RTM_DELETE
            && let Some(errno) = self.delete_error.take()
        {
            if errno == libc::ESRCH {
                self.routes.retain(|route| route_key(&route.0).unwrap() != key);
            }
            return Err(io::Error::from_raw_os_error(errno).into());
        }
        if self.fail == Some((operation, key.clone())) {
            self.fail = None;
            return Err(io::Error::from_raw_os_error(libc::EIO).into());
        }
        let current = self.routes.iter().position(|route| route_key(&route.0).unwrap() == key);
        match (operation, current) {
            (libc::RTM_ADD, None) => self.routes.push(RouteMessage(route.message(libc::RTM_ADD)?)),
            (libc::RTM_DELETE, Some(index)) => {
                self.routes.remove(index);
            }
            (libc::RTM_ADD, Some(_)) => return Err(io::Error::from_raw_os_error(libc::EEXIST).into()),
            (libc::RTM_DELETE, None) => return Err(io::Error::from_raw_os_error(libc::ESRCH).into()),
            _ => unreachable!(),
        }
        if self.after_apply.as_ref() == Some(&(operation, key)) {
            self.after_apply = None;
            return Err(io::Error::from_raw_os_error(libc::ETIMEDOUT).into());
        }
        Ok(())
    }
}

#[test]
fn failed_delete_still_restores_the_backup_if_the_entry_disappears_before_cleanup() {
    let original = route("198.51.100.10", 32, 2, Some("192.0.2.1"));
    let requested = route("198.51.100.10", 32, 7, None);
    let key = route_key(&original.0).unwrap();
    let mut table = FakeRoutes { routes: vec![original.clone()], fail: Some((libc::RTM_DELETE, key.clone())), ..Default::default() };
    let mut state = RouteRegistry::default();
    let mut routes = SessionRoutes::default();
    let error = routes.add(requested, &mut table, &mut state).unwrap_err();
    assert_eq!(error.downcast_ref::<io::Error>().unwrap().raw_os_error(), Some(libc::EIO));
    // A failed request can still have reached the kernel, or the entry can expire.
    table.routes.clear();
    let before = table.calls.len();
    routes.restore_with(&mut table, &mut state).unwrap();
    assert_eq!(&table.calls[before..], &[(libc::RTM_ADD, key)]);
    assert_eq!(table.routes.len(), 1);
    assert_eq!(table.routes[0].0, original.message(libc::RTM_ADD).unwrap());
}

#[test]
fn restore_ignores_only_a_missing_route_and_keeps_other_native_delete_errors() {
    for errno in [libc::ESRCH, libc::EIO, libc::EPERM, libc::ENOENT] {
        let original = route("198.51.100.10", 32, 2, Some("192.0.2.1"));
        let requested = route("198.51.100.10", 32, 7, None);
        let mut table = FakeRoutes { routes: vec![original.clone()], ..Default::default() };
        let mut state = RouteRegistry::default();
        let mut routes = SessionRoutes::default();
        routes.add(requested, &mut table, &mut state).unwrap();
        table.delete_error = Some(errno);
        let before = table.calls.len();
        let result = routes.restore_with(&mut table, &mut state);
        let operations: Vec<_> = table.calls[before..].iter().map(|(operation, _)| *operation).collect();
        assert_eq!(table.routes.len(), 1);
        if errno == libc::ESRCH {
            result.unwrap();
            assert_eq!(operations, [libc::RTM_DELETE, libc::RTM_ADD]);
            assert_eq!(table.routes[0].0, original.message(libc::RTM_ADD).unwrap());
        } else {
            let error = result.unwrap_err();
            assert_eq!(error.to_string(), io::Error::from_raw_os_error(errno).to_string());
            assert_eq!(operations, [libc::RTM_DELETE]);
            assert_eq!(header(&table.routes[0].0).unwrap().rtm_index, 7);
        }
    }
}

#[test]
fn restores_ipv4_and_ipv6_defaults_with_original_attributes() {
    for (destination, gateway) in [("0.0.0.0", "192.0.2.1"), ("::", "fe80::1")] {
        let mut original = route(destination, 0, 2, Some(gateway));
        reference_field!(original.0, rtm_flags, libc::RTF_UP | libc::RTF_GATEWAY | libc::RTF_PROTO3);
        let start = offset_of!(libc::rt_msghdr, rtm_rmx);
        for (offset, value) in [
            (offset_of!(libc::rt_metrics, rmx_mtu), 1400u32),
            (offset_of!(libc::rt_metrics, rmx_hopcount), 7),
            (offset_of!(libc::rt_metrics, rmx_rtt), 123),
            (offset_of!(libc::rt_metrics, rmx_locks), libc::RTV_MTU as u32),
        ] {
            original.0[start + offset..start + offset + 4].copy_from_slice(&value.to_ne_bytes());
        }
        // Include the preferred source, as returned by NET_RT_DUMP.
        append(&mut original.0, &ip_address(if destination == "::" { "2001:db8::2" } else { "192.0.2.2" }.parse().unwrap()));
        reference_field!(original.0, rtm_addrs, libc::RTA_DST | libc::RTA_GATEWAY | libc::RTA_NETMASK | libc::RTA_IFA);
        reference_field!(original.0, rtm_msglen, original.0.len() as u16);
        let expected = original.message(libc::RTM_ADD).unwrap();
        let requested = route(destination, 0, 7, None);
        let mut table = FakeRoutes { routes: vec![original], ..Default::default() };
        let mut state = RouteRegistry::default();
        let mut routes = SessionRoutes::default();
        routes.add(requested, &mut table, &mut state).unwrap();
        assert_eq!(header(&table.routes[0].0).unwrap().rtm_index, 7);
        routes.restore_with(&mut table, &mut state).unwrap();
        assert_eq!(table.routes[0].0, expected);
    }
}

#[test]
fn an_expiring_host_route_is_pinned_then_restored_even_when_the_path_is_the_same() {
    let mut original = route("198.51.100.10", 32, 2, None);
    reference_field!(original.0, rtm_flags, libc::RTF_UP | libc::RTF_HOST | libc::RTF_WASCLONED | libc::RTF_LLINFO);
    let expire = offset_of!(libc::rt_msghdr, rtm_rmx) + offset_of!(libc::rt_metrics, rmx_expire);
    original.0[expire..expire + 4].copy_from_slice(&1234u32.to_ne_bytes());
    let requested = route("198.51.100.10", 32, 2, None);
    let mut table = FakeRoutes { routes: vec![original.clone()], ..Default::default() };
    let mut state = RouteRegistry::default();
    let mut routes = SessionRoutes::default();
    routes.add(requested, &mut table, &mut state).unwrap();
    let installed = header(&table.routes[0].0).unwrap();
    assert_ne!(installed.rtm_flags & libc::RTF_STATIC, 0);
    assert_eq!(installed.rtm_rmx.rmx_expire, 0);
    routes.restore_with(&mut table, &mut state).unwrap();
    assert_eq!(table.routes[0].0, original.message(libc::RTM_ADD).unwrap());
}

#[test]
fn failed_add_restores_the_displaced_route_and_earlier_changes() {
    let first = route("198.51.100.10", 32, 2, Some("192.0.2.1"));
    let original = route("0.0.0.0", 0, 2, Some("192.0.2.1"));
    let requested = route("0.0.0.0", 0, 7, None);
    let keys = [route_key(&first.0).unwrap(), route_key(&requested.0).unwrap()];
    let mut table = FakeRoutes { routes: vec![original.clone()], fail: Some((libc::RTM_ADD, keys[1].clone())), ..Default::default() };
    let mut state = RouteRegistry::default();
    let mut routes = SessionRoutes::default();
    routes.add(first, &mut table, &mut state).unwrap();
    assert!(routes.add(requested, &mut table, &mut state).is_err());
    assert!(table.find(&keys[1]).unwrap().is_none());
    routes.restore_with(&mut table, &mut state).unwrap();
    assert_eq!(table.routes.len(), 1);
    assert_eq!(table.routes[0].0, original.message(libc::RTM_ADD).unwrap());
}

#[test]
fn failed_delete_and_unattempted_routes_are_left_intact() {
    let original = route("0.0.0.0", 0, 2, Some("192.0.2.1"));
    let unattempted = route("203.0.113.0", 24, 2, Some("192.0.2.1"));
    let requested = route("0.0.0.0", 0, 7, None);
    let keys = [route_key(&requested.0).unwrap(), route_key(&unattempted.0).unwrap()];
    let mut table =
        FakeRoutes { routes: vec![original.clone(), unattempted.clone()], fail: Some((libc::RTM_DELETE, keys[0].clone())), ..Default::default() };
    let mut state = RouteRegistry::default();
    let mut routes = SessionRoutes::default();
    assert!(routes.add(requested, &mut table, &mut state).is_err());
    routes.restore_with(&mut table, &mut state).unwrap();
    assert_eq!(table.routes[0].0, original.0);
    assert_eq!(table.routes[1].0, unattempted.0);
    assert_eq!(table.calls.len(), 1);
}

#[test]
fn external_route_changes_are_preserved_while_other_routes_are_cleaned() {
    let first = route("0.0.0.0", 0, 7, None);
    let second = route("2001:db8::", 64, 7, None);
    let mut table = FakeRoutes::default();
    let mut state = RouteRegistry::default();
    let mut routes = SessionRoutes::default();
    routes.add(first, &mut table, &mut state).unwrap();
    routes.add(second, &mut table, &mut state).unwrap();
    let external = route("2001:db8::", 64, 9, None);
    table.routes[1] = external.clone();
    assert!(routes.restore_with(&mut table, &mut state).unwrap_err().to_string().contains("leaving it untouched"));
    assert_eq!(table.routes.len(), 1);
    assert_eq!(table.routes[0].0, external.0);
}

#[test]
fn restoration_preserves_every_kind_of_external_egress_change() {
    let flags = libc::RTF_UP | libc::RTF_HOST | libc::RTF_STATIC | libc::RTF_GATEWAY;
    let attrs = vec![
        (libc::RTAX_DST, ip_address("198.51.100.1".parse().unwrap())),
        (libc::RTAX_GATEWAY, ip_address("192.0.2.1".parse().unwrap())),
        (libc::RTAX_NETMASK, ip_address("255.255.255.255".parse().unwrap())),
        (libc::RTAX_IFA, ip_address("192.0.2.7".parse().unwrap())),
    ];
    for changed in ["interface", "gateway", "source", "missing-source", "gateway-flag", "reject", "blackhole"] {
        let requested = RouteMessage::new(7, flags, &attrs).unwrap();
        let key = route_key(&requested.0).unwrap();
        let mut changed_attrs = attrs.clone();
        let (mut index, mut changed_flags) = (7, flags);
        match changed {
            "interface" => index = 9,
            "gateway" => changed_attrs[1].1 = ip_address("192.0.2.254".parse().unwrap()),
            "source" => changed_attrs[3].1 = ip_address("192.0.2.8".parse().unwrap()),
            "missing-source" => changed_attrs.retain(|(kind, _)| *kind != libc::RTAX_IFA),
            "gateway-flag" => changed_flags ^= libc::RTF_GATEWAY,
            "reject" => changed_flags |= libc::RTF_REJECT,
            "blackhole" => changed_flags |= libc::RTF_BLACKHOLE,
            _ => unreachable!(),
        }
        let external = RouteMessage::new(index, changed_flags, &changed_attrs).unwrap();
        assert_eq!(route_key(&external.0).unwrap(), key);
        let mut table = FakeRoutes::default();
        let mut state = RouteRegistry::default();
        let mut session = SessionRoutes::default();
        session.add(requested, &mut table, &mut state).unwrap();
        table.routes[0] = external.clone();
        table.calls.clear();
        let error = session.restore_with(&mut table, &mut state).unwrap_err();
        assert!(error.to_string().contains("leaving it untouched"), "{changed}: {error:#}");
        assert!(table.calls.is_empty(), "{changed}: {:?}", table.calls);
        assert_eq!(table.routes[0].0, external.0);
    }
}

#[test]
fn restoration_keeps_native_address_errors_after_the_key_has_matched() {
    for unknown in [libc::RTAX_GATEWAY, libc::RTAX_IFA] {
        let mut attrs = vec![
            (libc::RTAX_DST, ip_address("198.51.100.1".parse().unwrap())),
            (libc::RTAX_GATEWAY, ip_address("192.0.2.1".parse().unwrap())),
            (libc::RTAX_IFA, ip_address("192.0.2.7".parse().unwrap())),
        ];
        attrs.iter_mut().find(|(kind, _)| *kind == unknown).unwrap().1 = vec![2, libc::AF_UNSPEC as u8];
        let requested = RouteMessage::new(7, libc::RTF_UP | libc::RTF_HOST | libc::RTF_STATIC | libc::RTF_GATEWAY, &attrs).unwrap();
        let mut table = FakeRoutes::default();
        let mut state = RouteRegistry::default();
        let mut session = SessionRoutes::default();
        session.add(requested, &mut table, &mut state).unwrap();
        let installed = table.routes[0].0.clone();
        table.calls.clear();
        let error = session.restore_with(&mut table, &mut state).unwrap_err();
        assert_eq!(error.to_string(), "unsupported routing address family");
        assert!(table.calls.is_empty());
        assert_eq!(table.routes[0].0, installed);
    }
}

#[test]
fn route_keys_distinguish_scope_and_prefix_and_ignore_host_bits() {
    let global = route("192.0.2.0", 24, 2, None);
    let mut scoped = global.clone();
    reference_field!(scoped.0, rtm_flags, libc::RTF_UP | libc::RTF_STATIC | libc::RTF_IFSCOPE);
    assert_ne!(route_key(&global.0).unwrap(), route_key(&scoped.0).unwrap());
    assert_ne!(route_key(&global.0).unwrap(), route_key(&route("192.0.2.0", 32, 2, None).0).unwrap());
    assert_eq!(route_key(&global.0).unwrap(), route_key(&route("192.0.2.42", 24, 2, None).0).unwrap());
}

#[test]
fn route_lookup_never_matches_an_address_of_the_other_family() {
    for (ipv4, ipv6, v4_prefix, v6_prefix) in [("0.0.0.0", "::", 0, 0), ("0.0.0.0", "::", 32, 128), ("192.0.2.1", "c000:201::", 24, 24)] {
        let v4 = route(ipv4, v4_prefix, 7, None);
        let v6 = route(ipv6, v6_prefix, 7, None);
        let v4_key = route_key(&v4.0).unwrap();
        let v6_key = route_key(&v6.0).unwrap();
        assert_ne!(v4_key, v6_key);
        assert!(find_in_snapshot(&v4.0, &v6_key).unwrap().is_none());
        assert!(find_in_snapshot(&v6.0, &v4_key).unwrap().is_none());
    }
}

#[test]
fn host_routes_ignore_masks_but_stay_distinct_from_full_prefix_networks() {
    for (address, bits) in [("192.0.2.1", 32), ("2001:db8::1234", 128), ("fe80::1234", 128)] {
        let host = route(address, bits, 7, None);
        let host_key = route_key(&host.0).unwrap();
        for prefix in [0, 1, bits - 1, bits] {
            let mut masked_host = route(address, prefix, 7, None);
            let flags = header(&masked_host.0).unwrap().rtm_flags | libc::RTF_HOST;
            reference_field!(masked_host.0, rtm_flags, flags);
            assert_eq!(route_key(&masked_host.0).unwrap(), host_key);
        }

        let mut network = host.clone();
        let flags = header(&network.0).unwrap().rtm_flags & !libc::RTF_HOST;
        reference_field!(network.0, rtm_flags, flags);
        let network_key = route_key(&network.0).unwrap();
        assert_ne!(host_key, network_key);
        assert!(find_in_snapshot(&network.0, &host_key).unwrap().is_none());
        assert!(find_in_snapshot(&host.0, &network_key).unwrap().is_none());

        let ip = address.parse().unwrap();
        let missing_mask = RouteMessage::new(7, flags, &[(libc::RTAX_DST, ip_address(ip)), (libc::RTAX_GATEWAY, link_address(7))]).unwrap();
        assert_eq!(route_key(&missing_mask.0).unwrap(), route_key(&route(address, 0, 7, None).0).unwrap());
        let host_without_mask = RouteMessage::new(7, flags | libc::RTF_HOST, &[(libc::RTAX_DST, ip_address(ip))]).unwrap();
        assert_eq!(route_key(&host_without_mask.0).unwrap(), host_key);

        let mut truncated_host = host;
        truncated_host.0.pop();
        assert!(route_key(&truncated_host.0).unwrap_err().to_string().contains("truncated route address"));
    }
}

#[test]
fn tun_routes_normalize_network_bits_for_both_families() {
    for (address, prefix, network) in [
        ("192.0.2.71", 24, "192.0.2.0"),
        ("192.0.2.71", 0, "0.0.0.0"),
        ("192.0.2.71", 32, "192.0.2.71"),
        ("2001:db8::71", 64, "2001:db8::"),
        ("2001:db8::71", 0, "::"),
        ("2001:db8::71", 128, "2001:db8::71"),
    ] {
        let request = Route::new(address.parse().unwrap(), prefix).with_if_name("lo0".into());
        let encoded = tun_route(&request).unwrap();
        let head = header(&encoded.0).unwrap();
        let attrs = addresses(&encoded.0, &head).unwrap();
        assert_eq!(attrs[libc::RTAX_DST as usize], Some(ip_address(network.parse().unwrap())));
        assert_eq!(attrs[libc::RTAX_NETMASK as usize], Some(ip_address(request.mask())));
        assert_eq!(head.rtm_flags & libc::RTF_HOST != 0, prefix == if address.contains(':') { 128 } else { 32 });
    }
}

#[test]
fn kernel_dump_is_read_only_and_preserves_exact_routes_and_metrics() {
    let routes = dump_routes().unwrap();
    let loopback = route_key(&route("127.0.0.1", 32, 1, None).0).unwrap();
    assert!(routes.iter().any(|route| route_key(&route.0).unwrap() == loopback));
    assert_eq!(route_key(&SystemRoutes.find(&loopback).unwrap().unwrap().0).unwrap(), loopback);
    let snapshot: Vec<_> = routes.iter().flat_map(|route| route.0.iter().copied()).collect();
    for route in routes.iter().take(32).chain(routes.iter().rev().take(32)) {
        assert_snapshot_lookup_matches_previous(&snapshot, &route_key(&route.0).unwrap());
    }
    for route in routes {
        let original = header(&route.0).unwrap();
        if original.rtm_flags & libc::RTF_PROXY != 0 {
            continue;
        }
        for operation in [libc::RTM_ADD, libc::RTM_DELETE] {
            let expected = crate::platform::os::reference_tests::restore_message(&route.0, operation).unwrap();
            assert_eq!(route.message(operation).unwrap(), expected);
        }
        let restored = route.message(libc::RTM_ADD).unwrap();
        assert_eq!(route_key(&restored).unwrap(), route_key(&route.0).unwrap());
        let metrics = offset_of!(libc::rt_msghdr, rtm_rmx)..size_of::<libc::rt_msghdr>();
        assert_eq!(restored[metrics.clone()], route.0[metrics]);
        assert_ne!(header(&restored).unwrap().rtm_addrs & libc::RTA_IFP, 0);
        let before = addresses(&route.0, &original).unwrap();
        let after = addresses(&restored, &header(&restored).unwrap()).unwrap();
        for index in 0..before.len() {
            if index != libc::RTAX_IFP as usize {
                assert_eq!(before[index], after[index]);
            }
        }
    }
}

#[test]
fn every_native_metric_and_extra_address_survives_restoration() {
    let mut bytes = vec![0; size_of::<libc::rt_msghdr>()];
    // Deliberately include nonzero C padding and every metric, including reserved words.
    bytes[6..8].copy_from_slice(&[0x52, 0xa7]);
    for (index, byte) in bytes[offset_of!(libc::rt_msghdr, rtm_rmx)..].iter_mut().enumerate() {
        *byte = (index as u8).wrapping_mul(17).wrapping_add(1);
    }
    reference_field!(bytes, rtm_version, libc::RTM_VERSION as u8);
    reference_field!(bytes, rtm_index, 7u16);
    reference_field!(bytes, rtm_flags, libc::RTF_UP | libc::RTF_STATIC | libc::RTF_DONE | libc::RTF_IFREF | libc::RTF_CONDEMNED | libc::RTF_DEAD);
    let mut addrs = 0i32;
    for (index, address) in [
        ip_address("192.0.2.0".parse().unwrap()),
        ip_address("192.0.2.1".parse().unwrap()),
        vec![7, 0, 0, 0, 255, 255, 255], // compact mask with trailing zero
        vec![],                          // a zero-length sockaddr still occupies four bytes
        link_address(9),
        ip_address("192.0.2.2".parse().unwrap()),
        ip_address("192.0.2.3".parse().unwrap()),
        ip_address("192.0.2.255".parse().unwrap()),
    ]
    .into_iter()
    .enumerate()
    {
        addrs |= 1 << index;
        append(&mut bytes, &address);
    }
    reference_field!(bytes, rtm_addrs, addrs);
    reference_field!(bytes, rtm_msglen, bytes.len() as u16);
    let route = RouteMessage(bytes);
    for operation in [libc::RTM_ADD, libc::RTM_DELETE] {
        let expected = crate::platform::os::reference_tests::restore_message(&route.0, operation).unwrap();
        assert_eq!(route.message(operation).unwrap(), expected);
        assert_eq!(&expected[6..8], &[0x52, 0xa7]);
    }
}

#[test]
fn restoration_preserves_native_errors_and_continues_other_cleanup() {
    struct DeleteFailure {
        table: FakeRoutes,
        key: RouteKey,
        errno: i32,
    }
    impl RouteTable for DeleteFailure {
        fn find(&mut self, key: &RouteKey) -> Result<Option<RouteMessage>> {
            self.table.find(key)
        }
        fn request(&mut self, route: &RouteMessage, operation: i32) -> Result<()> {
            if operation == libc::RTM_DELETE && route_key(&route.0)? == self.key {
                if self.errno == libc::ESRCH {
                    // The route disappears between the lookup and the delete request.
                    self.table.routes.retain(|route| route_key(&route.0).unwrap() != self.key);
                }
                return Err(io::Error::from_raw_os_error(self.errno)).context("injected routing failure");
            }
            self.table.request(route, operation)
        }
    }
    for errno in [libc::ESRCH, libc::EPERM, libc::EIO] {
        let original = route("0.0.0.0", 0, 2, Some("192.0.2.1"));
        let requested = route("0.0.0.0", 0, 7, None);
        let unrelated = route("2001:db8::", 64, 7, None);
        let mut table = FakeRoutes { routes: vec![original.clone()], ..Default::default() };
        let mut state = RouteRegistry::default();
        let mut changes = SessionRoutes::default();
        changes.add(unrelated, &mut table, &mut state).unwrap();
        changes.add(requested.clone(), &mut table, &mut state).unwrap();
        let mut table = DeleteFailure { table, key: route_key(&requested.0).unwrap(), errno };
        let result = changes.restore_with(&mut table, &mut state);
        assert_eq!(result.is_ok(), errno == libc::ESRCH);
        assert_eq!(table.table.routes.len(), 1); // The unrelated route is always cleaned.
        if errno == libc::ESRCH {
            assert_eq!(table.table.routes[0].0, original.message(libc::RTM_ADD).unwrap());
        } else {
            let error = result.unwrap_err().to_string();
            assert!(error.contains("injected routing failure"), "{error}");
            assert!(error.contains(&io::Error::from_raw_os_error(errno).to_string()), "{error}");
            assert_eq!(table.table.routes[0].0, requested.message(libc::RTM_ADD).unwrap());
        }
    }
}

#[test]
fn compact_masks_are_zero_filled_and_only_link_local_zones_are_ignored() {
    use std::net::Ipv6Addr;
    assert_eq!(address_bytes(&[6, 0, 0, 0, 255, 255], libc::AF_INET as u8).unwrap().collect::<Vec<_>>(), [255, 255, 0, 0]);
    assert_eq!(address_bytes(&[], libc::AF_INET6 as u8).unwrap().collect::<Vec<_>>(), [0; 16]);
    for text in ["fe80::1", "fea0::1", "febf::1"] {
        let ip: Ipv6Addr = text.parse().unwrap();
        let mut address = ip_address(ip.into());
        address[10..12].copy_from_slice(&[0, 7]); // XNU's embedded link-local zone.
        address[24..28].copy_from_slice(&99u32.to_ne_bytes()); // sockaddr_in6 scope field.
        assert_eq!(address_bytes(&address, libc::AF_INET6 as u8).unwrap().collect::<Vec<_>>(), ip.octets());
        assert!(same_address(&address, &ip_address(ip.into())).unwrap());
    }
    for text in ["2001:db8::1", "fe7f:1234::1", "fec0:1234::1"] {
        let ip: Ipv6Addr = text.parse().unwrap();
        assert_eq!(address_bytes(&ip_address(ip.into()), libc::AF_INET6 as u8).unwrap().collect::<Vec<_>>(), ip.octets());
    }
    assert_eq!(address_bytes(&[], libc::AF_UNSPEC as u8).err().unwrap().to_string(), "unsupported routing address family");
}

#[test]
fn normalization_keeps_previous_bytes_and_errors_for_partial_sockaddrs() {
    // Frozen eager normalizer from before the iterator refactor.
    fn previous(address: &[u8], family: u8) -> Result<Vec<u8>> {
        let (offset, length) = match i32::from(family) {
            libc::AF_INET => (4, 4),
            libc::AF_INET6 => (8, 16),
            _ => bail!("unsupported routing address family"),
        };
        let mut bytes = (offset..offset + length).map(|index| address.get(index).copied().unwrap_or(0)).collect::<Vec<_>>();
        if i32::from(family) == libc::AF_INET6 && bytes[0] == 0xfe && bytes[1] & 0xc0 == 0x80 {
            bytes[2..4].fill(0);
        }
        Ok(bytes)
    }

    let mut state = 0x1a8953e0u32;
    for family in [libc::AF_INET as u8, libc::AF_INET6 as u8, libc::AF_UNSPEC as u8, 255] {
        for prefix in [[0, 0], [0xfe, 0x7f], [0xfe, 0x80], [0xfe, 0xa0], [0xfe, 0xbf], [0xfe, 0xc0], [255, 255]] {
            let mut bytes = [0; 64];
            for byte in &mut bytes {
                state = state.wrapping_mul(1664525).wrapping_add(1013904223);
                *byte = (state >> 24) as u8;
            }
            let offset = if i32::from(family) == libc::AF_INET { 4 } else { 8 };
            bytes[offset..offset + 2].copy_from_slice(&prefix);
            for length in 0..=bytes.len() {
                let actual = address_bytes(&bytes[..length], family).map(Iterator::collect::<Vec<_>>).map_err(|error| error.to_string());
                let expected = previous(&bytes[..length], family).map_err(|error| error.to_string());
                assert_eq!(actual, expected, "family={family} prefix={prefix:?} length={length}");
            }
        }
    }
}

#[test]
fn sysctl_native_io_cause_survives_library_conversion_and_context() {
    let error = Ctl::Oid(vec![i32::MAX]).value().expect_err("an invalid kernel OID must fail");
    let SysctlError::IoError(native) = &error else { panic!("expected native I/O failure, got {error:?}") };
    let native_code = native.raw_os_error();
    let native_kind = native.kind();
    let native_message = native.to_string();
    let error = anyhow::Error::from(error).context("routing table lookup");
    let preserved = error.chain().find_map(|cause| cause.downcast_ref::<io::Error>()).expect("native I/O cause was lost");
    assert_eq!(preserved.raw_os_error(), native_code);
    assert_eq!(preserved.kind(), native_kind);
    assert_eq!(preserved.to_string(), native_message);
    let details = format!("{error:#}");
    assert!(details.contains("routing table lookup"), "{details}");
    assert!(details.contains(&native_message), "{details}");
}

// Previous native dump implementation, retained only as a test reference.
fn dump_routes() -> Result<Vec<RouteMessage>> {
    let table = Ctl::Oid(vec![libc::CTL_NET, libc::PF_ROUTE, 0, 0, libc::NET_RT_DUMP, 0]);
    for _ in 0..3 {
        let bytes = match table.value() {
            Ok(CtlValue::Node(bytes) | CtlValue::Struct(bytes)) => bytes,
            Err(SysctlError::IoError(error)) if error.raw_os_error() == Some(libc::ENOMEM) => continue,
            Err(SysctlError::IoError(error)) => return Err(error.into()),
            Err(error) => return Err(error.into()),
            Ok(_) => bail!("unexpected routing table value type"),
        };
        let mut routes = Vec::new();
        let mut offset = 0;
        while offset < bytes.len() {
            let head = header(&bytes[offset..])?;
            let length = usize::from(head.rtm_msglen);
            ensure!(length >= size_of::<libc::rt_msghdr>() && length <= bytes.len() - offset, "invalid route dump message");
            ensure!(head.rtm_version == libc::RTM_VERSION as u8, "unexpected route dump version");
            let message = &bytes[offset..offset + length];
            let attrs = addresses(message, &head)?;
            let family = attrs[libc::RTAX_DST as usize].as_ref().and_then(|dst| dst.get(1)).copied().map(i32::from);
            if matches!(family, Some(libc::AF_INET | libc::AF_INET6)) {
                routes.push(RouteMessage(message.to_vec()));
            }
            offset += length;
        }
        return Ok(routes);
    }
    bail!("routing table kept changing during snapshot")
}

// Frozen decode-collect-find pipeline for comparing error ordering.
fn previous_find_in_snapshot(bytes: &[u8], key: &RouteKey) -> Result<Option<RouteMessage>> {
    let mut routes = Vec::new();
    let mut offset = 0;
    while offset < bytes.len() {
        let head = header(&bytes[offset..])?;
        let length = usize::from(head.rtm_msglen);
        ensure!(length >= size_of::<libc::rt_msghdr>() && length <= bytes.len() - offset, "invalid route dump message");
        ensure!(head.rtm_version == libc::RTM_VERSION as u8, "unexpected route dump version");
        let message = &bytes[offset..offset + length];
        let attrs = addresses(message, &head)?;
        let family = attrs[libc::RTAX_DST as usize].as_ref().and_then(|dst| dst.get(1)).copied().map(i32::from);
        if matches!(family, Some(libc::AF_INET | libc::AF_INET6)) {
            routes.push(RouteMessage(message.to_vec()));
        }
        offset += length;
    }
    for route in routes {
        if route_key(&route.0)? == *key {
            return Ok(Some(route));
        }
    }
    Ok(None)
}

fn assert_snapshot_lookup_matches_previous(bytes: &[u8], key: &RouteKey) {
    let normalize = |result: Result<Option<RouteMessage>>| result.map(|route| route.map(|route| route.0)).map_err(|error| format!("{error:#}"));
    assert_eq!(normalize(find_in_snapshot(bytes, key)), normalize(previous_find_in_snapshot(bytes, key)));
}

#[test]
fn snapshot_lookup_preserves_first_match_for_prefix_scope_and_family() {
    let ignored = RouteMessage::new(7, libc::RTF_UP, &[(libc::RTAX_DST, link_address(7))]).unwrap();
    let without_destination = RouteMessage::new(7, libc::RTF_UP, &[]).unwrap();
    let mut routes = vec![ignored.clone(), without_destination];
    for (address, prefixes) in [("192.0.2.129", &[0, 1, 24, 31, 32][..]), ("fe80::1234", &[0, 1, 64, 127, 128][..])] {
        for &prefix in prefixes {
            for index in [1, 7] {
                for scoped in [false, true] {
                    let mut original = route(address, prefix, index, None);
                    let flags = header(&original.0).unwrap().rtm_flags;
                    reference_field!(original.0, rtm_flags, flags | if scoped { libc::RTF_IFSCOPE } else { 0 });
                    let mut duplicate = original.clone();
                    reference_field!(duplicate.0, rtm_rmx.rmx_mtu, 9000u32);
                    routes.extend([original, duplicate, ignored.clone()]);
                }
            }
        }
    }
    let bytes: Vec<_> = routes.iter().flat_map(|route| route.0.iter().copied()).collect();
    for route in routes.iter().filter(|route| {
        addresses(&route.0, &header(&route.0).unwrap()).unwrap()[libc::RTAX_DST as usize]
            .as_ref()
            .and_then(|dst| dst.get(1))
            .is_some_and(|family| matches!(i32::from(*family), libc::AF_INET | libc::AF_INET6))
    }) {
        assert_snapshot_lookup_matches_previous(&bytes, &route_key(&route.0).unwrap());
    }
    let absent = route_key(&route("203.0.113.250", 32, 9, None).0).unwrap();
    assert_snapshot_lookup_matches_previous(&bytes, &absent);
    assert!(find_in_snapshot(&bytes, &absent).unwrap().is_none());
}

#[test]
fn snapshot_lookup_keeps_errors_in_the_tail_after_a_match() {
    let target = route("192.0.2.1", 32, 1, None);
    let key = route_key(&target.0).unwrap();
    let second = route("2001:db8::1", 128, 7, None);
    let bytes: Vec<_> = target.0.iter().chain(&second.0).copied().collect();
    for end in 0..=bytes.len() {
        assert_snapshot_lookup_matches_previous(&bytes[..end], &key);
        if end > target.0.len() && end < bytes.len() {
            assert!(find_in_snapshot(&bytes[..end], &key).is_err());
        }
    }
    let mut invalid_version = second.clone();
    reference_field!(invalid_version.0, rtm_version, 0u8);
    let mut invalid_length = second.clone();
    reference_field!(invalid_length.0, rtm_msglen, 0u16);
    let mut ignored_with_missing_address = RouteMessage::new(7, libc::RTF_UP, &[(libc::RTAX_DST, link_address(7))]).unwrap();
    reference_field!(ignored_with_missing_address.0, rtm_addrs, libc::RTA_DST | libc::RTA_GATEWAY);
    for tail in [invalid_version.0, invalid_length.0, ignored_with_missing_address.0] {
        let bytes: Vec<_> = target.0.iter().chain(&tail).copied().collect();
        assert_snapshot_lookup_matches_previous(&bytes, &key);
        assert!(find_in_snapshot(&bytes, &key).is_err());
    }
}

#[test]
fn snapshot_lookup_matches_previous_for_arbitrary_trailing_bytes() {
    let target = route("192.0.2.1", 32, 1, None);
    let key = route_key(&target.0).unwrap();
    let mut state = 0x3799ae274c1082e5u64;
    for length in 0..256 {
        let mut bytes = target.0.clone();
        for _ in 0..length {
            state = state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            bytes.push((state >> 32) as u8);
        }
        assert_snapshot_lookup_matches_previous(&bytes, &key);
    }
}

#[test]
fn duplicate_routes_keep_the_first_backup_and_reverse_insertion_order() {
    let first = route("198.51.100.10", 32, 2, Some("192.0.2.1"));
    let second = route("198.51.100.20", 32, 2, Some("192.0.2.1"));
    let first_key = route_key(&first.0).unwrap();
    let second_key = route_key(&second.0).unwrap();
    let mut table = FakeRoutes { routes: vec![first.clone(), second.clone()], ..Default::default() };
    let mut state = RouteRegistry::default();
    let mut changes = SessionRoutes::default();
    changes.add(route("198.51.100.10", 32, 7, None), &mut table, &mut state).unwrap();
    changes.add(route("198.51.100.20", 32, 7, None), &mut table, &mut state).unwrap();
    let before = table.calls.len();
    changes.add(route("198.51.100.10", 32, 9, Some("192.0.2.9")), &mut table, &mut state).unwrap();
    assert_eq!(table.calls.len(), before, "a duplicate must not install another egress");
    changes.restore_with(&mut table, &mut state).unwrap();
    assert_eq!(
        &table.calls[before..],
        &[
            (libc::RTM_DELETE, second_key.clone()),
            (libc::RTM_ADD, second_key.clone()),
            (libc::RTM_DELETE, first_key.clone()),
            (libc::RTM_ADD, first_key.clone()),
        ]
    );
    assert_eq!(table.find(&first_key).unwrap().unwrap().0, first.message(libc::RTM_ADD).unwrap());
    assert_eq!(table.find(&second_key).unwrap().unwrap().0, second.message(libc::RTM_ADD).unwrap());
}

#[test]
fn multi_session_overlapping_routes_restore_the_original_in_either_close_order() {
    for (destination, gateway) in [("0.0.0.0", "192.0.2.1"), ("::", "fe80::1")] {
        for first in [0, 1] {
            let mut original = route(destination, 0, 2, Some(gateway));
            reference_field!(original.0, rtm_rmx.rmx_mtu, 1492u32);
            let expected = original.message(libc::RTM_ADD).unwrap();
            let mut table = FakeRoutes { routes: vec![original], ..Default::default() };
            let mut state = RouteRegistry::default();
            let mut sessions = [Some(SessionRoutes::default()), Some(SessionRoutes::default())];
            for (index, session) in sessions.iter_mut().enumerate() {
                session.as_mut().unwrap().add(route(destination, 0, 7 + index as u16, None), &mut table, &mut state).unwrap();
            }
            sessions[first].take().unwrap().restore_with(&mut table, &mut state).unwrap();
            assert_eq!(header(&table.routes[0].0).unwrap().rtm_index, if first == 0 { 8 } else { 7 });
            sessions[1 - first].take().unwrap().restore_with(&mut table, &mut state).unwrap();
            assert_eq!(table.routes.len(), 1);
            assert_eq!(table.routes[0].0, expected);
        }
    }
}

#[test]
fn multi_session_shared_endpoint_pin_is_kept_until_the_last_owner_closes() {
    for preexisting in [false, true] {
        let pin = route("198.51.100.10", 32, 2, Some("192.0.2.1"));
        let mut original = pin.clone();
        reference_field!(original.0, rtm_rmx.rmx_mtu, 1400u32);
        reference_field!(original.0, rtm_rmx.rmx_expire, 1234u32);
        let expected = original.message(libc::RTM_ADD).unwrap();
        let mut table = FakeRoutes { routes: if preexisting { vec![original] } else { vec![] }, ..Default::default() };
        let mut state = RouteRegistry::default();
        let mut a = SessionRoutes::default();
        let mut b = SessionRoutes::default();
        a.add(pin.clone(), &mut table, &mut state).unwrap();
        b.add(pin.clone(), &mut table, &mut state).unwrap();
        a.restore_with(&mut table, &mut state).unwrap();
        assert_eq!(table.routes.len(), 1, "the second session still owns the endpoint pin");
        assert_eq!(table.routes[0].0, pin.message(libc::RTM_ADD).unwrap());
        b.restore_with(&mut table, &mut state).unwrap();
        assert_eq!(table.routes.len(), usize::from(preexisting));
        if preexisting {
            assert_eq!(table.routes[0].0, expected);
        }
    }
}

fn assert_current_route(table: &mut FakeRoutes, key: &RouteKey, expected: Option<&RouteMessage>) {
    assert_eq!(table.find(key).unwrap().map(|route| route.0), expected.map(|route| route.message(libc::RTM_ADD).unwrap()));
}

#[test]
fn multi_session_three_owners_can_close_in_every_order() {
    for order in [[0, 1, 2], [0, 2, 1], [1, 0, 2], [1, 2, 0], [2, 0, 1], [2, 1, 0]] {
        let original = route("0.0.0.0", 0, 2, Some("192.0.2.1"));
        let key = route_key(&original.0).unwrap();
        let requested: [_; 3] = std::array::from_fn(|index| route("0.0.0.0", 0, 7 + index as u16, None));
        let mut table = FakeRoutes { routes: vec![original.clone()], ..Default::default() };
        let mut state = RouteRegistry::default();
        let mut sessions: [_; 3] = std::array::from_fn(|_| Some(SessionRoutes::default()));
        for (session, requested) in sessions.iter_mut().zip(&requested) {
            session.as_mut().unwrap().add(requested.clone(), &mut table, &mut state).unwrap();
        }
        for index in order {
            sessions[index].take().unwrap().restore_with(&mut table, &mut state).unwrap();
            let expected = sessions.iter().rposition(Option::is_some).map(|index| &requested[index]).unwrap_or(&original);
            assert_current_route(&mut table, &key, Some(expected));
        }
        assert!(state.is_empty());
    }
}

#[test]
fn multi_session_scopes_are_independent_and_duplicate_keys_keep_the_first_request() {
    let mut requested = [route("198.51.100.10", 32, 7, None), route("198.51.100.10", 32, 8, None)];
    for route in &mut requested {
        let flags = header(&route.0).unwrap().rtm_flags | libc::RTF_IFSCOPE;
        reference_field!(route.0, rtm_flags, flags);
    }
    let mut table = FakeRoutes::default();
    let mut state = RouteRegistry::default();
    let mut a = SessionRoutes::default();
    let mut b = SessionRoutes::default();
    a.add(requested[0].clone(), &mut table, &mut state).unwrap();
    let calls = table.calls.len();
    a.add(requested[0].clone(), &mut table, &mut state).unwrap();
    assert_eq!(table.calls.len(), calls);
    b.add(requested[1].clone(), &mut table, &mut state).unwrap();
    assert_eq!(state.len(), 2);
    a.restore_with(&mut table, &mut state).unwrap();
    assert_current_route(&mut table, &route_key(&requested[1].0).unwrap(), Some(&requested[1]));
    b.restore_with(&mut table, &mut state).unwrap();
    assert!(state.is_empty());
    assert!(table.routes.is_empty());
}

#[test]
fn multi_session_partial_setup_rolls_back_without_releasing_another_session_pin() {
    let pin = route("198.51.100.10", 32, 2, Some("192.0.2.1"));
    let original = route("0.0.0.0", 0, 2, Some("192.0.2.1"));
    let vpn = route("0.0.0.0", 0, 8, None);
    let key = route_key(&vpn.0).unwrap();
    let mut table = FakeRoutes { routes: vec![original.clone()], fail: Some((libc::RTM_ADD, key.clone())), ..Default::default() };
    let mut state = RouteRegistry::default();
    let mut a = SessionRoutes::default();
    let mut b = SessionRoutes::default();
    a.add(pin.clone(), &mut table, &mut state).unwrap();
    b.add(pin.clone(), &mut table, &mut state).unwrap();
    assert!(b.add(vpn, &mut table, &mut state).is_err());
    b.restore_with(&mut table, &mut state).unwrap();
    assert_current_route(&mut table, &route_key(&pin.0).unwrap(), Some(&pin));
    assert_current_route(&mut table, &key, Some(&original));
    a.restore_with(&mut table, &mut state).unwrap();
    assert_current_route(&mut table, &route_key(&pin.0).unwrap(), None);
    assert_current_route(&mut table, &key, Some(&original));
    assert!(state.is_empty());
}

#[test]
fn multi_session_failed_setup_restores_only_owners_that_are_still_alive() {
    for operation in [libc::RTM_DELETE, libc::RTM_ADD] {
        for applied in [false, true] {
            for close_a_first in [false, true] {
                let original = route("0.0.0.0", 0, 2, Some("192.0.2.1"));
                let key = route_key(&original.0).unwrap();
                let active = route("0.0.0.0", 0, 7, None);
                let mut table = FakeRoutes { routes: vec![original.clone()], ..Default::default() };
                let mut state = RouteRegistry::default();
                let mut a = Some(SessionRoutes::default());
                let mut b = SessionRoutes::default();
                a.as_mut().unwrap().add(active.clone(), &mut table, &mut state).unwrap();
                if applied {
                    table.after_apply = Some((operation, key.clone()));
                } else {
                    table.fail = Some((operation, key.clone()));
                }
                let error = b.add(route("0.0.0.0", 0, 8, None), &mut table, &mut state).unwrap_err();
                assert_eq!(error.downcast_ref::<io::Error>().unwrap().raw_os_error(), Some(if applied { libc::ETIMEDOUT } else { libc::EIO }));
                if close_a_first {
                    a.take().unwrap().restore_with(&mut table, &mut state).unwrap();
                    // Closing the old device can remove a route whose failed DELETE never took effect.
                    table.routes.retain(|route| header(&route.0).unwrap().rtm_index != 7);
                }
                b.restore_with(&mut table, &mut state).unwrap();
                assert_current_route(&mut table, &key, Some(if close_a_first { &original } else { &active }));
                if let Some(a) = a {
                    a.restore_with(&mut table, &mut state).unwrap();
                }
                assert_current_route(&mut table, &key, Some(&original));
                assert!(state.is_empty());
            }
        }
    }
}

#[test]
fn multi_session_restores_the_observed_snapshot_of_the_previous_owner() {
    let original = route("0.0.0.0", 0, 2, Some("192.0.2.1"));
    let key = route_key(&original.0).unwrap();
    let mut table = FakeRoutes { routes: vec![original.clone()], ..Default::default() };
    let mut state = RouteRegistry::default();
    let mut a = SessionRoutes::default();
    let mut b = SessionRoutes::default();
    a.add(route("0.0.0.0", 0, 7, None), &mut table, &mut state).unwrap();
    reference_field!(table.routes[0].0, rtm_rmx.rmx_mtu, 1280u32);
    reference_field!(table.routes[0].0, rtm_rmx.rmx_rtt, 777u32);
    let observed = table.routes[0].clone();
    b.add(route("0.0.0.0", 0, 8, None), &mut table, &mut state).unwrap();
    b.restore_with(&mut table, &mut state).unwrap();
    assert_current_route(&mut table, &key, Some(&observed));
    a.restore_with(&mut table, &mut state).unwrap();
    assert_current_route(&mut table, &key, Some(&original));
}

#[test]
fn multi_session_residual_route_does_not_replace_a_live_owner_snapshot() {
    let original = route("0.0.0.0", 0, 2, Some("192.0.2.1"));
    let key = route_key(&original.0).unwrap();
    let mut table = FakeRoutes { routes: vec![original.clone()], ..Default::default() };
    let mut state = RouteRegistry::default();
    let mut a = SessionRoutes::default();
    let mut b = SessionRoutes::default();
    let mut c = SessionRoutes::default();
    a.add(route("0.0.0.0", 0, 7, None), &mut table, &mut state).unwrap();
    reference_field!(table.routes[0].0, rtm_rmx.rmx_mtu, 1280u32);
    let observed_a = table.routes[0].clone();
    b.add(route("0.0.0.0", 0, 8, None), &mut table, &mut state).unwrap();
    reference_field!(table.routes[0].0, rtm_rmx.rmx_mtu, 1100u32);
    table.fail = Some((libc::RTM_DELETE, key.clone()));
    assert!(b.restore_with(&mut table, &mut state).is_err());
    c.add(route("0.0.0.0", 0, 9, None), &mut table, &mut state).unwrap();
    c.restore_with(&mut table, &mut state).unwrap();
    assert_current_route(&mut table, &key, Some(&observed_a));
    a.restore_with(&mut table, &mut state).unwrap();
    assert_current_route(&mut table, &key, Some(&original));
    assert!(state.is_empty());
}

#[test]
fn orphaned_cleanup_preserves_the_baseline_until_recovery_finishes() {
    for failure in ["find", "delete", "add", "delete-applied", "add-applied"] {
        let mut original = route("0.0.0.0", 0, 2, Some("192.0.2.1"));
        reference_field!(original.0, rtm_rmx.rmx_mtu, 1492u32);
        let key = route_key(&original.0).unwrap();
        let mut table = FakeRoutes { routes: vec![original.clone()], ..Default::default() };
        let mut state = RouteRegistry::default();
        let mut session = SessionRoutes::default();
        session.add(route("0.0.0.0", 0, 7, None), &mut table, &mut state).unwrap();
        match failure {
            "find" => table.find_error = Some(key.clone()),
            "delete" => table.fail = Some((libc::RTM_DELETE, key.clone())),
            "add" => table.fail = Some((libc::RTM_ADD, key.clone())),
            "delete-applied" => table.after_apply = Some((libc::RTM_DELETE, key.clone())),
            "add-applied" => table.after_apply = Some((libc::RTM_ADD, key.clone())),
            _ => unreachable!(),
        }
        assert!(session.restore_with(&mut table, &mut state).is_err(), "{failure}");
        assert!(state[&key].owners.is_empty());
        assert_eq!(state[&key].original.as_ref().unwrap().0, original.0);
        table.find_error = None;
        let before = table.calls.len();
        recover_orphans(&mut state, &mut table).unwrap();
        assert!(state.is_empty(), "{failure}");
        assert_current_route(&mut table, &key, Some(&original));
        if failure == "add-applied" {
            assert_eq!(table.calls.len(), before, "a lost ACK must not force another DELETE/ADD of the restored baseline");
        }
    }
}

#[test]
fn orphaned_cleanup_is_not_adopted_as_a_new_sessions_baseline() {
    let original = route("0.0.0.0", 0, 2, Some("192.0.2.1"));
    let key = route_key(&original.0).unwrap();
    let mut table = FakeRoutes { routes: vec![original.clone()], ..Default::default() };
    let mut state = RouteRegistry::default();
    let mut a = SessionRoutes::default();
    let mut b = SessionRoutes::default();
    a.add(route("0.0.0.0", 0, 7, None), &mut table, &mut state).unwrap();
    table.fail = Some((libc::RTM_DELETE, key.clone()));
    assert!(a.restore_with(&mut table, &mut state).is_err());
    b.add(route("0.0.0.0", 0, 8, None), &mut table, &mut state).unwrap();
    b.restore_with(&mut table, &mut state).unwrap();
    assert_current_route(&mut table, &key, Some(&original));
    assert!(state.is_empty());
}

#[test]
fn orphan_recovery_attempts_every_key_and_does_not_prevent_active_cleanup() {
    let first = route("198.51.100.10", 32, 2, Some("192.0.2.1"));
    let second = route("203.0.113.10", 32, 2, Some("192.0.2.1"));
    let active = route("2001:db8::", 64, 7, None);
    let keys = [route_key(&first.0).unwrap(), route_key(&second.0).unwrap(), route_key(&active.0).unwrap()];
    let mut table = FakeRoutes::default();
    let mut state = RouteRegistry::default();
    let mut a = SessionRoutes::default();
    let mut b = SessionRoutes::default();
    a.add(first, &mut table, &mut state).unwrap();
    a.add(second, &mut table, &mut state).unwrap();
    table.delete_error = Some(libc::EIO);
    table.fail = Some((libc::RTM_DELETE, keys[0].clone()));
    assert!(a.restore_with(&mut table, &mut state).is_err());
    assert_eq!(state.len(), 2);
    table.delete_error = None;
    table.find_error = Some(keys[1].clone());
    assert!(recover_orphans(&mut state, &mut table).is_err());
    assert!(!state.contains_key(&keys[0]));
    assert!(state.contains_key(&keys[1]));
    b.add(active, &mut table, &mut state).unwrap();
    b.restore_with(&mut table, &mut state).unwrap();
    assert!(!state.contains_key(&keys[2]));
    table.find_error = None;
    recover_orphans(&mut state, &mut table).unwrap();
    assert!(state.is_empty());
    assert!(table.routes.is_empty());
}

#[test]
fn external_changes_are_preserved_with_active_owners_and_orphaned_history() {
    for keep_a_alive in [false, true] {
        let original = route("0.0.0.0", 0, 2, Some("192.0.2.1"));
        let external = route("0.0.0.0", 0, 9, None);
        let key = route_key(&original.0).unwrap();
        let mut table = FakeRoutes { routes: vec![original], ..Default::default() };
        let mut state = RouteRegistry::default();
        let mut a = Some(SessionRoutes::default());
        let mut b = SessionRoutes::default();
        a.as_mut().unwrap().add(route("0.0.0.0", 0, 7, None), &mut table, &mut state).unwrap();
        if keep_a_alive {
            b.add(route("0.0.0.0", 0, 8, None), &mut table, &mut state).unwrap();
        } else {
            table.fail = Some((libc::RTM_DELETE, key.clone()));
            assert!(a.take().unwrap().restore_with(&mut table, &mut state).is_err());
        }
        table.routes[0] = external.clone();
        let before = table.calls.len();
        if keep_a_alive {
            assert!(b.restore_with(&mut table, &mut state).is_err());
            assert_eq!(state[&key].owners.len(), 1);
            assert!(a.take().unwrap().restore_with(&mut table, &mut state).is_err());
        } else {
            assert!(recover_orphans(&mut state, &mut table).is_err());
        }
        assert_eq!(table.calls.len(), before);
        assert_eq!(table.routes[0].0, external.0);
        assert!(state.is_empty());
        let mut replacement = SessionRoutes::default();
        replacement.add(route("0.0.0.0", 0, 8, None), &mut table, &mut state).unwrap();
        replacement.restore_with(&mut table, &mut state).unwrap();
        assert_current_route(&mut table, &key, Some(&external));
    }
}

#[test]
fn endpoint_lookup_waits_for_the_route_transaction_mutex() {
    use std::sync::mpsc;
    let guard = ROUTES.lock().unwrap();
    let (ready, started) = mpsc::channel();
    let (finished, result) = mpsc::channel();
    let worker = std::thread::spawn(move || {
        ready.send(()).unwrap();
        finished.send(RouteMessage::lookup("127.0.0.1".parse().unwrap())).unwrap();
    });
    started.recv_timeout(Duration::from_secs(5)).unwrap();
    let early = result.recv_timeout(Duration::from_millis(100));
    drop(guard);
    worker.join().unwrap();
    assert!(matches!(early, Err(mpsc::RecvTimeoutError::Timeout)), "lookup ran while the route transaction lock was held");
    result.recv_timeout(Duration::from_secs(5)).unwrap().unwrap();
}

#[tokio::test]
#[ignore = "root on macOS; creates two temporary TUNs, one free benchmark host route and a loopback endpoint pin"]
async fn native_shared_routes_survive_out_of_order_session_cleanup() {
    tokio::task::spawn_blocking(|| {
        use crate::session::TunSession;
        use daemon_proto::pb::{Cidr, Ip, TunSessionConfig, ip};
        assert_eq!(unsafe { libc::geteuid() }, 0, "run this ignored test as root");
        #[derive(Default)]
        struct Cleanup {
            sessions: Vec<TunSession>,
            original: Option<RouteMessage>,
        }
        impl Drop for Cleanup {
            fn drop(&mut self) {
                for session in self.sessions.drain(..).rev() {
                    let _ = session.close();
                }
                let _ = recover_routes();
                if let Some(original) = &self.original
                    && let Ok(key) = route_key(&original.0)
                    && let Ok(Some(current)) = SystemRoutes.find(&key)
                    && current.same_egress(original).unwrap_or(false)
                {
                    let _ = SystemRoutes.request(&current, libc::RTM_DELETE);
                }
            }
        }
        let lo = u16::try_from(nix::net::if_::if_nametoindex("lo0").unwrap()).unwrap();
        let mut table = SystemRoutes;
        let available: Vec<_> = (128..254)
            .filter(|last| {
                let candidate = route(&format!("198.18.254.{last}"), 32, lo, None);
                table.find(&route_key(&candidate.0).unwrap()).unwrap().is_none()
            })
            .take(3)
            .collect();
        assert_eq!(available.len(), 3, "benchmark host addresses are already in use");
        let destination = format!("198.18.254.{}", available[0]);
        let mut original = route(&destination, 32, lo, None);
        reference_field!(original.0, rtm_rmx.rmx_mtu, 1337u32);
        let key = route_key(&original.0).unwrap();
        let mut cleanup = Cleanup::default();
        let added = table.request(&original, libc::RTM_ADD);
        if !added.as_ref().is_err_and(|error| error.downcast_ref::<io::Error>().and_then(io::Error::raw_os_error) == Some(libc::EEXIST)) {
            cleanup.original = Some(original.clone());
        }
        added.unwrap();
        let original = table.find(&key).unwrap().unwrap();
        let endpoint = RouteMessage::lookup("127.0.0.2".parse().unwrap()).unwrap();
        let endpoint_key = route_key(&endpoint.0).unwrap();
        let endpoint_before = table.find(&endpoint_key).unwrap();
        let proto = |address: [u8; 4]| Ip { value: Some(ip::Value::V4(address.to_vec())) };
        let mut used_names = Vec::new();
        for first in [0, 1] {
            let names: Vec<_> =
                (64..256).map(|index| format!("utun{index}")).filter(|name| nix::net::if_::if_nametoindex(name.as_str()).is_err()).take(2).collect();
            assert_eq!(names.len(), 2, "no two unused interfaces in utun64..utun255");
            for (index, name) in names.iter().enumerate() {
                let config = crate::validation::validate_config(&TunSessionConfig {
                    interface_name: name.clone(),
                    mtu: 1400,
                    addresses: vec![Cidr { ip: Some(proto([198, 18, 254, available[index + 1]])), prefix: 32 }],
                    peer_allowed_ips: vec![Cidr { ip: Some(proto([198, 18, 254, available[0]])), prefix: 32 }],
                    peer_endpoints: vec![proto([127, 0, 0, 2])],
                    ..Default::default()
                })
                .unwrap();
                // Separate factories must still share the same route ownership registry.
                cleanup.sessions.push(crate::session::factory().unwrap()(&config).unwrap());
                used_names.push(name.clone());
            }
            eprintln!("shared routes interfaces={names:?} benchmark={destination} first_close={first}");
            let remaining = names[1 - first].clone();
            cleanup.sessions.remove(first).close().unwrap();
            let current = table.find(&key).unwrap().unwrap();
            assert_eq!(u32::from(header(&current.0).unwrap().rtm_index), nix::net::if_::if_nametoindex(remaining.as_str()).unwrap());
            let pin = table.find(&endpoint_key).unwrap().expect("the remaining session still needs its endpoint pin");
            assert!(pin.same_egress(&endpoint).unwrap());
            cleanup.sessions.remove(0).close().unwrap();
            let restored = table.find(&key).unwrap().unwrap();
            assert!(restored.same_egress(&original).unwrap());
            assert_eq!(header(&restored.0).unwrap().rtm_rmx.rmx_mtu, header(&original.0).unwrap().rtm_rmx.rmx_mtu);
            let pin_after = table.find(&endpoint_key).unwrap();
            match (&endpoint_before, &pin_after) {
                (Some(before), Some(after)) => assert!(after.same_egress(before).unwrap()),
                (None, None) => {}
                _ => panic!("endpoint pin was not restored to its original presence"),
            }
        }
        drop(cleanup);
        assert!(table.find(&key).unwrap().is_none(), "benchmark route remains after fixture cleanup");
        for name in used_names {
            assert!(nix::net::if_::if_nametoindex(name.as_str()).is_err(), "temporary interface remains: {name}");
        }
    })
    .await
    .unwrap();
}
