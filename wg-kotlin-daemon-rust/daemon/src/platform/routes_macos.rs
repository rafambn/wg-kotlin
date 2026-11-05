use super::EndpointRoute;
use super::endpoint::{addresses, append, exchange, field, header, ip_address, link_address};
use route_manager::Route;
use std::io;
use std::mem::{offset_of, size_of};

pub(super) struct SessionRoutes {
    changes: Vec<RouteChange>,
}

struct RouteChange {
    key: RouteKey,
    route: Snapshot,
    previous: Option<Snapshot>,
    add_attempted: bool,
}

impl SessionRoutes {
    pub fn new() -> Self {
        Self { changes: Vec::new() }
    }

    pub fn install(&mut self, vpn: &[Route], endpoints: &[EndpointRoute]) -> io::Result<()> {
        let requests = endpoints.iter().map(|route| route.message(libc::RTM_ADD).map(Snapshot)).chain(vpn.iter().map(tun_route));
        for request in requests {
            self.add(request?, &mut SystemRoutes)?;
        }
        Ok(())
    }

    fn add(&mut self, route: Snapshot, table: &mut impl RouteTable) -> io::Result<()> {
        let key = route.key()?;
        if self.changes.iter().any(|change| change.key == key) {
            return Ok(());
        }
        let previous = table.find(&key)?;
        if let Some(previous) = &previous {
            previous.message(libc::RTM_ADD)?;
        }
        // Record before deleting: an error or timeout must retain the backup.
        self.changes.push(RouteChange { key, route, previous, add_attempted: false });
        let change = self.changes.last_mut().unwrap();
        if let Some(previous) = &change.previous {
            table.request(previous, libc::RTM_DELETE)?;
        }
        change.add_attempted = true;
        table.request(&change.route, libc::RTM_ADD)
    }

    pub fn restore(&mut self) -> Result<(), String> {
        self.restore_with(&mut SystemRoutes)
    }

    fn restore_with(&mut self, table: &mut impl RouteTable) -> Result<(), String> {
        let mut errors = Vec::new();
        for change in std::mem::take(&mut self.changes).into_iter().rev() {
            if let Err(error) = change.restore(table) {
                errors.push(error.to_string());
            }
        }
        if errors.is_empty() { Ok(()) } else { Err(errors.join("; ")) }
    }
}

impl RouteChange {
    fn restore(self, table: &mut impl RouteTable) -> io::Result<()> {
        if let Some(current) = table.find(&self.key)? {
            if !self.add_attempted {
                return Ok(()); // deletion failed; the existing entry is untouched
            }
            if !current.same_path(&self.route)? {
                return Err(io::Error::other("route changed outside its session; leaving it untouched"));
            }
            match table.request(&current, libc::RTM_DELETE) {
                Ok(()) => {}
                Err(error) if error.raw_os_error() == Some(libc::ESRCH) => {}
                Err(error) => return Err(error),
            }
        }
        if let Some(previous) = self.previous {
            table.request(&previous, libc::RTM_ADD)?;
        }
        Ok(())
    }
}

trait RouteTable {
    fn find(&mut self, key: &RouteKey) -> io::Result<Option<Snapshot>>;
    fn request(&mut self, route: &Snapshot, operation: i32) -> io::Result<()>;
}

struct SystemRoutes;

impl RouteTable for SystemRoutes {
    fn find(&mut self, key: &RouteKey) -> io::Result<Option<Snapshot>> {
        // RTM_GET can prefer a scoped route even for an unscoped query. A dump
        // lets us match the exact prefix AND scope that ADD/DELETE will modify.
        for route in dump_routes()? {
            if route.key()? == *key {
                return Ok(Some(route));
            }
        }
        Ok(None)
    }

    fn request(&mut self, route: &Snapshot, operation: i32) -> io::Result<()> {
        exchange(route.message(operation)?).map(|_| ())
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct RouteKey {
    family: u8,
    destination: Vec<u8>,
    mask: Vec<u8>,
    host: bool,
    scope: u16,
}

#[derive(Clone, Debug)]
struct Snapshot(Vec<u8>);

impl Snapshot {
    fn key(&self) -> io::Result<RouteKey> {
        let head = header(&self.0)?;
        let attrs = addresses(&self.0, &head)?;
        let dst = attrs[libc::RTAX_DST as usize].as_deref().ok_or_else(|| io::Error::other("route has no destination"))?;
        let family = *dst.get(1).ok_or_else(|| io::Error::other("invalid route destination"))?;
        let mut destination = address_bytes(dst, family)?;
        let host = head.rtm_flags & libc::RTF_HOST != 0;
        let mask =
            if host { vec![255; destination.len()] } else { address_bytes(attrs[libc::RTAX_NETMASK as usize].as_deref().unwrap_or(&[]), family)? };
        for (byte, mask) in destination.iter_mut().zip(&mask) {
            *byte &= mask;
        }
        Ok(RouteKey { family, destination, mask, host, scope: if head.rtm_flags & libc::RTF_IFSCOPE != 0 { head.rtm_index } else { 0 } })
    }

    fn same_path(&self, other: &Self) -> io::Result<bool> {
        let left = header(&self.0)?;
        let right = header(&other.0)?;
        let left_attrs = addresses(&self.0, &left)?;
        let right_attrs = addresses(&other.0, &right)?;
        let routing_flags = libc::RTF_GATEWAY | libc::RTF_REJECT | libc::RTF_BLACKHOLE;
        if self.key()? != other.key()? || left.rtm_index != right.rtm_index || left.rtm_flags & routing_flags != right.rtm_flags & routing_flags {
            return Ok(false);
        }
        let gateway_equal = match (&left_attrs[libc::RTAX_GATEWAY as usize], &right_attrs[libc::RTAX_GATEWAY as usize]) {
            (Some(a), Some(b)) if a.get(1) == Some(&(libc::AF_LINK as u8)) && b.get(1) == Some(&(libc::AF_LINK as u8)) => true,
            (Some(a), Some(b)) => same_address(a, b)?,
            (a, b) => a == b,
        };
        let source = &right_attrs[libc::RTAX_IFA as usize];
        let source_equal = match (&left_attrs[libc::RTAX_IFA as usize], source) {
            (_, None) => true,
            (Some(a), Some(b)) => same_address(a, b)?,
            _ => false,
        };
        Ok(gateway_equal && source_equal)
    }

    fn message(&self, operation: i32) -> io::Result<Vec<u8>> {
        let head = header(&self.0)?;
        if head.rtm_index == 0 {
            return Err(io::Error::other("route has no interface to restore"));
        }
        if head.rtm_flags & libc::RTF_PROXY != 0 {
            return Err(io::Error::other("cannot safely replace a kernel proxy route"));
        }
        let mut attrs = addresses(&self.0, &head)?;
        attrs[libc::RTAX_IFP as usize] = Some(link_address(head.rtm_index));
        let mut bytes = self.0[..size_of::<libc::rt_msghdr>()].to_vec();
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
}

fn address_bytes(address: &[u8], family: u8) -> io::Result<Vec<u8>> {
    let (offset, length) = match i32::from(family) {
        libc::AF_INET => (4, 4),
        libc::AF_INET6 => (8, 16),
        _ => return Err(io::Error::other("unsupported routing address family")),
    };
    let mut bytes = vec![0; length];
    for (index, byte) in bytes.iter_mut().enumerate() {
        *byte = address.get(offset + index).copied().unwrap_or(0);
    }
    // XNU can embed a link-local zone in the address or sockaddr scope field.
    // The route's explicit interface/scope is compared separately.
    if i32::from(family) == libc::AF_INET6 && bytes[0] == 0xfe && bytes[1] & 0xc0 == 0x80 {
        bytes[2..4].fill(0);
    }
    Ok(bytes)
}

fn same_address(a: &[u8], b: &[u8]) -> io::Result<bool> {
    match (a.get(1), b.get(1)) {
        (Some(a_family), Some(b_family)) if a_family == b_family => Ok(address_bytes(a, *a_family)? == address_bytes(b, *b_family)?),
        _ => Ok(false),
    }
}

fn tun_route(route: &Route) -> io::Result<Snapshot> {
    let name = route.if_name().ok_or_else(|| io::Error::other("TUN route has no interface"))?;
    let name = std::ffi::CString::new(name.as_str())?;
    let index = unsafe { libc::if_nametoindex(name.as_ptr()) };
    if index == 0 {
        return Err(io::Error::last_os_error());
    }
    let index = u16::try_from(index).map_err(io::Error::other)?;
    let mask = route.mask();
    let destination = match (route.destination(), mask) {
        (std::net::IpAddr::V4(ip), std::net::IpAddr::V4(mask)) => std::net::IpAddr::V4((u32::from(ip) & u32::from(mask)).into()),
        (std::net::IpAddr::V6(ip), std::net::IpAddr::V6(mask)) => std::net::IpAddr::V6((u128::from(ip) & u128::from(mask)).into()),
        _ => return Err(io::Error::other("route address family mismatch")),
    };
    let mut bytes = vec![0; size_of::<libc::rt_msghdr>()];
    append(&mut bytes, &ip_address(destination));
    append(&mut bytes, &link_address(index));
    append(&mut bytes, &ip_address(mask));
    field!(bytes, rtm_msglen, u16::try_from(bytes.len()).map_err(io::Error::other)?);
    field!(bytes, rtm_version, libc::RTM_VERSION as u8);
    field!(bytes, rtm_index, index);
    field!(bytes, rtm_addrs, libc::RTA_DST | libc::RTA_GATEWAY | libc::RTA_NETMASK);
    let host = if route.prefix() == if destination.is_ipv4() { 32 } else { 128 } { libc::RTF_HOST } else { 0 };
    field!(bytes, rtm_flags, libc::RTF_UP | libc::RTF_STATIC | host);
    Ok(Snapshot(bytes))
}

fn dump_routes() -> io::Result<Vec<Snapshot>> {
    for _ in 0..3 {
        let mut mib = [libc::CTL_NET, libc::PF_ROUTE, 0, 0, libc::NET_RT_DUMP, 0];
        let mut size = 0usize;
        if unsafe { libc::sysctl(mib.as_mut_ptr(), 6, std::ptr::null_mut(), &mut size, std::ptr::null_mut(), 0) } < 0 {
            return Err(io::Error::last_os_error());
        }
        let mut bytes = vec![0u8; size];
        if unsafe { libc::sysctl(mib.as_mut_ptr(), 6, bytes.as_mut_ptr().cast(), &mut size, std::ptr::null_mut(), 0) } < 0 {
            let error = io::Error::last_os_error();
            if error.raw_os_error() == Some(libc::ENOMEM) {
                continue;
            }
            return Err(error);
        }
        bytes.truncate(size);
        let mut routes = Vec::new();
        let mut offset = 0;
        while offset < bytes.len() {
            let head = header(&bytes[offset..])?;
            let length = usize::from(head.rtm_msglen);
            if length < size_of::<libc::rt_msghdr>() || length > bytes.len() - offset {
                return Err(io::Error::other("invalid route dump message"));
            }
            if head.rtm_version != libc::RTM_VERSION as u8 {
                return Err(io::Error::other("unexpected route dump version"));
            }
            let message = &bytes[offset..offset + length];
            let attrs = addresses(message, &head)?;
            if matches!(
                attrs[libc::RTAX_DST as usize].as_ref().and_then(|dst| dst.get(1)).copied().map(i32::from),
                Some(libc::AF_INET | libc::AF_INET6)
            ) {
                routes.push(Snapshot(message.to_vec()));
            }
            offset += length;
        }
        return Ok(routes);
    }
    Err(io::Error::other("routing table kept changing during snapshot"))
}

#[cfg(test)]
#[path = "routes_macos_tests.rs"]
mod tests;
