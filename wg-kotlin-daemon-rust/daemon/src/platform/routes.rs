use crate::validation::Config;
use anyhow::{Context, Result, anyhow, ensure};
use ipnetwork::IpNetwork;
use std::{
    collections::{BTreeMap, HashSet},
    net::IpAddr,
    ops::AsyncFnOnce,
    sync::{Arc, LazyLock, Mutex, MutexGuard, Weak},
    time::Duration,
};
use talpid_routing::{Node, RequiredRoute, RouteManagerHandle};
use tokio::runtime::Runtime;
use tokio::sync::watch;

static ROUTES: LazyLock<Mutex<RouteRegistry>> = LazyLock::new(Mutex::default);
#[cfg(target_os = "linux")]
const ROUTING_ID: u32 = 0x77676b74;

#[derive(Default)]
pub(super) struct RouteRegistry {
    routes: BTreeMap<IpNetwork, Arc<RequiredRoute>>,
    interfaces: Vec<Weak<str>>,
    manager: Option<(Runtime, RouteManagerHandle)>,
    pub(super) failure: watch::Sender<Option<String>>,
}

pub struct ResolvedEndpoints {
    pub(super) routes: Vec<RequiredRoute>,
    pub(super) registry: MutexGuard<'static, RouteRegistry>,
}

pub(super) fn registry() -> Result<MutexGuard<'static, RouteRegistry>> {
    ROUTES.lock().map_err(|_| anyhow!("route registry poisoned"))
}

fn upstream_error(error: talpid_routing::Error) -> anyhow::Error {
    // Upstream Windows errors omit some nested I/O sources from their Display chain.
    let detail = format!("{error:?}");
    anyhow::Error::new(error).context(detail)
}

impl RouteRegistry {
    pub(super) fn track_interface(&mut self, name: &Arc<str>) {
        self.interfaces.retain(|interface| interface.strong_count() > 0);
        self.interfaces.push(Arc::downgrade(name));
    }

    fn check_endpoint_path(&self, interface: &str, ip: IpAddr, uses: impl Fn(&str) -> bool) -> Result<()> {
        if let Some(tunnel) = self.interfaces.iter().filter_map(Weak::upgrade).find(|name| uses(name)) {
            anyhow::bail!("endpoint {ip} for {interface} would be routed through active tunnel {tunnel}");
        }
        Ok(())
    }

    fn endpoint_route(&mut self, interface: &str, ip: IpAddr) -> Result<RequiredRoute> {
        let prefix = IpNetwork::new(ip, if ip.is_ipv4() { 32 } else { 128 })?;
        if let Some(route) = self.routes.get(&prefix) {
            self.check_endpoint_path(interface, ip, |name| route.as_ref() == &RequiredRoute::new(prefix, Node::device(name.to_owned())))?;
            return Ok(route.as_ref().clone());
        }
        #[cfg(target_os = "linux")]
        let node = {
            let route = self
                .request(async |manager| manager.get_destination_route(ip, None).await)
                .with_context(|| format!("failed to resolve route for endpoint {ip}"))?
                .context("no route to endpoint")?;
            let node = route.get_node();
            self.check_endpoint_path(interface, ip, |name| node.get_device() == Some(name))?;
            node.clone()
        };
        #[cfg(not(target_os = "linux"))]
        let node = talpid_routing::NetNode::DefaultNode;
        Ok(RequiredRoute::new(prefix, node))
    }

    fn request<T>(&mut self, operation: impl AsyncFnOnce(&RouteManagerHandle) -> Result<T, talpid_routing::Error>) -> Result<T> {
        if self.manager.is_none() {
            // The upstream actors and network monitors must keep running between requests.
            let runtime = tokio::runtime::Builder::new_multi_thread().worker_threads(1).enable_all().build()?;
            let handle = runtime
                .block_on(async {
                    #[cfg(target_os = "linux")]
                    let handle = RouteManagerHandle::spawn(ROUTING_ID, ROUTING_ID).await;
                    #[cfg(not(target_os = "linux"))]
                    let handle = RouteManagerHandle::spawn().await;
                    handle
                })
                .map_err(upstream_error)?;
            self.manager = Some((runtime, handle));
        }
        let (runtime, handle) = self.manager.as_ref().unwrap();
        runtime.block_on(async {
            tokio::time::timeout(Duration::from_secs(5), operation(handle)).await.context("routing request timed out")?.map_err(upstream_error)
        })
    }

    pub(super) fn acquire(&mut self, route: RequiredRoute, leases: &mut Vec<Arc<RequiredRoute>>) -> Result<()> {
        if let Some(existing) = self.routes.get(&route.prefix) {
            ensure!(existing.as_ref() == &route, "route {} is already owned by another session", route.prefix);
            if !leases.iter().any(|lease| Arc::ptr_eq(lease, existing)) {
                leases.push(existing.clone());
            }
            return Ok(());
        }
        let route = Arc::new(route);
        self.routes.insert(route.prefix, route.clone());
        leases.push(route.clone()); // Include partially applied requests in setup rollback.
        self.request(async |manager| manager.add_routes(HashSet::from([route.as_ref().clone()])).await)
            .with_context(|| format!("failed to add route {route:?}"))
    }

    pub(super) fn release(&mut self, leases: Vec<Arc<RequiredRoute>>) -> Result<()> {
        let mut changed = false;
        for route in leases {
            if Arc::strong_count(&route) == 2 {
                self.routes.remove(&route.prefix);
                changed = true;
            }
        }
        if !changed && !self.routes.is_empty() {
            return Ok(());
        }
        self.restart()
    }

    pub(super) fn restart(&mut self) -> Result<()> {
        self.restart_with(Self::replace_manager)
    }

    fn restart_with(&mut self, mut replace: impl FnMut(&mut Self) -> Result<()>) -> Result<()> {
        let Err(error) = replace(self) else { return Ok(()) };
        // The previous manager has already stopped. Restore the leases that survived
        // the operation, using a fresh manager in case the first applied only some.
        if let Err(restore) = replace(self) {
            let error = error.context(format!("failed to restore active session routes: {restore:#}"));
            std::mem::take(&mut self.failure).send_replace(Some(format!("{error:#}")));
            return Err(error);
        }
        Err(error)
    }

    fn stop_manager(&mut self) {
        if let Some((runtime, manager)) = self.manager.take() {
            runtime.block_on(manager.stop());
        }
    }

    fn replace_manager(&mut self) -> Result<()> {
        self.stop_manager();
        if self.routes.is_empty() {
            return Ok(());
        }
        let routes = self.routes.values().map(|route| route.as_ref().clone()).collect();
        // Refresh upstream's interface snapshot and reapply the active sessions' requests.
        self.request(async |manager| manager.add_routes(routes).await)
    }
}

/// Serialize endpoint resolution, TUN creation and registration with session cleanup.
pub fn resolve_endpoint_routes(config: &Config) -> Result<ResolvedEndpoints> {
    let endpoints = config.peer_endpoints.iter().map(|ip| ip.context("invalid peer endpoint address")).collect::<Result<Vec<_>>>()?;
    let mut registry = registry()?;
    let routes = endpoints.into_iter().map(|ip| registry.endpoint_route(&config.interface_name, ip)).collect::<Result<_>>();
    #[cfg(target_os = "linux")]
    if registry.routes.is_empty() {
        // A manager started only for these lookups owns no session routes.
        // Stop it even if lookup failed or the caller never creates the TUN.
        registry.stop_manager();
    }
    Ok(ResolvedEndpoints { routes: routes?, registry })
}

pub(super) fn vpn_routes(config: &Config, interface: &str) -> Result<Vec<RequiredRoute>> {
    config
        .peer_allowed_ips
        .iter()
        .filter(|(ip, prefix)| *prefix != if ip.is_ipv4() { 32 } else { 128 } || !config.peer_endpoints.contains(&Some(*ip)))
        .map(|&(ip, prefix)| {
            let network = IpNetwork::new(ip, prefix)?;
            Ok(RequiredRoute::new(IpNetwork::new(network.network(), prefix)?, Node::device(interface.to_owned())))
        })
        .collect()
}

#[cfg(test)]
#[path = "../../tests/unit/platform/routes_tests.rs"]
mod tests;
