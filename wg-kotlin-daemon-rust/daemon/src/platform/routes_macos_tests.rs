use super::*;
use std::net::IpAddr;

fn route(destination: &str, prefix: u8, interface: u16, gateway: Option<&str>) -> Snapshot {
    let destination: IpAddr = destination.parse().unwrap();
    let mask = Route::new(destination, prefix).mask();
    let mut bytes = vec![0; size_of::<libc::rt_msghdr>()];
    append(&mut bytes, &ip_address(destination));
    append(&mut bytes, &gateway.map(|ip| ip_address(ip.parse().unwrap())).unwrap_or_else(|| link_address(interface)));
    append(&mut bytes, &ip_address(mask));
    field!(bytes, rtm_msglen, bytes.len() as u16);
    field!(bytes, rtm_version, libc::RTM_VERSION as u8);
    field!(bytes, rtm_index, interface);
    field!(bytes, rtm_addrs, libc::RTA_DST | libc::RTA_GATEWAY | libc::RTA_NETMASK);
    let host = if prefix == if destination.is_ipv4() { 32 } else { 128 } { libc::RTF_HOST } else { 0 };
    field!(bytes, rtm_flags, libc::RTF_UP | libc::RTF_STATIC | host | if gateway.is_some() { libc::RTF_GATEWAY } else { 0 });
    Snapshot(bytes)
}

#[derive(Default)]
struct FakeRoutes {
    routes: Vec<Snapshot>,
    fail: Option<(i32, RouteKey)>,
    calls: Vec<(i32, RouteKey)>,
}

impl RouteTable for FakeRoutes {
    fn find(&mut self, key: &RouteKey) -> io::Result<Option<Snapshot>> {
        Ok(self.routes.iter().find(|route| route.key().unwrap() == *key).cloned())
    }

    fn request(&mut self, route: &Snapshot, operation: i32) -> io::Result<()> {
        let key = route.key()?;
        self.calls.push((operation, key.clone()));
        if self.fail == Some((operation, key.clone())) {
            self.fail = None;
            return Err(io::Error::from_raw_os_error(libc::EIO));
        }
        let current = self.routes.iter().position(|route| route.key().unwrap() == key);
        match (operation, current) {
            (libc::RTM_ADD, None) => self.routes.push(Snapshot(route.message(libc::RTM_ADD)?)),
            (libc::RTM_DELETE, Some(index)) => {
                self.routes.remove(index);
            }
            (libc::RTM_ADD, Some(_)) => return Err(io::Error::from_raw_os_error(libc::EEXIST)),
            (libc::RTM_DELETE, None) => return Err(io::Error::from_raw_os_error(libc::ESRCH)),
            _ => unreachable!(),
        }
        Ok(())
    }
}

#[test]
fn restores_ipv4_and_ipv6_defaults_with_original_attributes() {
    for (destination, gateway) in [("0.0.0.0", "192.0.2.1"), ("::", "fe80::1")] {
        let mut original = route(destination, 0, 2, Some(gateway));
        field!(original.0, rtm_flags, libc::RTF_UP | libc::RTF_GATEWAY | libc::RTF_PROTO3);
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
        field!(original.0, rtm_addrs, libc::RTA_DST | libc::RTA_GATEWAY | libc::RTA_NETMASK | libc::RTA_IFA);
        field!(original.0, rtm_msglen, original.0.len() as u16);
        let expected = original.message(libc::RTM_ADD).unwrap();
        let requested = route(destination, 0, 7, None);
        let mut table = FakeRoutes { routes: vec![original], ..Default::default() };
        let mut routes = SessionRoutes::new();
        routes.add(requested, &mut table).unwrap();
        assert_eq!(header(&table.routes[0].0).unwrap().rtm_index, 7);
        routes.restore_with(&mut table).unwrap();
        assert_eq!(table.routes[0].0, expected);
        assert!(routes.changes.is_empty());
    }
}

#[test]
fn an_expiring_host_route_is_pinned_then_restored_even_when_the_path_is_the_same() {
    let mut original = route("198.51.100.10", 32, 2, None);
    field!(original.0, rtm_flags, libc::RTF_UP | libc::RTF_HOST | libc::RTF_WASCLONED | libc::RTF_LLINFO);
    let expire = offset_of!(libc::rt_msghdr, rtm_rmx) + offset_of!(libc::rt_metrics, rmx_expire);
    original.0[expire..expire + 4].copy_from_slice(&1234u32.to_ne_bytes());
    let requested = route("198.51.100.10", 32, 2, None);
    let mut table = FakeRoutes { routes: vec![original.clone()], ..Default::default() };
    let mut routes = SessionRoutes::new();
    routes.add(requested, &mut table).unwrap();
    let installed = header(&table.routes[0].0).unwrap();
    assert_ne!(installed.rtm_flags & libc::RTF_STATIC, 0);
    assert_eq!(installed.rtm_rmx.rmx_expire, 0);
    routes.restore_with(&mut table).unwrap();
    assert_eq!(table.routes[0].0, original.message(libc::RTM_ADD).unwrap());
}

#[test]
fn failed_add_restores_the_displaced_route_and_earlier_changes() {
    let first = route("198.51.100.10", 32, 2, Some("192.0.2.1"));
    let original = route("0.0.0.0", 0, 2, Some("192.0.2.1"));
    let requested = route("0.0.0.0", 0, 7, None);
    let keys = [first.key().unwrap(), requested.key().unwrap()];
    let mut table = FakeRoutes { routes: vec![original.clone()], fail: Some((libc::RTM_ADD, keys[1].clone())), ..Default::default() };
    let mut routes = SessionRoutes::new();
    routes.add(first, &mut table).unwrap();
    assert!(routes.add(requested, &mut table).is_err());
    assert!(table.find(&keys[1]).unwrap().is_none());
    routes.restore_with(&mut table).unwrap();
    assert_eq!(table.routes.len(), 1);
    assert_eq!(table.routes[0].0, original.message(libc::RTM_ADD).unwrap());
}

#[test]
fn failed_delete_and_unattempted_routes_are_left_intact() {
    let original = route("0.0.0.0", 0, 2, Some("192.0.2.1"));
    let unattempted = route("203.0.113.0", 24, 2, Some("192.0.2.1"));
    let requested = route("0.0.0.0", 0, 7, None);
    let keys = [requested.key().unwrap(), unattempted.key().unwrap()];
    let mut table =
        FakeRoutes { routes: vec![original.clone(), unattempted.clone()], fail: Some((libc::RTM_DELETE, keys[0].clone())), ..Default::default() };
    let mut routes = SessionRoutes::new();
    assert!(routes.add(requested, &mut table).is_err());
    routes.restore_with(&mut table).unwrap();
    assert_eq!(table.routes[0].0, original.0);
    assert_eq!(table.routes[1].0, unattempted.0);
    assert_eq!(table.calls.len(), 1);
}

#[test]
fn external_route_changes_are_preserved_while_other_routes_are_cleaned() {
    let first = route("0.0.0.0", 0, 7, None);
    let second = route("2001:db8::", 64, 7, None);
    let mut table = FakeRoutes::default();
    let mut routes = SessionRoutes::new();
    routes.add(first, &mut table).unwrap();
    routes.add(second, &mut table).unwrap();
    let external = route("2001:db8::", 64, 9, None);
    table.routes[1] = external.clone();
    assert!(routes.restore_with(&mut table).unwrap_err().contains("leaving it untouched"));
    assert_eq!(table.routes.len(), 1);
    assert_eq!(table.routes[0].0, external.0);
}

#[test]
fn route_keys_distinguish_scope_and_prefix_and_ignore_host_bits() {
    let global = route("192.0.2.0", 24, 2, None);
    let mut scoped = global.clone();
    field!(scoped.0, rtm_flags, libc::RTF_UP | libc::RTF_STATIC | libc::RTF_IFSCOPE);
    assert_ne!(global.key().unwrap(), scoped.key().unwrap());
    assert_ne!(global.key().unwrap(), route("192.0.2.0", 32, 2, None).key().unwrap());
    assert_eq!(global.key().unwrap(), route("192.0.2.42", 24, 2, None).key().unwrap());
}

#[test]
fn kernel_dump_is_read_only_and_preserves_exact_routes_and_metrics() {
    let routes = dump_routes().unwrap();
    let loopback = route("127.0.0.1", 32, 1, None).key().unwrap();
    assert!(routes.iter().any(|route| route.key().unwrap() == loopback));
    for route in routes {
        let original = header(&route.0).unwrap();
        if original.rtm_flags & libc::RTF_PROXY != 0 {
            continue;
        }
        let restored = route.message(libc::RTM_ADD).unwrap();
        assert_eq!(Snapshot(restored.clone()).key().unwrap(), route.key().unwrap());
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
