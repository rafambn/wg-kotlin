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
fn kernel_dump_and_native_encoding_preserve_exact_routes_and_metrics() {
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
            let expected = crate::platform::os::reference_tests::route_message(&route.0, operation).unwrap();
            assert_eq!(route.message(operation).unwrap(), expected);
        }
        let encoded = route.message(libc::RTM_ADD).unwrap();
        assert_eq!(route_key(&encoded).unwrap(), route_key(&route.0).unwrap());
        let metrics = offset_of!(libc::rt_msghdr, rtm_rmx)..size_of::<libc::rt_msghdr>();
        assert_eq!(encoded[metrics.clone()], route.0[metrics]);
        assert_ne!(header(&encoded).unwrap().rtm_addrs & libc::RTA_IFP, 0);
        let before = addresses(&route.0, &original).unwrap();
        let after = addresses(&encoded, &header(&encoded).unwrap()).unwrap();
        for index in 0..before.len() {
            if index != libc::RTAX_IFP as usize {
                assert_eq!(before[index], after[index]);
            }
        }
    }
}

#[test]
fn native_encoding_preserves_metrics_and_extra_addresses() {
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
        let expected = crate::platform::os::reference_tests::route_message(&route.0, operation).unwrap();
        assert_eq!(route.message(operation).unwrap(), expected);
        assert_eq!(&expected[6..8], &[0x52, 0xa7]);
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
fn replacing_ipv4_and_ipv6_routes_never_reinstalls_the_original() {
    for (destination, gateway) in [("0.0.0.0", "192.0.2.1"), ("::", "fe80::1")] {
        let original = route(destination, 0, 2, Some(gateway));
        let requested = route(destination, 0, 7, None);
        let mut table = FakeRoutes { routes: vec![original], ..Default::default() };
        let mut state = RouteRegistry::default();
        let mut session = SessionRoutes::default();
        session.add(requested, &mut table, &mut state).unwrap();
        let before = table.calls.len();
        session.clear_with(&mut table, &mut state).unwrap();
        assert!(table.routes.is_empty());
        assert!(state.is_empty());
        assert!(table.calls[before..].iter().all(|(operation, _)| *operation == libc::RTM_DELETE));
    }
}

#[test]
fn shared_endpoint_is_removed_only_after_the_last_session_in_either_close_order() {
    for preexisting in [false, true] {
        for first in [0, 1] {
            let pin = route("198.51.100.10", 32, 2, Some("192.0.2.1"));
            let mut original = pin.clone();
            reference_field!(original.0, rtm_rmx.rmx_expire, 1234u32);
            let mut table = FakeRoutes { routes: if preexisting { vec![original] } else { vec![] }, ..Default::default() };
            let mut state = RouteRegistry::default();
            let mut sessions = [Some(SessionRoutes::default()), Some(SessionRoutes::default())];
            for session in &mut sessions {
                session.as_mut().unwrap().add(pin.clone(), &mut table, &mut state).unwrap();
            }
            let before = table.calls.len();
            sessions[first].take().unwrap().clear_with(&mut table, &mut state).unwrap();
            assert_eq!(table.routes.len(), 1);
            assert_eq!(table.calls.len(), before, "a shared route must not be reinstalled or removed");
            sessions[1 - first].take().unwrap().clear_with(&mut table, &mut state).unwrap();
            assert!(table.routes.is_empty(), "the preexisting entry must not be restored either");
            assert!(state.is_empty());
        }
    }
}

#[test]
fn replaced_sessions_cannot_delete_the_new_route_and_are_never_reactivated() {
    for order in [[0, 1, 2], [0, 2, 1], [1, 0, 2], [1, 2, 0], [2, 0, 1], [2, 1, 0]] {
        let original = route("0.0.0.0", 0, 2, Some("192.0.2.1"));
        let key = route_key(&original.0).unwrap();
        let mut table = FakeRoutes { routes: vec![original], ..Default::default() };
        let mut state = RouteRegistry::default();
        let mut sessions = [Some(SessionRoutes::default()), Some(SessionRoutes::default()), Some(SessionRoutes::default())];
        // The third request reuses the first egress, but has a new installation identity.
        for (session, interface) in sessions.iter_mut().zip([7, 8, 7]) {
            session.as_mut().unwrap().add(route("0.0.0.0", 0, interface, None), &mut table, &mut state).unwrap();
        }
        let mut latest_alive = true;
        for index in order {
            sessions[index].take().unwrap().clear_with(&mut table, &mut state).unwrap();
            latest_alive &= index != 2;
            let current = table.find(&key).unwrap();
            assert_eq!(current.is_some(), latest_alive, "close order={order:?}, closing={index}");
            if let Some(current) = current {
                assert_eq!(header(&current.0).unwrap().rtm_index, 7);
            }
        }
        assert!(state.is_empty());
    }
}

#[test]
fn duplicate_keys_are_installed_once_and_cleared_in_reverse_order() {
    let first = route("198.51.100.10", 32, 7, None);
    let second = route("198.51.100.20", 32, 7, None);
    let mut table = FakeRoutes::default();
    let mut state = RouteRegistry::default();
    let mut session = SessionRoutes::default();
    session.add(first.clone(), &mut table, &mut state).unwrap();
    session.add(second.clone(), &mut table, &mut state).unwrap();
    let before = table.calls.len();
    session.add(route("198.51.100.10", 32, 9, None), &mut table, &mut state).unwrap();
    assert_eq!(table.calls.len(), before);
    session.clear_with(&mut table, &mut state).unwrap();
    assert_eq!(&table.calls[before..], &[(libc::RTM_DELETE, route_key(&second.0).unwrap()), (libc::RTM_DELETE, route_key(&first.0).unwrap()),]);
    assert!(table.routes.is_empty());
    assert!(state.is_empty());
}

#[test]
fn failed_add_cleans_earlier_changes_without_recreating_displaced_routes() {
    for applied in [false, true] {
        let original = route("0.0.0.0", 0, 2, Some("192.0.2.1"));
        let key = route_key(&original.0).unwrap();
        let mut table = FakeRoutes { routes: vec![original], ..Default::default() };
        let mut state = RouteRegistry::default();
        let mut session = SessionRoutes::default();
        session.add(route("2001:db8::", 64, 7, None), &mut table, &mut state).unwrap();
        if applied {
            table.after_apply = Some((libc::RTM_ADD, key));
        } else {
            table.fail = Some((libc::RTM_ADD, key));
        }
        let error = session.add(route("0.0.0.0", 0, 7, None), &mut table, &mut state).unwrap_err();
        assert_eq!(error.downcast_ref::<io::Error>().unwrap().raw_os_error(), Some(if applied { libc::ETIMEDOUT } else { libc::EIO }));
        let before = table.calls.len();
        session.clear_with(&mut table, &mut state).unwrap();
        assert!(table.routes.is_empty());
        assert!(state.is_empty());
        assert!(table.calls[before..].iter().all(|(operation, _)| *operation == libc::RTM_DELETE));
    }
}

#[test]
fn failed_delete_does_not_transfer_ownership_or_touch_unattempted_routes() {
    for applied in [false, true] {
        let pin = route("198.51.100.10", 32, 2, Some("192.0.2.1"));
        let untouched = route("203.0.113.10", 32, 2, Some("192.0.2.1"));
        let key = route_key(&pin.0).unwrap();
        let mut table = FakeRoutes { routes: vec![untouched.clone()], ..Default::default() };
        let mut state = RouteRegistry::default();
        let mut first = SessionRoutes::default();
        let mut failed = SessionRoutes::default();
        first.add(pin, &mut table, &mut state).unwrap();
        if applied {
            table.after_apply = Some((libc::RTM_DELETE, key));
        } else {
            table.fail = Some((libc::RTM_DELETE, key));
        }
        assert!(failed.add(route("198.51.100.10", 32, 7, None), &mut table, &mut state).is_err());
        let before = table.calls.len();
        failed.clear_with(&mut table, &mut state).unwrap();
        assert_eq!(table.calls.len(), before, "a failed replacement does not own the earlier session's route");
        first.clear_with(&mut table, &mut state).unwrap();
        assert_eq!(table.routes.len(), 1);
        assert_eq!(table.routes[0].0, untouched.0);
        assert!(state.is_empty());
    }
}

#[test]
fn failed_replacement_add_does_not_reactivate_an_earlier_live_session() {
    for applied in [false, true] {
        let active = route("0.0.0.0", 0, 7, None);
        let key = route_key(&active.0).unwrap();
        let mut table = FakeRoutes::default();
        let mut state = RouteRegistry::default();
        let mut first = SessionRoutes::default();
        let mut failed = SessionRoutes::default();
        first.add(active, &mut table, &mut state).unwrap();
        if applied {
            table.after_apply = Some((libc::RTM_ADD, key));
        } else {
            table.fail = Some((libc::RTM_ADD, key));
        }
        assert!(failed.add(route("0.0.0.0", 0, 8, None), &mut table, &mut state).is_err());
        failed.clear_with(&mut table, &mut state).unwrap();
        let before = table.calls.len();
        first.clear_with(&mut table, &mut state).unwrap();
        assert_eq!(table.calls.len(), before);
        assert!(table.routes.is_empty());
        assert!(state.is_empty());
    }
}

#[test]
fn a_failed_shared_setup_keeps_the_other_sessions_pin() {
    let pin = route("198.51.100.10", 32, 2, Some("192.0.2.1"));
    let broken = route("203.0.113.10", 32, 7, None);
    let mut table = FakeRoutes::default();
    let mut state = RouteRegistry::default();
    let mut first = SessionRoutes::default();
    let mut failed = SessionRoutes::default();
    first.add(pin.clone(), &mut table, &mut state).unwrap();
    failed.add(pin, &mut table, &mut state).unwrap();
    table.fail = Some((libc::RTM_ADD, route_key(&broken.0).unwrap()));
    assert!(failed.add(broken, &mut table, &mut state).is_err());
    failed.clear_with(&mut table, &mut state).unwrap();
    assert_eq!(table.routes.len(), 1);
    first.clear_with(&mut table, &mut state).unwrap();
    assert!(table.routes.is_empty());
    assert!(state.is_empty());
}

#[test]
fn cleanup_retries_failed_removals_but_never_adds_routes() {
    for failure in ["find", "delete", "delete-applied"] {
        let requested = route("0.0.0.0", 0, 7, None);
        let key = route_key(&requested.0).unwrap();
        let mut table = FakeRoutes::default();
        let mut state = RouteRegistry::default();
        let mut session = SessionRoutes::default();
        session.add(requested, &mut table, &mut state).unwrap();
        match failure {
            "find" => table.find_error = Some(key.clone()),
            "delete" => table.fail = Some((libc::RTM_DELETE, key.clone())),
            "delete-applied" => table.after_apply = Some((libc::RTM_DELETE, key.clone())),
            _ => unreachable!(),
        }
        assert!(session.clear_with(&mut table, &mut state).is_err());
        assert_eq!(state[&key].users, 0);
        table.find_error = None;
        let before = table.calls.len();
        recover_orphans(&mut state, &mut table).unwrap();
        assert!(table.routes.is_empty());
        assert!(state.is_empty());
        assert!(table.calls[before..].iter().all(|(operation, _)| *operation == libc::RTM_DELETE));
    }
}

#[test]
fn cleanup_ignores_only_esrch_and_preserves_other_native_delete_errors() {
    for errno in [libc::ESRCH, libc::EIO, libc::EPERM, libc::ENOENT] {
        let mut table = FakeRoutes::default();
        let mut state = RouteRegistry::default();
        let mut session = SessionRoutes::default();
        session.add(route("198.51.100.10", 32, 7, None), &mut table, &mut state).unwrap();
        session.add(route("203.0.113.10", 32, 7, None), &mut table, &mut state).unwrap();
        table.delete_error = Some(errno);
        let result = session.clear_with(&mut table, &mut state);
        assert_eq!(result.is_ok(), errno == libc::ESRCH);
        if let Err(error) = result {
            assert!(format!("{error:#}").contains(&io::Error::from_raw_os_error(errno).to_string()));
        }
        assert_eq!(table.routes.len(), usize::from(errno != libc::ESRCH));
        recover_orphans(&mut state, &mut table).unwrap();
        assert!(table.routes.is_empty());
        assert!(state.is_empty());
    }
}

#[test]
fn external_changes_are_not_deleted_or_overwritten_during_setup_or_cleanup() {
    for orphaned in [false, true] {
        let requested = route("0.0.0.0", 0, 7, None);
        let external = route("0.0.0.0", 0, 9, None);
        let key = route_key(&requested.0).unwrap();
        let mut table = FakeRoutes::default();
        let mut state = RouteRegistry::default();
        let mut session = SessionRoutes::default();
        session.add(requested, &mut table, &mut state).unwrap();
        if orphaned {
            table.fail = Some((libc::RTM_DELETE, key.clone()));
            assert!(session.clear_with(&mut table, &mut state).is_err());
        } else {
            let mut other = SessionRoutes::default();
            table.routes[0] = external.clone();
            let before = table.calls.len();
            assert!(other.add(route("0.0.0.0", 0, 8, None), &mut table, &mut state).is_err());
            other.clear_with(&mut table, &mut state).unwrap();
            assert_eq!(table.calls.len(), before);
            assert!(session.clear_with(&mut table, &mut state).is_err());
        }
        let before = table.calls.len();
        if orphaned {
            table.routes[0] = external.clone();
            assert!(recover_orphans(&mut state, &mut table).is_err());
        }
        assert_eq!(table.calls.len(), before);
        assert_eq!(table.routes[0].0, external.0);
        assert!(state.is_empty());
    }
}

#[test]
fn cleanup_of_an_old_version_cannot_affect_shared_users_of_a_new_version() {
    let mut table = FakeRoutes::default();
    let mut state = RouteRegistry::default();
    let mut old = SessionRoutes::default();
    let mut a = SessionRoutes::default();
    let mut b = SessionRoutes::default();
    old.add(route("0.0.0.0", 0, 7, None), &mut table, &mut state).unwrap();
    a.add(route("0.0.0.0", 0, 8, None), &mut table, &mut state).unwrap();
    b.add(route("0.0.0.0", 0, 8, None), &mut table, &mut state).unwrap();
    old.clear_with(&mut table, &mut state).unwrap();
    a.clear_with(&mut table, &mut state).unwrap();
    assert_eq!(table.routes.len(), 1);
    assert_eq!(header(&table.routes[0].0).unwrap().rtm_index, 8);
    b.clear_with(&mut table, &mut state).unwrap();
    assert!(state.is_empty());
    assert!(table.routes.is_empty());
}

#[test]
fn sharing_an_active_route_can_reinstall_it_if_it_disappeared() {
    let pin = route("198.51.100.10", 32, 2, Some("192.0.2.1"));
    let mut table = FakeRoutes::default();
    let mut state = RouteRegistry::default();
    let mut first = SessionRoutes::default();
    let mut second = SessionRoutes::default();
    first.add(pin.clone(), &mut table, &mut state).unwrap();
    table.routes.clear();
    second.add(pin, &mut table, &mut state).unwrap();
    first.clear_with(&mut table, &mut state).unwrap();
    assert_eq!(table.routes.len(), 1);
    second.clear_with(&mut table, &mut state).unwrap();
    assert!(table.routes.is_empty());
    assert!(state.is_empty());
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
    assert!(matches!(early, Err(mpsc::RecvTimeoutError::Timeout)));
    result.recv_timeout(Duration::from_secs(5)).unwrap().unwrap();
}

#[tokio::test]
#[ignore = "root on macOS; creates two temporary TUNs and free benchmark host routes"]
async fn native_shared_routes_are_cleaned_without_restoring_replaced_routes() {
    tokio::task::spawn_blocking(|| {
        use crate::session::TunSession;
        use daemon_proto::pb::{Cidr, Ip, TunSessionConfig, ip};
        assert_eq!(unsafe { libc::geteuid() }, 0, "run this ignored test as root");
        #[derive(Default)]
        struct Cleanup {
            sessions: Vec<TunSession>,
            fixtures: Vec<RouteMessage>,
        }
        impl Drop for Cleanup {
            fn drop(&mut self) {
                for session in self.sessions.drain(..).rev() {
                    let _ = session.close();
                }
                let _ = recover_routes();
                for fixture in &self.fixtures {
                    if let Ok(key) = route_key(&fixture.0)
                        && let Ok(Some(current)) = SystemRoutes.find(&key)
                        && current.same_egress(fixture).unwrap_or(false)
                    {
                        let _ = SystemRoutes.request(&current, libc::RTM_DELETE);
                    }
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
            .take(4)
            .collect();
        assert_eq!(available.len(), 4, "benchmark host addresses are already in use");
        let destination = format!("198.18.254.{}", available[0]);
        let original = route(&destination, 32, lo, None);
        let endpoint = route(&format!("198.18.254.{}", available[3]), 32, lo, None);
        let key = route_key(&original.0).unwrap();
        let endpoint_key = route_key(&endpoint.0).unwrap();
        let mut cleanup = Cleanup::default();
        let proto = |address: [u8; 4]| Ip { value: Some(ip::Value::V4(address.to_vec())) };
        let mut used_names = Vec::new();
        for first in [0, 1] {
            for fixture in [&original, &endpoint] {
                // The fixture owns these previously free routes even if the ADD reply fails.
                cleanup.fixtures.push(fixture.clone());
                table.request(fixture, libc::RTM_ADD).unwrap();
            }
            let names: Vec<_> =
                (64..256).map(|index| format!("utun{index}")).filter(|name| nix::net::if_::if_nametoindex(name.as_str()).is_err()).take(2).collect();
            assert_eq!(names.len(), 2, "no two unused interfaces in utun64..utun255");
            for (index, name) in names.iter().enumerate() {
                let config = crate::validation::validate_config(&TunSessionConfig {
                    interface_name: name.clone(),
                    mtu: 1400,
                    addresses: vec![Cidr { ip: Some(proto([198, 18, 254, available[index + 1]])), prefix: 32 }],
                    peer_allowed_ips: vec![Cidr { ip: Some(proto([198, 18, 254, available[0]])), prefix: 32 }],
                    peer_endpoints: vec![proto([198, 18, 254, available[3]])],
                    ..Default::default()
                })
                .unwrap();
                cleanup.sessions.push(crate::session::factory().unwrap()(&config).unwrap());
                used_names.push(name.clone());
            }
            eprintln!("no-restore routes interfaces={names:?} benchmark={destination} first_close={first}");
            cleanup.sessions.remove(first).close().unwrap();
            let current = table.find(&key).unwrap();
            if first == 0 {
                assert_eq!(u32::from(header(&current.unwrap().0).unwrap().rtm_index), nix::net::if_::if_nametoindex(names[1].as_str()).unwrap());
            } else {
                assert!(current.is_none(), "closing the latest installation must not bring back an earlier session's route");
            }
            assert!(table.find(&endpoint_key).unwrap().is_some(), "the remaining session still owns the shared endpoint pin");
            cleanup.sessions.remove(0).close().unwrap();
            assert!(table.find(&key).unwrap().is_none(), "the original benchmark route must not be restored");
            assert!(table.find(&endpoint_key).unwrap().is_none(), "the preexisting endpoint route must not be restored");
        }
        drop(cleanup);
        assert!(table.find(&key).unwrap().is_none());
        assert!(table.find(&endpoint_key).unwrap().is_none());
        for name in used_names {
            assert!(nix::net::if_::if_nametoindex(name.as_str()).is_err(), "temporary interface remains: {name}");
        }
    })
    .await
    .unwrap();
}

#[test]
fn cleanup_preserves_every_kind_of_external_egress_change() {
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
        let error = session.clear_with(&mut table, &mut state).unwrap_err();
        assert!(error.to_string().contains("leaving it untouched"), "{changed}: {error:#}");
        assert!(table.calls.is_empty(), "{changed}: {:?}", table.calls);
        assert_eq!(table.routes[0].0, external.0);
    }
}

#[test]
fn cleanup_keeps_native_address_errors_after_the_key_has_matched() {
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
        let error = session.clear_with(&mut table, &mut state).unwrap_err();
        assert_eq!(error.to_string(), "unsupported routing address family");
        assert!(table.calls.is_empty());
        assert_eq!(table.routes[0].0, installed);
    }
}
