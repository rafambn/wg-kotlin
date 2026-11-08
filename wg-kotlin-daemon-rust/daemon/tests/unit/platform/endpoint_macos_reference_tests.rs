use socket2::{Domain, SockAddr, Socket, Type};
use std::io::{self, Read, Write};
use std::mem::{offset_of, size_of};
use std::net::IpAddr;
use std::sync::atomic::{AtomicI32, Ordering};
use std::time::Duration;

static SEQUENCE: AtomicI32 = AtomicI32::new(1);

#[derive(Clone, Debug)]
pub struct EndpointRoute {
    destination: IpAddr,
    interface: u16,
    gateway: Vec<u8>,
    via_gateway: bool,
    source: Option<Vec<u8>>,
    mtu: u32,
    hopcount: u32,
}

macro_rules! field {
    ($bytes:expr, $($name:ident).+, $value:expr) => {{
        let value = ($value).to_ne_bytes();
        let start = offset_of!(libc::rt_msghdr, $($name).+);
        $bytes[start..start + value.len()].copy_from_slice(&value);
    }};
}
pub(in crate::platform) use field;

impl EndpointRoute {
    pub fn lookup(destination: IpAddr) -> io::Result<Self> {
        let query = Self { destination, interface: 0, gateway: vec![], via_gateway: false, source: None, mtu: 0, hopcount: 0 };
        let reply = exchange(query.message(libc::RTM_GET)?)?;
        let header = header(&reply)?;
        if header.rtm_index == 0 || header.rtm_flags & (libc::RTF_REJECT | libc::RTF_BLACKHOLE) != 0 {
            return Err(io::Error::other("endpoint route is not reachable"));
        }
        let addresses = addresses(&reply, &header)?;
        let gateway = match addresses[libc::RTAX_GATEWAY as usize].as_ref() {
            Some(gateway) if matches!(gateway.get(1).copied().map(i32::from), Some(libc::AF_INET | libc::AF_INET6)) => gateway.clone(),
            _ => link_address(header.rtm_index),
        };
        Ok(Self {
            destination,
            interface: header.rtm_index,
            gateway,
            via_gateway: header.rtm_flags & libc::RTF_GATEWAY != 0,
            source: addresses[libc::RTAX_IFA as usize].clone(),
            mtu: header.rtm_rmx.rmx_mtu,
            hopcount: header.rtm_rmx.rmx_hopcount,
        })
    }

    pub(super) fn message(&self, operation: i32) -> io::Result<Vec<u8>> {
        let mut payload = ip_address(self.destination);
        let mut addrs = libc::RTA_DST;
        let mut flags = libc::RTF_UP | libc::RTF_HOST | libc::RTF_STATIC;
        if operation == libc::RTM_GET {
            // XNU only returns the selected source address when IFP/IFA is
            // requested. An empty IFP requests it without restricting lookup.
            addrs |= libc::RTA_IFP;
            append(&mut payload, &link_address(0));
        } else {
            addrs |= libc::RTA_GATEWAY | libc::RTA_NETMASK | libc::RTA_IFP;
            // Direct routes can also return an IP gateway, including loopback.
            // Preserve the kernel's flag rather than inferring it from sockaddr.
            if self.via_gateway {
                flags |= libc::RTF_GATEWAY;
            }
            append(&mut payload, &self.gateway);
            let mask =
                if self.destination.is_ipv4() { IpAddr::V4(std::net::Ipv4Addr::BROADCAST) } else { IpAddr::V6(std::net::Ipv6Addr::from(u128::MAX)) };
            append(&mut payload, &ip_address(mask));
            // RTA_IFP is essential even for gateway routes: without it XNU can
            // resolve the gateway through the newly-created TUN instead.
            append(&mut payload, &link_address(self.interface));
            if let Some(source) = &self.source {
                addrs |= libc::RTA_IFA;
                append(&mut payload, source);
            }
        }
        let length = size_of::<libc::rt_msghdr>() + payload.len();
        let mut message = vec![0; size_of::<libc::rt_msghdr>()];
        field!(message, rtm_msglen, u16::try_from(length).map_err(io::Error::other)?);
        field!(message, rtm_version, libc::RTM_VERSION as u8);
        field!(message, rtm_type, operation as u8);
        field!(message, rtm_index, self.interface);
        field!(message, rtm_flags, flags);
        field!(message, rtm_addrs, addrs);
        if operation == libc::RTM_ADD {
            field!(message, rtm_rmx.rmx_mtu, self.mtu);
            field!(message, rtm_rmx.rmx_hopcount, self.hopcount);
            let inits = if self.mtu != 0 { libc::RTV_MTU } else { 0 } | if self.hopcount != 0 { libc::RTV_HOPCOUNT } else { 0 };
            field!(message, rtm_inits, inits as u32);
        }
        message.extend(payload);
        Ok(message)
    }
}

pub(super) fn ip_address(ip: IpAddr) -> Vec<u8> {
    let address = SockAddr::from(std::net::SocketAddr::new(ip, 0));
    // SockAddr initializes its sockaddr storage; copy only its declared length.
    unsafe { std::slice::from_raw_parts(address.as_ptr().cast(), address.len() as usize) }.to_vec()
}

pub(super) fn link_address(index: u16) -> Vec<u8> {
    let mut bytes = vec![0; size_of::<libc::sockaddr_dl>()];
    bytes[0] = bytes.len() as u8;
    bytes[1] = libc::AF_LINK as u8;
    bytes[2..4].copy_from_slice(&index.to_ne_bytes());
    bytes
}

pub(super) fn append(payload: &mut Vec<u8>, address: &[u8]) {
    let start = payload.len();
    payload.extend_from_slice(address);
    payload.resize(start + address.len().max(4).next_multiple_of(4), 0);
}

pub(super) fn header(bytes: &[u8]) -> io::Result<libc::rt_msghdr> {
    if bytes.len() < size_of::<libc::rt_msghdr>() {
        return Err(io::Error::other("truncated routing message"));
    }
    // Routing socket data is byte-aligned; all header fields are integer types.
    Ok(unsafe { std::ptr::read_unaligned(bytes.as_ptr().cast()) })
}

pub(super) fn addresses(bytes: &[u8], header: &libc::rt_msghdr) -> io::Result<[Option<Vec<u8>>; libc::RTAX_MAX as usize]> {
    let mut result = std::array::from_fn(|_| None);
    let mut offset = size_of::<libc::rt_msghdr>();
    for (index, slot) in result.iter_mut().enumerate() {
        if header.rtm_addrs & (1 << index) == 0 {
            continue;
        }
        let length = usize::from(*bytes.get(offset).ok_or_else(|| io::Error::other("missing route address"))?);
        let step = length.max(4).next_multiple_of(4);
        if offset + step > bytes.len() {
            return Err(io::Error::other("truncated route address"));
        }
        *slot = Some(bytes[offset..offset + length].to_vec());
        offset += step;
    }
    Ok(result)
}

pub(super) fn exchange(mut message: Vec<u8>) -> io::Result<Vec<u8>> {
    let operation = message[offset_of!(libc::rt_msghdr, rtm_type)];
    let pid = unsafe { libc::getpid() };
    let sequence = SEQUENCE.fetch_add(1, Ordering::Relaxed);
    field!(message, rtm_pid, pid);
    field!(message, rtm_seq, sequence);
    let mut socket = Socket::new(Domain::from(libc::PF_ROUTE), Type::RAW, None)?;
    socket.set_read_timeout(Some(Duration::from_secs(5)))?;
    socket.set_write_timeout(Some(Duration::from_secs(5)))?;
    socket.write_all(&message)?;
    let deadline = std::time::Instant::now() + Duration::from_secs(5);
    let mut buffer = [0; 65536];
    loop {
        let remaining = deadline.saturating_duration_since(std::time::Instant::now());
        if remaining.is_zero() {
            return Err(io::Error::new(io::ErrorKind::TimedOut, "routing reply timed out"));
        }
        socket.set_read_timeout(Some(remaining))?;
        let count = socket.read(&mut buffer)?;
        if count == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "routing socket closed"));
        }
        let mut offset = 0;
        while offset < count {
            if count - offset < 4 {
                return Err(io::Error::other("truncated routing message prefix"));
            }
            let length = usize::from(u16::from_ne_bytes([buffer[offset], buffer[offset + 1]]));
            if length < 4 || length > count - offset {
                return Err(io::Error::other("invalid routing message length"));
            }
            // Interface/address notifications have different, often shorter
            // headers than rt_msghdr. They are not replies to this request.
            if buffer[offset + 2] != libc::RTM_VERSION as u8 || buffer[offset + 3] != operation {
                offset += length;
                continue;
            }
            let reply = header(&buffer[offset..offset + length])?;
            if reply.rtm_version == libc::RTM_VERSION as u8 && reply.rtm_pid == pid && reply.rtm_seq == sequence {
                if reply.rtm_errno != 0 {
                    return Err(io::Error::from_raw_os_error(reply.rtm_errno));
                }
                return Ok(buffer[offset..offset + length].to_vec());
            }
            offset += length;
        }
    }
}

#[test]
fn endpoint_requests_match_the_previous_implementation() {
    for destination in ["127.0.0.1", "::1"] {
        let destination = destination.parse().unwrap();
        let previous = EndpointRoute::lookup(destination).unwrap();
        let current = super::RouteMessage::lookup(destination).unwrap();
        for operation in [libc::RTM_ADD, libc::RTM_DELETE] {
            let expected = restore_message(&previous.message(libc::RTM_ADD).unwrap(), operation).unwrap();
            assert_eq!(current.message(operation).unwrap(), expected);
        }
    }
}

#[test]
fn native_routing_socket_keeps_queued_replies_in_separate_reads() {
    let pid = unsafe { libc::getpid() };
    let mut socket = Socket::new(Domain::from(libc::PF_ROUTE), Type::RAW, None).unwrap();
    socket.set_recv_buffer_size(1024 * 1024).unwrap();
    socket.set_write_timeout(Some(Duration::from_secs(1))).unwrap();
    let mut pending = std::collections::HashSet::from([-1001, -1002]);
    for (sequence, destination) in [(-1001i32, "127.0.0.1"), (-1002, "::1")] {
        let query = super::RouteMessage::new(
            0,
            libc::RTF_UP | libc::RTF_HOST | libc::RTF_STATIC,
            &[(libc::RTAX_DST, ip_address(destination.parse().unwrap())), (libc::RTAX_IFP, link_address(0))],
        )
        .unwrap();
        let mut message = query.message(libc::RTM_GET).unwrap();
        field!(message, rtm_pid, pid);
        field!(message, rtm_seq, sequence);
        socket.write_all(&message).unwrap();
    }
    let deadline = std::time::Instant::now() + Duration::from_secs(2);
    let mut buffer = [0; 65536];
    while !pending.is_empty() {
        let remaining = deadline.saturating_duration_since(std::time::Instant::now());
        assert!(!remaining.is_zero(), "missing replies: {pending:?}");
        socket.set_read_timeout(Some(remaining)).unwrap();
        let count = socket.read(&mut buffer).unwrap();
        assert!(count >= 4);
        assert_eq!(usize::from(u16::from_ne_bytes([buffer[0], buffer[1]])), count, "one read must contain exactly one record");
        if buffer[2] == libc::RTM_VERSION as u8 && buffer[3] == libc::RTM_GET as u8 {
            let reply = header(&buffer[..count]).unwrap();
            if reply.rtm_pid == pid {
                pending.remove(&reply.rtm_seq);
            }
        }
    }
}

pub(in crate::platform) fn restore_message(original: &[u8], operation: i32) -> io::Result<Vec<u8>> {
    let head = header(original)?;
    if head.rtm_index == 0 {
        return Err(io::Error::other("route has no interface to restore"));
    }
    if head.rtm_flags & libc::RTF_PROXY != 0 {
        return Err(io::Error::other("cannot safely replace a kernel proxy route"));
    }
    let mut attrs = addresses(original, &head)?;
    attrs[libc::RTAX_IFP as usize] = Some(link_address(head.rtm_index));
    let mut bytes = original[..size_of::<libc::rt_msghdr>()].to_vec();
    let mut addrs = 0i32;
    for (index, value) in attrs.iter().enumerate() {
        if let Some(value) = value {
            addrs |= 1 << index;
            append(&mut bytes, value);
        }
    }
    field!(bytes, rtm_msglen, u16::try_from(bytes.len()).map_err(io::Error::other)?);
    field!(bytes, rtm_type, operation as u8);
    field!(bytes, rtm_errno, 0i32);
    field!(bytes, rtm_addrs, addrs);
    field!(bytes, rtm_flags, head.rtm_flags & !(libc::RTF_DONE | libc::RTF_IFREF | libc::RTF_CONDEMNED | libc::RTF_DEAD));
    let metrics = libc::RTV_MTU
        | libc::RTV_HOPCOUNT
        | libc::RTV_EXPIRE
        | libc::RTV_RPIPE
        | libc::RTV_SPIPE
        | libc::RTV_SSTHRESH
        | libc::RTV_RTT
        | libc::RTV_RTTVAR;
    field!(bytes, rtm_inits, if operation == libc::RTM_ADD { metrics as u32 } else { 0u32 });
    Ok(bytes)
}

mod previous_tests {
    use super::*;

    #[test]
    fn endpoint_metrics_match_the_previous_serializer_byte_for_byte() {
        for mtu in [0, 1400, u32::MAX] {
            for hopcount in [0, 3, u32::MAX] {
                let mut route = EndpointRoute::lookup("127.0.0.1".parse().unwrap()).unwrap();
                route.mtu = mtu;
                route.hopcount = hopcount;
                for operation in [libc::RTM_GET, libc::RTM_ADD, libc::RTM_DELETE] {
                    let bytes = route.message(operation).unwrap();
                    let mut expected = vec![0; size_of::<libc::rt_msghdr>()];
                    let mut inits = 0u32;
                    if operation == libc::RTM_ADD {
                        // Retain the former offset-based encoder as an independent reference.
                        for (offset, value, bit) in [
                            (offset_of!(libc::rt_metrics, rmx_mtu), mtu, libc::RTV_MTU),
                            (offset_of!(libc::rt_metrics, rmx_hopcount), hopcount, libc::RTV_HOPCOUNT),
                        ] {
                            if value != 0 {
                                let start = offset_of!(libc::rt_msghdr, rtm_rmx) + offset;
                                expected[start..start + 4].copy_from_slice(&value.to_ne_bytes());
                                inits |= bit as u32;
                            }
                        }
                    }
                    let metrics = offset_of!(libc::rt_msghdr, rtm_rmx)..size_of::<libc::rt_msghdr>();
                    assert_eq!(bytes[metrics.clone()], expected[metrics]);
                    assert_eq!(header(&bytes).unwrap().rtm_inits, inits);
                }
            }
        }
    }

    #[test]
    fn native_ip_addresses_preserve_the_original_wire_layout() {
        for number in [0, 1, 0x7f000001, 0xc0000201, u32::MAX] {
            let mut expected = vec![0; size_of::<libc::sockaddr_in>()];
            expected[0] = expected.len() as u8;
            expected[1] = libc::AF_INET as u8;
            expected[4..8].copy_from_slice(&number.to_be_bytes());
            assert_eq!(ip_address(std::net::Ipv4Addr::from(number).into()), expected);
        }
        for number in [0, 1, 0xfe800000000000000000000000000001, 0x20010db800000000123456789abcdef0, u128::MAX] {
            let mut expected = vec![0; size_of::<libc::sockaddr_in6>()];
            expected[0] = expected.len() as u8;
            expected[1] = libc::AF_INET6 as u8;
            expected[8..24].copy_from_slice(&number.to_be_bytes());
            assert_eq!(ip_address(std::net::Ipv6Addr::from(number).into()), expected);
        }
    }

    #[test]
    fn routing_sockets_are_closed_on_exec() {
        use std::os::fd::AsRawFd;
        let socket = Socket::new(Domain::from(libc::PF_ROUTE), Type::RAW, None).unwrap();
        assert_ne!(unsafe { libc::fcntl(socket.as_raw_fd(), libc::F_GETFD) } & libc::FD_CLOEXEC, 0);
    }

    #[test]
    fn gateway_host_message_binds_the_original_interface_explicitly() {
        for (destination, gateway) in [("198.51.100.10", "192.0.2.1"), ("2001:db8::10", "fe80::1")] {
            let route = EndpointRoute {
                destination: destination.parse().unwrap(),
                gateway: ip_address(gateway.parse().unwrap()),
                via_gateway: true,
                interface: 7,
                source: Some(ip_address(if destination.contains(':') { "2001:db8::2" } else { "192.0.2.2" }.parse().unwrap())),
                mtu: 1400,
                hopcount: 3,
            };
            for operation in [libc::RTM_ADD, libc::RTM_DELETE] {
                let bytes = route.message(operation).unwrap();
                let header = header(&bytes).unwrap();
                assert_eq!(header.rtm_index, 7);
                assert_ne!(header.rtm_addrs & libc::RTA_IFP, 0);
                assert_ne!(header.rtm_flags & libc::RTF_GATEWAY, 0);
                assert_eq!(header.rtm_flags & libc::RTF_IFSCOPE, 0); // exception must serve unbound app sockets
                let addresses = addresses(&bytes, &header).unwrap();
                assert_eq!(addresses[libc::RTAX_IFP as usize], Some(link_address(7)));
                assert_eq!(addresses[libc::RTAX_GATEWAY as usize], Some(route.gateway.clone()));
                assert_eq!(addresses[libc::RTAX_IFA as usize], route.source);
            }
        }
    }

    #[test]
    fn loopback_lookup_preserves_source_and_serializes_as_a_direct_route() {
        for ip in ["127.0.0.1", "::1"] {
            let route = EndpointRoute::lookup(ip.parse().unwrap()).unwrap();
            assert_eq!(route.interface as u32, unsafe { libc::if_nametoindex(c"lo0".as_ptr()) });
            assert_eq!(route.source, Some(ip_address(ip.parse().unwrap())));
            assert!(!route.via_gateway);
            for operation in [libc::RTM_ADD, libc::RTM_DELETE] {
                let bytes = route.message(operation).unwrap();
                assert_eq!(header(&bytes).unwrap().rtm_flags & libc::RTF_GATEWAY, 0);
            }
        }
    }
}

#[test]
fn host_routes_match_the_previous_wire_format_for_both_families() {
    for (destination, gateway, source) in [("198.51.100.19", "192.0.2.1", "192.0.2.2"), ("2001:db8::19", "fe80::1", "2001:db8::2")] {
        let destination: IpAddr = destination.parse().unwrap();
        for interface in [1, 7, u16::MAX] {
            for via_gateway in [false, true] {
                for with_source in [false, true] {
                    for (mtu, hopcount) in [(0, 0), (1400, 3), (u32::MAX, u32::MAX)] {
                        let expected = EndpointRoute {
                            destination,
                            interface,
                            gateway: ip_address(gateway.parse().unwrap()),
                            via_gateway,
                            source: with_source.then(|| ip_address(source.parse().unwrap())),
                            mtu,
                            hopcount,
                        };
                        let mut reply = expected.message(libc::RTM_ADD).unwrap();
                        // Route lookup can return scoped, dynamic routes with additional metrics.
                        let flags = header(&reply).unwrap().rtm_flags | libc::RTF_IFSCOPE | libc::RTF_WASCLONED;
                        field!(reply, rtm_flags, flags);
                        field!(reply, rtm_rmx.rmx_rtt, 456u32);
                        field!(reply, rtm_rmx.rmx_expire, 123u32);
                        let route = super::RouteMessage::host_route(destination, &reply).unwrap();
                        for operation in [libc::RTM_ADD, libc::RTM_DELETE] {
                            let expected = restore_message(&expected.message(libc::RTM_ADD).unwrap(), operation).unwrap();
                            assert_eq!(route.message(operation).unwrap(), expected);
                        }
                    }
                }
            }
        }
    }
}

#[test]
fn endpoint_queries_and_native_addresses_match_the_previous_wire_format() {
    for destination in ["0.0.0.0", "127.0.0.1", "255.255.255.255", "::", "::1", "fe80::1", "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"] {
        let destination: IpAddr = destination.parse().unwrap();
        assert_eq!(super::ip_address(destination), ip_address(destination));
        let flags = libc::RTF_UP | libc::RTF_HOST | libc::RTF_STATIC;
        let current =
            super::RouteMessage::new(0, flags, &[(libc::RTAX_DST, super::ip_address(destination)), (libc::RTAX_IFP, super::link_address(0))])
                .unwrap();
        let previous = EndpointRoute { destination, interface: 0, gateway: vec![], via_gateway: false, source: None, mtu: 0, hopcount: 0 };
        assert_eq!(current.message(libc::RTM_GET).unwrap(), previous.message(libc::RTM_GET).unwrap());
    }
}

#[test]
fn unreachable_endpoint_routes_are_rejected() {
    let destination = "198.51.100.19".parse().unwrap();
    for (interface, extra_flags) in [(0, 0), (2, libc::RTF_REJECT), (2, libc::RTF_BLACKHOLE)] {
        let reply = super::RouteMessage::new(interface, libc::RTF_UP | extra_flags, &[(libc::RTAX_DST, ip_address(destination))]).unwrap();
        assert_eq!(super::RouteMessage::host_route(destination, &reply.0).unwrap_err().to_string(), "endpoint route is not reachable");
    }
}

#[test]
fn non_ip_and_missing_gateways_bind_the_selected_interface() {
    let destination = "198.51.100.19".parse().unwrap();
    for gateway in [None, Some(link_address(9)), Some(vec![4, libc::AF_UNSPEC as u8, 0, 0])] {
        let mut attrs = vec![(libc::RTAX_DST, ip_address(destination))];
        if let Some(gateway) = gateway {
            attrs.push((libc::RTAX_GATEWAY, gateway));
        }
        let reply = super::RouteMessage::new(7, libc::RTF_UP, &attrs).unwrap();
        let host = super::RouteMessage::host_route(destination, &reply.0).unwrap();
        let bytes = host.message(libc::RTM_ADD).unwrap();
        let head = header(&bytes).unwrap();
        let attrs = addresses(&bytes, &head).unwrap();
        assert_eq!(attrs[libc::RTAX_GATEWAY as usize], Some(link_address(7)));
        assert_eq!(attrs[libc::RTAX_IFP as usize], Some(link_address(7)));
        assert_eq!(head.rtm_flags & libc::RTF_GATEWAY, 0);
    }
}

#[test]
fn read_only_invalid_queries_keep_the_native_error_code() {
    // GET without a destination can only query the routing socket. It cannot add or remove a route.
    let query = super::RouteMessage::new(0, libc::RTF_UP, &[]).unwrap().message(libc::RTM_GET).unwrap();
    assert_eq!(header(&query).unwrap().rtm_type as i32, libc::RTM_GET);
    let expected = exchange(query.clone()).unwrap_err();
    let actual = super::exchange(query).unwrap_err();
    let actual = actual.downcast_ref::<io::Error>().expect("native routing error must remain available");
    assert!(expected.raw_os_error().is_some());
    assert_eq!(actual.raw_os_error(), expected.raw_os_error());
    assert_eq!(actual.kind(), expected.kind());
    assert_eq!(actual.to_string(), expected.to_string());
}

#[test]
fn malformed_routing_messages_keep_the_previous_diagnostics() {
    for length in 0..size_of::<libc::rt_msghdr>() {
        let bytes = vec![0; length];
        assert_eq!(
            super::header(&bytes).map(|_| ()).map_err(|error| error.to_string()),
            header(&bytes).map(|_| ()).map_err(|error| error.to_string())
        );
    }
    for address_bits in [libc::RTA_DST, libc::RTA_DST | libc::RTA_GATEWAY, libc::RTA_IFA, 255] {
        for length in 0..32 {
            let mut bytes = vec![0; size_of::<libc::rt_msghdr>() + length];
            field!(bytes, rtm_addrs, address_bits);
            if length > 0 {
                bytes[size_of::<libc::rt_msghdr>()] = 16;
            }
            let head = header(&bytes).unwrap();
            assert_eq!(
                super::addresses(&bytes, &head).map_err(|error| error.to_string()),
                addresses(&bytes, &head).map_err(|error| error.to_string())
            );
        }
    }
}

#[test]
fn every_address_bitmap_preserves_slots_padding_and_truncation_errors() {
    let count = libc::RTAX_MAX as usize;
    for bitmap in 0..1i32 << count {
        for length in [0usize, 1, 2, 3, 4, 5, 16, 28] {
            let mut attrs = Vec::new();
            let mut expected: [Option<Vec<u8>>; libc::RTAX_MAX as usize] = std::array::from_fn(|_| None);
            for (index, slot) in expected.iter_mut().enumerate() {
                if bitmap & (1 << index) != 0 {
                    let mut address = vec![32 + index as u8; length];
                    if let Some(first) = address.first_mut() {
                        *first = length as u8;
                    }
                    *slot = Some(address.clone());
                    attrs.push((index as i32, address));
                }
            }
            let message = super::RouteMessage::new(7, libc::RTF_UP, &attrs).unwrap();
            let head = super::header(&message.0).unwrap();
            assert_eq!(head.rtm_addrs, bitmap);
            assert_eq!(super::addresses(&message.0, &head).unwrap(), expected);
            let mut extra_bits = head;
            extra_bits.rtm_addrs |= !((1 << count) - 1);
            assert_eq!(super::addresses(&message.0, &extra_bits).unwrap(), expected);
            for end in size_of::<libc::rt_msghdr>()..message.0.len() {
                let bytes = &message.0[..end];
                let actual = super::addresses(bytes, &head).map_err(|error| error.to_string());
                assert!(actual.is_err(), "bitmap={bitmap} length={length} end={end}");
                assert_eq!(actual, addresses(bytes, &head).map_err(|error| error.to_string()));
            }
        }
    }
}
