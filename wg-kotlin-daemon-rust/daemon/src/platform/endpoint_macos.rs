use std::io::{self, Read, Write};
use std::mem::{offset_of, size_of};
use std::net::IpAddr;
use std::os::fd::FromRawFd;
use std::os::unix::net::UnixStream;
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
    ($bytes:expr, $name:ident, $value:expr) => {{
        let value = ($value).to_ne_bytes();
        let start = offset_of!(libc::rt_msghdr, $name);
        $bytes[start..start + value.len()].copy_from_slice(&value);
    }};
}

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

    pub fn replace(&self) -> io::Result<()> {
        let _ = self.delete();
        exchange(self.message(libc::RTM_ADD)?).map(|_| ())
    }

    pub fn delete(&self) -> io::Result<()> {
        exchange(self.message(libc::RTM_DELETE)?).map(|_| ())
    }

    fn message(&self, operation: i32) -> io::Result<Vec<u8>> {
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
            let mut inits = 0u32;
            for (offset, value, bit) in [
                (offset_of!(libc::rt_metrics, rmx_mtu), self.mtu, libc::RTV_MTU),
                (offset_of!(libc::rt_metrics, rmx_hopcount), self.hopcount, libc::RTV_HOPCOUNT),
            ] {
                if value != 0 {
                    let start = offset_of!(libc::rt_msghdr, rtm_rmx) + offset;
                    message[start..start + 4].copy_from_slice(&value.to_ne_bytes());
                    inits |= bit as u32;
                }
            }
            field!(message, rtm_inits, inits);
        }
        message.extend(payload);
        Ok(message)
    }
}

fn ip_address(ip: IpAddr) -> Vec<u8> {
    match ip {
        IpAddr::V4(ip) => {
            let mut bytes = vec![0; size_of::<libc::sockaddr_in>()];
            bytes[0] = bytes.len() as u8;
            bytes[1] = libc::AF_INET as u8;
            bytes[4..8].copy_from_slice(&ip.octets());
            bytes
        }
        IpAddr::V6(ip) => {
            let mut bytes = vec![0; size_of::<libc::sockaddr_in6>()];
            bytes[0] = bytes.len() as u8;
            bytes[1] = libc::AF_INET6 as u8;
            bytes[8..24].copy_from_slice(&ip.octets());
            bytes
        }
    }
}

fn link_address(index: u16) -> Vec<u8> {
    let mut bytes = vec![0; size_of::<libc::sockaddr_dl>()];
    bytes[0] = bytes.len() as u8;
    bytes[1] = libc::AF_LINK as u8;
    bytes[2..4].copy_from_slice(&index.to_ne_bytes());
    bytes
}

fn append(payload: &mut Vec<u8>, address: &[u8]) {
    payload.extend_from_slice(address);
    payload.resize(payload.len().next_multiple_of(4), 0);
}

fn header(bytes: &[u8]) -> io::Result<libc::rt_msghdr> {
    if bytes.len() < size_of::<libc::rt_msghdr>() {
        return Err(io::Error::other("truncated routing message"));
    }
    // Routing socket data is byte-aligned; all header fields are integer types.
    Ok(unsafe { std::ptr::read_unaligned(bytes.as_ptr().cast()) })
}

fn addresses(bytes: &[u8], header: &libc::rt_msghdr) -> io::Result<[Option<Vec<u8>>; libc::RTAX_MAX as usize]> {
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

fn exchange(mut message: Vec<u8>) -> io::Result<Vec<u8>> {
    let operation = message[offset_of!(libc::rt_msghdr, rtm_type)];
    let pid = unsafe { libc::getpid() };
    let sequence = SEQUENCE.fetch_add(1, Ordering::Relaxed);
    field!(message, rtm_pid, pid);
    field!(message, rtm_seq, sequence);
    let fd = unsafe { libc::socket(libc::PF_ROUTE, libc::SOCK_RAW, libc::AF_UNSPEC) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    let mut socket = unsafe { UnixStream::from_raw_fd(fd) };
    if unsafe { libc::fcntl(fd, libc::F_SETFD, libc::FD_CLOEXEC) } < 0 {
        return Err(io::Error::last_os_error());
    }
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

#[cfg(test)]
mod tests {
    use super::*;

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
