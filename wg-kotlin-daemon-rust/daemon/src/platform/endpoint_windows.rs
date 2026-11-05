use route_manager::{Route, RouteManager};
use std::{io, net::IpAddr};

#[derive(Clone, Debug)]
pub struct EndpointRoute(Route);

impl EndpointRoute {
    pub fn lookup(ip: IpAddr) -> io::Result<Self> {
        // On Windows find_route uses GetBestRoute2, not an ordered route dump.
        let found = RouteManager::new()?.find_route(&ip)?.ok_or_else(|| io::Error::other("no route to endpoint"))?;
        Ok(Self::from_route(ip, found))
    }

    fn from_route(ip: IpAddr, found: Route) -> Self {
        let mut route = Route::new(ip, if ip.is_ipv4() { 32 } else { 128 });
        if let Some(gateway) = found.gateway() {
            route = route.with_gateway(gateway);
        }
        if let Some(name) = found.if_name() {
            route = route.with_if_name(name.clone());
        }
        if let Some(index) = found.if_index() {
            route = route.with_if_index(index);
        }
        if let Some(metric) = found.metric() {
            route = route.with_metric(metric);
        }
        if let Some(luid) = found.luid() {
            route = route.with_luid(luid);
        }
        Self(route)
    }

    pub fn replace(&self) -> io::Result<()> {
        let mut manager = RouteManager::new()?;
        let _ = manager.delete(&self.0);
        manager.add(&self.0)
    }

    pub fn delete(&self) -> io::Result<()> {
        RouteManager::new()?.delete(&self.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn preserves_ipv6_gateway_interface_luid_and_metric() {
        let endpoint: IpAddr = "2001:db8::10".parse().unwrap();
        let original = Route::new("::".parse().unwrap(), 0)
            .with_gateway("fe80::1".parse().unwrap())
            .with_if_index(12)
            .with_luid(0x6008000000000)
            .with_metric(71);
        assert_eq!(
            EndpointRoute::from_route(endpoint, original).0,
            Route::new(endpoint, 128).with_gateway("fe80::1".parse().unwrap()).with_if_index(12).with_luid(0x6008000000000).with_metric(71)
        );
    }
}
