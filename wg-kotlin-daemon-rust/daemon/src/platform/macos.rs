use anyhow::{Context, Result, anyhow, bail, ensure};
use indexmap::{IndexMap, IndexSet, map::Entry};
use objc2_core_foundation::{CFArray, CFDictionary, CFRetained, CFString, CFType, Type as _};
use objc2_system_configuration::{SCDynamicStore, SCError, SCErrorString, kSCStatusNoKey};
use route_manager::Route;
use socket2::{Domain, SockAddr, Socket, Type};
use std::ffi::CStr;
use std::io::{self, Read, Write};
use std::mem::{offset_of, size_of};
use std::net::IpAddr;
use std::sync::{
    Arc, LazyLock, Mutex, MutexGuard,
    atomic::{AtomicI32, Ordering},
};
use std::time::Duration;
use sysctl::{Ctl, CtlValue, Sysctl, SysctlError};

pub const REQUIRED_BINARIES: &[&str] = &[];

pub(super) fn set_dns(interface: &str, servers: &[String], domains: &[String]) -> Result<()> {
    set_values(None, &dns_values(interface, servers, domains))
}

pub(super) fn clear_dns(interface: &str) -> Result<()> {
    remove_values(None, &resolver_keys(interface))
}

fn resolver_keys(interface: &str) -> [CFRetained<CFString>; 2] {
    ["/DNS", ""].map(|suffix| CFString::from_str(&format!("State:/Network/Interface/{interface}{suffix}")))
}

fn dns_values(interface: &str, servers: &[String], domains: &[String]) -> [(CFRetained<CFString>, CFRetained<CFDictionary>); 2] {
    let strings = |values: &[String]| CFArray::from_retained_objects(&values.iter().map(|value| CFString::from_str(value)).collect::<Vec<_>>());
    let dns = CFDictionary::from_slices(
        &[&*CFString::from_str("ServerAddresses"), &*CFString::from_str("SupplementalMatchDomains")],
        &[&*strings(servers), &*strings(domains)],
    );
    let root = CFDictionary::from_slices(&[&*CFString::from_str("UserDefinedName")], &[&*CFString::from_str(interface)]);
    let [dns_key, root_key] = resolver_keys(interface);
    [(dns_key, dns.as_opaque().retain()), (root_key, root.as_opaque().retain())]
}

fn copy_value(store: Option<&SCDynamicStore>, key: &CFString) -> Result<Option<CFRetained<CFType>>> {
    let value = SCDynamicStore::value(store, key);
    ensure!(value.is_some() || SCError() == kSCStatusNoKey as i32, store_error(&format!("read {key}"), SCError()));
    Ok(value)
}

fn set_values(store: Option<&SCDynamicStore>, values: &[(CFRetained<CFString>, CFRetained<CFDictionary>)]) -> Result<()> {
    let (keys, objects): (Vec<_>, Vec<_>) = values.iter().map(|(key, value)| (&**key, &**value)).unzip();
    let dictionary = CFDictionary::from_slices(&keys, &objects);
    ensure!(unsafe { SCDynamicStore::set_multiple(store, Some(dictionary.as_opaque()), None, None) }, store_error("set DNS entries", SCError()));
    for (key, expected) in values {
        ensure!(copy_value(store, key)?.as_ref() == Some(&CFType::retain(expected)), "DNS store value did not match after setting {key}");
    }
    Ok(())
}

fn remove_values(store: Option<&SCDynamicStore>, keys: &[CFRetained<CFString>]) -> Result<()> {
    super::cleanup_results(keys.iter().map(|key| {
        if copy_value(store, key)?.is_some() {
            // SCError reads thread-local state; formatting this CFString key does not change it.
            ensure!(SCDynamicStore::remove_value(store, key) || SCError() == kSCStatusNoKey as i32, store_error(&format!("remove {key}"), SCError()));
            ensure!(copy_value(store, key)?.is_none(), "DNS store value still exists after removing {key}");
        }
        Ok(())
    }))
}

fn store_error(operation: &str, status: i32) -> String {
    let message = unsafe { CStr::from_ptr(SCErrorString(status).as_ptr()) }.to_string_lossy();
    format!("{operation}: {message} (SystemConfiguration {status})")
}

static SEQUENCE: AtomicI32 = AtomicI32::new(1);
const HOST_FLAGS: i32 = libc::RTF_UP | libc::RTF_HOST | libc::RTF_STATIC;
// Darwin's eight RTV_* flags (0x01 through 0x80) initialize all configurable metrics.
const ALL_ROUTE_METRICS: u32 = 0xff;

#[derive(Clone, Debug)]
pub struct RouteMessage(Vec<u8>);
pub use RouteMessage as EndpointRoute;

macro_rules! field {
    ($bytes:expr, $($name:ident).+, $value:expr) => {{
        let value = ($value).to_ne_bytes();
        let start = offset_of!(libc::rt_msghdr, $($name).+);
        $bytes[start..start + value.len()].copy_from_slice(&value);
    }};
}

impl RouteMessage {
    pub fn lookup(destination: IpAddr) -> Result<Self> {
        let _registry = route_registry()?;
        // Request IFP so XNU also returns the selected source, without restricting lookup.
        let query = Self::new(0, HOST_FLAGS, &[(libc::RTAX_DST, ip_address(destination)), (libc::RTAX_IFP, link_address(0))])?;
        let reply = exchange(query.message(libc::RTM_GET)?)?;
        Self::host_route(destination, &reply)
    }

    fn host_route(destination: IpAddr, reply: &[u8]) -> Result<Self> {
        let head = header(reply)?;
        ensure!(head.rtm_index != 0 && head.rtm_flags & (libc::RTF_REJECT | libc::RTF_BLACKHOLE) == 0, "endpoint route is not reachable");
        let mut selected = addresses(reply, &head)?;
        let gateway = match selected[libc::RTAX_GATEWAY as usize].take() {
            Some(gateway) if matches!(gateway.get(1).copied().map(i32::from), Some(libc::AF_INET | libc::AF_INET6)) => gateway,
            _ => link_address(head.rtm_index),
        };
        let mask = if destination.is_ipv4() { IpAddr::V4(std::net::Ipv4Addr::BROADCAST) } else { IpAddr::V6(std::net::Ipv6Addr::from(u128::MAX)) };
        let mut attrs = vec![(libc::RTAX_DST, ip_address(destination)), (libc::RTAX_GATEWAY, gateway), (libc::RTAX_NETMASK, ip_address(mask))];
        attrs.extend(selected[libc::RTAX_IFA as usize].take().map(|source| (libc::RTAX_IFA, source)));
        let mut route = Self::new(head.rtm_index, HOST_FLAGS | (head.rtm_flags & libc::RTF_GATEWAY), &attrs)?;
        // Pin the host route using only the path metrics previously applied to endpoints.
        field!(route.0, rtm_rmx.rmx_mtu, head.rtm_rmx.rmx_mtu);
        field!(route.0, rtm_rmx.rmx_hopcount, head.rtm_rmx.rmx_hopcount);
        Ok(route)
    }

    fn new(index: u16, flags: i32, attrs: &[(i32, Vec<u8>)]) -> Result<Self> {
        let mut bytes = vec![0; size_of::<libc::rt_msghdr>()];
        field!(bytes, rtm_version, libc::RTM_VERSION as u8);
        field!(bytes, rtm_index, index);
        field!(bytes, rtm_flags, flags);
        Ok(Self(encode_addresses(bytes, attrs.iter().map(|(kind, value)| (*kind, value)))?))
    }

    fn message(&self, operation: i32) -> Result<Vec<u8>> {
        let head = header(&self.0)?;
        ensure!(operation == libc::RTM_GET || head.rtm_index != 0, "route has no interface to restore");
        ensure!(head.rtm_flags & libc::RTF_PROXY == 0, "cannot safely replace a kernel proxy route");
        let mut attrs = addresses(&self.0, &head)?;
        // Bind ADD/DELETE to the original interface, even if a new TUN now routes its gateway.
        attrs[libc::RTAX_IFP as usize] = Some(link_address(head.rtm_index));
        let mut bytes = self.0[..size_of::<libc::rt_msghdr>()].to_vec();
        field!(bytes, rtm_type, operation as u8);
        field!(bytes, rtm_errno, 0i32);
        field!(bytes, rtm_flags, head.rtm_flags & !(libc::RTF_DONE | libc::RTF_IFREF | libc::RTF_CONDEMNED | libc::RTF_DEAD));
        field!(bytes, rtm_inits, if operation == libc::RTM_ADD { ALL_ROUTE_METRICS } else { 0u32 });
        encode_addresses(bytes, attrs.iter().enumerate().filter_map(|(index, value)| value.as_ref().map(|value| (index as i32, value))))
    }

    // The lookup has already checked the route key.
    fn same_egress(&self, other: &Self) -> Result<bool> {
        let (left, right) = (header(&self.0)?, header(&other.0)?);
        let (left_attrs, right_attrs) = (addresses(&self.0, &left)?, addresses(&other.0, &right)?);
        let routing_flags = libc::RTF_GATEWAY | libc::RTF_REJECT | libc::RTF_BLACKHOLE;
        if left.rtm_index != right.rtm_index || left.rtm_flags & routing_flags != right.rtm_flags & routing_flags {
            return Ok(false);
        }
        let gateway_equal = match (&left_attrs[libc::RTAX_GATEWAY as usize], &right_attrs[libc::RTAX_GATEWAY as usize]) {
            (Some(a), Some(b)) if a.get(1) == Some(&(libc::AF_LINK as u8)) && b.get(1) == Some(&(libc::AF_LINK as u8)) => true,
            (Some(a), Some(b)) => same_address(a, b)?,
            (a, b) => a == b,
        };
        let source_equal = match (&left_attrs[libc::RTAX_IFA as usize], &right_attrs[libc::RTAX_IFA as usize]) {
            (_, None) => true,
            (Some(a), Some(b)) => same_address(a, b)?,
            _ => false,
        };
        Ok(gateway_equal && source_equal)
    }
}

fn encode_addresses<'a>(mut bytes: Vec<u8>, attrs: impl Iterator<Item = (i32, &'a Vec<u8>)>) -> Result<Vec<u8>> {
    let mut addrs = 0i32;
    for (kind, value) in attrs {
        addrs |= 1 << kind;
        let start = bytes.len();
        bytes.extend_from_slice(value);
        bytes.resize(start + value.len().max(4).next_multiple_of(4), 0);
    }
    field!(bytes, rtm_msglen, u16::try_from(bytes.len())?);
    field!(bytes, rtm_addrs, addrs);
    Ok(bytes)
}

fn ip_address(ip: IpAddr) -> Vec<u8> {
    let address = SockAddr::from(std::net::SocketAddr::new(ip, 0));
    // SockAddr initializes its sockaddr storage; copy only its declared length.
    unsafe { std::slice::from_raw_parts(address.as_ptr().cast(), address.len() as usize) }.to_vec()
}

fn link_address(index: u16) -> Vec<u8> {
    let mut bytes = vec![0; size_of::<libc::sockaddr_dl>()];
    bytes[0] = bytes.len() as u8;
    bytes[1] = libc::AF_LINK as u8;
    bytes[2..4].copy_from_slice(&index.to_ne_bytes());
    bytes
}

fn header(bytes: &[u8]) -> Result<libc::rt_msghdr> {
    ensure!(bytes.len() >= size_of::<libc::rt_msghdr>(), "truncated routing message");
    // Routing socket data is byte-aligned; all header fields are integer types.
    Ok(unsafe { std::ptr::read_unaligned(bytes.as_ptr().cast()) })
}

fn addresses(bytes: &[u8], header: &libc::rt_msghdr) -> Result<[Option<Vec<u8>>; libc::RTAX_MAX as usize]> {
    let mut result = std::array::from_fn(|_| None);
    let mut offset = size_of::<libc::rt_msghdr>();
    for (_, slot) in result.iter_mut().enumerate().filter(|(index, _)| header.rtm_addrs & (1 << index) != 0) {
        let length = usize::from(*bytes.get(offset).context("missing route address")?);
        let step = length.max(4).next_multiple_of(4);
        ensure!(offset + step <= bytes.len(), "truncated route address");
        *slot = Some(bytes[offset..offset + length].to_vec());
        offset += step;
    }
    Ok(result)
}

fn exchange(mut message: Vec<u8>) -> Result<Vec<u8>> {
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
        ensure!(!remaining.is_zero(), io::Error::new(io::ErrorKind::TimedOut, "routing reply timed out"));
        socket.set_read_timeout(Some(remaining))?;
        let count = socket.read(&mut buffer)?;
        ensure!(count != 0, io::Error::new(io::ErrorKind::UnexpectedEof, "routing socket closed"));
        // PF_ROUTE preserves records, so one read contains one routing message.
        ensure!(count >= 4, "truncated routing message prefix");
        let length = usize::from(u16::from_ne_bytes([buffer[0], buffer[1]]));
        ensure!(length >= 4 && length <= count, "invalid routing message length");
        // Interface/address notifications have different, often shorter
        // headers than rt_msghdr. They are not replies to this request.
        if buffer[2] != libc::RTM_VERSION as u8 || buffer[3] != operation {
            continue;
        }
        let reply = header(&buffer[..length])?;
        if reply.rtm_pid == pid && reply.rtm_seq == sequence {
            ensure!(reply.rtm_errno == 0, io::Error::from_raw_os_error(reply.rtm_errno));
            return Ok(buffer[..length].to_vec());
        }
    }
}

#[cfg(not(test))]
type RouteTable = SystemRoutes;

type RouteRegistry = IndexMap<RouteKey, RouteState>;
static ROUTES: LazyLock<Mutex<RouteRegistry>> = LazyLock::new(Mutex::default);

#[derive(Default)]
pub(super) struct SessionRoutes {
    // One identity across this session's keys, removed explicitly during cleanup.
    id: Arc<()>,
    keys: IndexSet<RouteKey>,
}

struct RouteOwner {
    id: Arc<()>,
    snapshot: Arc<RouteMessage>,
}

struct RouteState {
    original: Option<Arc<RouteMessage>>,
    // Keep snapshot identity when a request may have failed without changing the route.
    attempted: Option<Arc<RouteMessage>>,
    owners: Vec<RouteOwner>,
}

pub(super) fn recover_routes() -> Result<()> {
    route_registry().map(drop)
}

fn route_registry() -> Result<MutexGuard<'static, RouteRegistry>> {
    let mut state = ROUTES.lock().map_err(|_| anyhow!("route registry poisoned"))?;
    recover_orphans(&mut state, &mut SystemRoutes)?;
    Ok(state)
}

impl SessionRoutes {
    pub fn install(&mut self, vpn: &[Route], endpoints: &[RouteMessage]) -> Result<()> {
        let mut state = route_registry()?;
        let mut requests = endpoints.iter().cloned().map(Ok).chain(vpn.iter().map(tun_route));
        requests.try_for_each(|request| self.add(request?, &mut SystemRoutes, &mut state))
    }

    fn add(&mut self, route: RouteMessage, table: &mut RouteTable, state: &mut RouteRegistry) -> Result<()> {
        let key = route_key(&route.0)?;
        if self.keys.contains(&key) {
            return Ok(());
        }
        let current = table.find(&key)?;
        let change = match state.entry(key.clone()) {
            Entry::Occupied(entry) => entry.into_mut(),
            Entry::Vacant(entry) => {
                if let Some(original) = &current {
                    original.message(libc::RTM_ADD)?;
                }
                let original = current.clone().map(Arc::new);
                entry.insert(RouteState { attempted: original.clone(), original, owners: Vec::new() })
            }
        };
        // Register ownership before any request, including requests whose replies may be lost.
        self.keys.insert(key.clone());
        change.owners.push(RouteOwner { id: self.id.clone(), snapshot: Arc::new(route) });
        apply_route(&key, state, current, table, false)
    }

    pub fn restore(self) -> Result<()> {
        let mut state = ROUTES.lock().map_err(|_| anyhow!("route registry poisoned"))?;
        self.restore_with(&mut SystemRoutes, &mut state)
    }

    fn restore_with(self, table: &mut RouteTable, state: &mut RouteRegistry) -> Result<()> {
        super::cleanup_results(self.keys.into_iter().rev().map(|key| {
            let change = state.get_mut(&key).context("missing route ownership")?;
            let index = change.owners.iter().position(|owner| Arc::ptr_eq(&owner.id, &self.id)).context("missing route owner")?;
            change.owners.remove(index);
            if index != change.owners.len() {
                return Ok(()); // A later session still owns the installed route.
            }
            apply_route(&key, state, table.find(&key)?, table, true)
        }))
    }
}

fn recover_orphans(state: &mut RouteRegistry, table: &mut RouteTable) -> Result<()> {
    let keys: Vec<_> = state.iter().rev().filter(|(_, change)| change.owners.is_empty()).map(|(key, _)| key.clone()).collect();
    super::cleanup_results(keys.iter().map(|key| apply_route(key, state, table.find(key)?, table, true)))
}

fn apply_route(key: &RouteKey, state: &mut RouteRegistry, current: Option<RouteMessage>, table: &mut RouteTable, restoring: bool) -> Result<()> {
    let change = state.get_mut(key).context("missing route ownership")?;
    let current = current.map(Arc::new);
    if let Some(current) = &current
        && !change.attempted.as_ref().map_or(Ok(false), |known| current.same_egress(known))?
    {
        if change.owners.is_empty() {
            state.shift_remove(key); // An external owner superseded this history.
        }
        bail!("route changed outside its session; leaving it untouched");
    }
    let target = change.owners.last().map(|owner| &owner.snapshot).or(change.original.as_ref());
    let unchanged = current.is_some() && change.attempted.as_ref().zip(target).is_some_and(|(known, target)| Arc::ptr_eq(known, target));
    // Refresh only the owner whose snapshot was installed, not a pending or removed owner.
    if let (Some(current), Some(known)) = (&current, &change.attempted)
        && let Some(owner) = change.owners.iter_mut().find(|owner| Arc::ptr_eq(&owner.snapshot, known))
    {
        owner.snapshot = current.clone();
        change.attempted = Some(current.clone());
    }
    let target = change.owners.last().map(|owner| owner.snapshot.clone()).or_else(|| change.original.clone());
    if !unchanged {
        if let Some(current) = &current
            && let Err(error) = table.request(current, libc::RTM_DELETE)
        {
            ensure!(restoring && error.downcast_ref::<io::Error>().and_then(io::Error::raw_os_error) == Some(libc::ESRCH), error);
        }
        change.attempted = target.clone();
        if let Some(target) = target {
            table.request(&target, libc::RTM_ADD)?;
        }
    }
    if change.owners.is_empty() {
        state.shift_remove(key);
    }
    Ok(())
}

struct SystemRoutes;

impl SystemRoutes {
    /// Returns only a route with exactly this key.
    fn find(&mut self, key: &RouteKey) -> Result<Option<RouteMessage>> {
        // RTM_GET can prefer a scoped route even for an unscoped query. A dump
        // lets us match the exact prefix AND scope that ADD/DELETE will modify.
        let table = Ctl::Oid(vec![libc::CTL_NET, libc::PF_ROUTE, 0, 0, libc::NET_RT_DUMP, 0]);
        for _ in 0..3 {
            match table.value() {
                Ok(CtlValue::Node(bytes) | CtlValue::Struct(bytes)) => return find_in_snapshot(&bytes, key),
                Err(SysctlError::IoError(error)) if error.raw_os_error() == Some(libc::ENOMEM) => continue,
                Err(error) => return Err(error.into()),
                Ok(_) => bail!("unexpected routing table value type"),
            }
        }
        bail!("routing table kept changing during snapshot")
    }

    fn request(&mut self, route: &RouteMessage, operation: i32) -> Result<()> {
        exchange(route.message(operation)?).map(|_| ())
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
struct RouteKey {
    // The normalized address length already distinguishes IPv4 from IPv6.
    destination: Vec<u8>,
    // None identifies an RTF_HOST route, not a missing network mask.
    mask: Option<Vec<u8>>,
    scope: u16,
}

fn route_key(bytes: &[u8]) -> Result<RouteKey> {
    let head = header(bytes)?;
    let attrs = addresses(bytes, &head)?;
    let dst = attrs[libc::RTAX_DST as usize].as_deref().context("route has no destination")?;
    let family = *dst.get(1).context("invalid route destination")?;
    let destination = address_bytes(dst, family)?;
    let mask_address = attrs[libc::RTAX_NETMASK as usize].as_deref().unwrap_or(&[]);
    let mask = if head.rtm_flags & libc::RTF_HOST != 0 { None } else { Some(address_bytes(mask_address, family)?.collect::<Vec<_>>()) };
    let destination = match &mask {
        Some(mask) => destination.zip(mask).map(|(byte, mask)| byte & mask).collect(),
        None => destination.collect(),
    };
    Ok(RouteKey { destination, mask, scope: if head.rtm_flags & libc::RTF_IFSCOPE != 0 { head.rtm_index } else { 0 } })
}

fn address_bytes(address: &[u8], family: u8) -> Result<impl Iterator<Item = u8> + '_> {
    let (offset, length) = match i32::from(family) {
        libc::AF_INET => (4, 4),
        libc::AF_INET6 => (8, 16),
        _ => bail!("unsupported routing address family"),
    };
    let byte = move |index: usize| address.get(offset + index).copied().unwrap_or(0);
    // XNU can embed a link-local zone in the address or sockaddr scope field.
    // The route's explicit interface/scope is compared separately.
    let link_local = i32::from(family) == libc::AF_INET6 && byte(0) == 0xfe && byte(1) & 0xc0 == 0x80;
    Ok((0..length).map(move |index| if link_local && (2..4).contains(&index) { 0 } else { byte(index) }))
}

fn same_address(a: &[u8], b: &[u8]) -> Result<bool> {
    match (a.get(1), b.get(1)) {
        (Some(a_family), Some(b_family)) if a_family == b_family => Ok(address_bytes(a, *a_family)?.eq(address_bytes(b, *b_family)?)),
        _ => Ok(false),
    }
}

fn tun_route(route: &Route) -> Result<RouteMessage> {
    let name = route.if_name().context("TUN route has no interface")?;
    let index = u16::try_from(nix::net::if_::if_nametoindex(name.as_str())?)?;
    let destination = route.network();
    let host = if route.prefix() == if destination.is_ipv4() { 32 } else { 128 } { libc::RTF_HOST } else { 0 };
    RouteMessage::new(
        index,
        libc::RTF_UP | libc::RTF_STATIC | host,
        &[(libc::RTAX_DST, ip_address(destination)), (libc::RTAX_GATEWAY, link_address(index)), (libc::RTAX_NETMASK, ip_address(route.mask()))],
    )
}

fn find_in_snapshot(mut bytes: &[u8], key: &RouteKey) -> Result<Option<RouteMessage>> {
    let mut found = None;
    while !bytes.is_empty() {
        let head = header(bytes)?;
        let length = usize::from(head.rtm_msglen);
        ensure!(length >= size_of::<libc::rt_msghdr>() && length <= bytes.len(), "invalid route dump message");
        ensure!(head.rtm_version == libc::RTM_VERSION as u8, "unexpected route dump version");
        let message = &bytes[..length];
        let attrs = addresses(message, &head)?;
        let family = attrs[libc::RTAX_DST as usize].as_ref().and_then(|dst| dst.get(1)).copied().map(i32::from);
        if found.is_none() && matches!(family, Some(libc::AF_INET | libc::AF_INET6)) && route_key(message)? == *key {
            found = Some(RouteMessage(message.to_vec()));
        }
        // Keep validating the snapshot after finding a match.
        bytes = &bytes[length..];
    }
    Ok(found)
}

#[cfg(test)]
#[path = "../../tests/unit/platform/dns_macos_tests.rs"]
mod dns_tests;

#[cfg(test)]
#[path = "../../tests/unit/platform/endpoint_macos_reference_tests.rs"]
pub(super) mod reference_tests;

#[cfg(test)]
use route_tests::TestRouteTable as RouteTable;

#[cfg(test)]
#[path = "../../tests/unit/platform/routes_macos_tests.rs"]
mod route_tests;
