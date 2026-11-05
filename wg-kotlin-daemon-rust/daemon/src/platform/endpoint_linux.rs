use netlink_packet_core::{DefaultNla, NLM_F_ACK, NLM_F_CREATE, NLM_F_REPLACE, NLM_F_REQUEST, NetlinkHeader, NetlinkMessage, NetlinkPayload};
use netlink_packet_route::{
    AddressFamily, RouteNetlinkMessage,
    route::{RouteAddress, RouteAttribute, RouteFlags, RouteMessage, RouteNextHopFlags, RouteProtocol, RouteType},
};
use netlink_sys::{Socket, SocketAddr, protocols::NETLINK_ROUTE};
use std::{io, net::IpAddr, os::fd::AsRawFd};

#[derive(Clone, Debug)]
pub struct EndpointRoute(RouteMessage);

impl EndpointRoute {
    pub fn lookup(ip: IpAddr) -> io::Result<Self> {
        let mut query = RouteMessage::default();
        query.header.address_family = if ip.is_ipv4() { AddressFamily::Inet } else { AddressFamily::Inet6 };
        query.header.destination_prefix_length = if ip.is_ipv4() { 32 } else { 128 };
        // FIB_MATCH asks the kernel to apply policy rules and return the original
        // FIB entry, including onlink flags, rather than a lossy route dump/cache.
        query.header.flags = RouteFlags::FibMatch | RouteFlags::LookupTable;
        query.attributes = vec![
            RouteAttribute::Destination(address(ip)),
            RouteAttribute::Uid(unsafe { libc::getuid() }),
            RouteAttribute::Other(DefaultNla::new(27, vec![libc::IPPROTO_UDP as u8])),
        ]; // RTA_IP_PROTO
        let found = transact(RouteNetlinkMessage::GetRoute(query), NLM_F_REQUEST)?.ok_or_else(|| io::Error::other("no route to endpoint"))?;
        Self::from_route(ip, found)
    }

    fn from_route(ip: IpAddr, mut found: RouteMessage) -> io::Result<Self> {
        if found.header.kind != RouteType::Unicast && found.header.kind != RouteType::Local {
            return Err(io::Error::other("endpoint route is not reachable"));
        }
        found.header.destination_prefix_length = if ip.is_ipv4() { 32 } else { 128 };
        found.header.protocol = RouteProtocol::Static;
        // Status/cache/offload flags cannot be replayed as route creation flags.
        found.header.flags &= RouteFlags::Onlink | RouteFlags::Pervasive;
        for attr in &mut found.attributes {
            if let RouteAttribute::MultiPath(hops) = attr {
                // Dead nexthops are not eligible for the original route either.
                // Linkdown/offload status is recalculated by the kernel on add.
                hops.retain(|hop| !hop.flags.contains(RouteNextHopFlags::Dead));
                if hops.is_empty() {
                    return Err(io::Error::other("endpoint route has no live nexthops"));
                }
                for hop in hops {
                    hop.flags &= RouteNextHopFlags::Onlink | RouteNextHopFlags::Pervasive;
                }
            }
        }
        found.attributes.retain(|attr| {
            !matches!(
                attr,
                RouteAttribute::Destination(_)
                    | RouteAttribute::CacheInfo(_)
                    | RouteAttribute::Uid(_)
                    | RouteAttribute::Mark(_)
                    | RouteAttribute::Iif(_)
                    | RouteAttribute::Expires(_)
                    | RouteAttribute::MulticastExpires(_)
            )
        });
        found.attributes.push(RouteAttribute::Destination(address(ip)));
        Ok(Self(found))
    }

    pub fn replace(&self) -> io::Result<()> {
        transact(RouteNetlinkMessage::NewRoute(self.0.clone()), NLM_F_REQUEST | NLM_F_ACK | NLM_F_CREATE | NLM_F_REPLACE).map(|_| ())
    }

    pub fn delete(&self) -> io::Result<()> {
        transact(RouteNetlinkMessage::DelRoute(self.0.clone()), NLM_F_REQUEST | NLM_F_ACK).map(|_| ())
    }
}

fn address(ip: IpAddr) -> RouteAddress {
    match ip {
        IpAddr::V4(ip) => RouteAddress::Inet(ip),
        IpAddr::V6(ip) => RouteAddress::Inet6(ip),
    }
}

fn transact(message: RouteNetlinkMessage, flags: u16) -> io::Result<Option<RouteMessage>> {
    let lookup = matches!(message, RouteNetlinkMessage::GetRoute(_));
    let mut socket = Socket::new(NETLINK_ROUTE)?;
    let port = socket.bind_auto()?.port_number();
    socket.connect(&SocketAddr::new(0, 0))?;
    let timeout = libc::timeval { tv_sec: 5, tv_usec: 0 };
    for option in [libc::SO_RCVTIMEO, libc::SO_SNDTIMEO] {
        if unsafe {
            libc::setsockopt(socket.as_raw_fd(), libc::SOL_SOCKET, option, &timeout as *const _ as *const _, std::mem::size_of_val(&timeout) as _)
        } != 0
        {
            return Err(io::Error::last_os_error());
        }
    }
    let mut header = NetlinkHeader::default();
    header.flags = flags;
    header.sequence_number = 1;
    header.port_number = port;
    let mut packet = NetlinkMessage::new(header, NetlinkPayload::InnerMessage(message));
    packet.finalize();
    let mut bytes = vec![0; packet.buffer_len()];
    packet.serialize(&mut bytes);
    socket.send(&bytes, 0)?;
    loop {
        let (bytes, sender) = socket.recv_from_full()?;
        if sender.port_number() != 0 {
            continue;
        }
        let mut offset = 0;
        while offset < bytes.len() {
            let reply = NetlinkMessage::<RouteNetlinkMessage>::deserialize(&bytes[offset..]).map_err(io::Error::other)?;
            let length = reply.header.length as usize;
            if length < 16 || length > bytes.len() - offset {
                return Err(io::Error::other("invalid netlink route reply"));
            }
            offset += (length + 3) & !3;
            if reply.header.sequence_number != 1 {
                continue;
            }
            match reply.payload {
                NetlinkPayload::InnerMessage(RouteNetlinkMessage::NewRoute(route)) if lookup => return Ok(Some(route)),
                NetlinkPayload::Error(error) => {
                    if error.code.is_some() {
                        return Err(error.to_io());
                    }
                    return Ok(None);
                }
                NetlinkPayload::Done(_) => return Ok(None),
                _ => {}
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use netlink_packet_route::route::RouteNextHop;

    #[test]
    fn host_exception_preserves_onlink_and_full_table_id() {
        let mut original = RouteMessage::default();
        original.header.kind = RouteType::Unicast;
        original.header.flags = RouteFlags::Onlink | RouteFlags::Cloned;
        original.attributes = vec![
            RouteAttribute::Table(1000),
            RouteAttribute::Oif(7),
            RouteAttribute::Gateway(address("192.0.2.1".parse().unwrap())),
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
        live.attributes = vec![RouteAttribute::Gateway(address("192.0.2.1".parse().unwrap()))];
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
}
