use super::run_command;
use anyhow::{Context, Result, ensure};
use futures_util::TryStreamExt;
use netlink_packet_core::DefaultNla;
use netlink_packet_route::route::{RouteAttribute, RouteFlags, RouteMessage, RouteNextHopFlags, RouteProtocol, RouteType};
use std::{future::Future, net::IpAddr, time::Duration};

pub const REQUIRED_BINARIES: &[&str] = &["resolvectl"];

pub(super) fn set_dns(interface: &str, servers: &[String], domains: &[String]) -> Result<()> {
    let domains: Vec<_> = domains.iter().map(|domain| format!("~{domain}")).collect();
    [("dns", servers), ("domain", &domains)].into_iter().try_for_each(|(operation, values)| {
        let args: Vec<_> = [operation, interface].into_iter().chain(values.iter().map(String::as_str)).collect();
        run_command(&format!("set-{operation}"), "resolvectl", &args, &[])
    })
}

pub(super) fn clear_dns(interface: &str) -> Result<()> {
    run_command("revert-dns", "resolvectl", &["revert", interface], &[])
}

#[derive(Clone, Debug)]
pub struct EndpointRoute(RouteMessage);

impl EndpointRoute {
    pub(super) fn same_slot(&self, other: &Self) -> bool {
        self.slot() == other.slot()
    }

    // REPLACE identifies a FIB slot independently of its current nexthops/metrics.
    fn slot(&self) -> (u32, u32, u8, u8, Option<&RouteAttribute>, Option<&RouteAttribute>) {
        let route = &self.0;
        let ipv6 = route.header.destination_prefix_length == 128;
        let mut table = u32::from(route.header.table);
        let mut priority = 0;
        for attr in &route.attributes {
            match attr {
                RouteAttribute::Table(value) => table = *value,
                RouteAttribute::Priority(value) => priority = *value,
                _ => {}
            }
        }
        let destination = route.attributes.iter().find(|attr| matches!(attr, RouteAttribute::Destination(_)));
        let source = route.attributes.iter().find(|attr| ipv6 && route.header.source_prefix_length != 0 && matches!(attr, RouteAttribute::Source(_)));
        (
            if table == 0 { 254 } else { table },
            if ipv6 && priority == 0 { 1024 } else { priority },
            if ipv6 { 0 } else { route.header.tos },
            if ipv6 { route.header.source_prefix_length } else { 0 },
            destination,
            source,
        )
    }

    pub fn lookup(ip: IpAddr) -> Result<Self> {
        let mut query = RouteMessage::default();
        query.header.address_family = ip.into();
        query.header.destination_prefix_length = if ip.is_ipv4() { 32 } else { 128 };
        // FIB_MATCH asks the kernel to apply policy rules and return the original
        // FIB entry, including onlink flags, rather than a lossy route dump/cache.
        query.header.flags = RouteFlags::FibMatch | RouteFlags::LookupTable;
        query.attributes = vec![
            RouteAttribute::Destination(ip.into()),
            RouteAttribute::Uid(unsafe { libc::getuid() }),
            RouteAttribute::Other(DefaultNla::new(27, vec![libc::IPPROTO_UDP as u8])),
        ]; // RTA_IP_PROTO
        let found = request(|handle| async move { handle.route().get(query).execute().try_next().await })?.context("no route to endpoint")?;
        Self::from_route(ip, found)
    }

    fn from_route(ip: IpAddr, mut found: RouteMessage) -> Result<Self> {
        ensure!(matches!(found.header.kind, RouteType::Unicast | RouteType::Local), "endpoint route is not reachable");
        found.header.destination_prefix_length = if ip.is_ipv4() { 32 } else { 128 };
        found.header.protocol = RouteProtocol::Static;
        // Status/cache/offload flags cannot be replayed as route creation flags.
        found.header.flags &= RouteFlags::Onlink | RouteFlags::Pervasive;
        for attr in &mut found.attributes {
            if let RouteAttribute::MultiPath(hops) = attr {
                // Dead nexthops are not eligible for the original route either.
                // Linkdown/offload status is recalculated by the kernel on add.
                hops.retain(|hop| !hop.flags.contains(RouteNextHopFlags::Dead));
                ensure!(!hops.is_empty(), "endpoint route has no live nexthops");
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
        found.attributes.push(RouteAttribute::Destination(ip.into()));
        Ok(Self(found))
    }

    pub fn replace(&self) -> Result<()> {
        request(|handle| async move { handle.route().add(self.0.clone()).replace().execute().await })
    }

    pub fn delete(&self) -> Result<()> {
        request(|handle| async move { handle.route().del(self.0.clone()).execute().await })
    }
}

fn request<T, F: Future<Output = Result<T, rtnetlink::Error>>>(operation: impl FnOnce(rtnetlink::Handle) -> F) -> Result<T> {
    tokio::runtime::Builder::new_current_thread().enable_all().build()?.block_on(async {
        let (connection, handle, _) = rtnetlink::new_connection()?;
        tokio::spawn(connection);
        Ok(tokio::time::timeout(Duration::from_secs(5), operation(handle)).await??)
    })
}

#[cfg(test)]
#[path = "../../tests/unit/platform/linux_tests.rs"]
mod tests;
