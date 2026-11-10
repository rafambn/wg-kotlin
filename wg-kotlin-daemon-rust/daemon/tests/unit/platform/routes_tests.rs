use super::*;

fn config(endpoints: &[&str], allowed: &[&str]) -> Config {
    Config {
        interface_name: "utun64".into(),
        addresses: vec![("192.0.2.64".parse().unwrap(), 32)],
        peer_allowed_ips: allowed
            .iter()
            .map(|network| {
                let network: IpNetwork = network.parse().unwrap();
                (network.ip(), network.prefix())
            })
            .collect(),
        peer_endpoints: endpoints.iter().map(|ip| Some(ip.parse().unwrap())).collect(),
        mtu: None,
        dns_servers: vec![],
        dns_domains: vec![],
    }
}

fn route(network: &str, device: &str) -> RequiredRoute {
    RequiredRoute::new(network.parse().unwrap(), Node::device(device.to_owned()))
}

#[test]
fn endpoint_host_exceptions_are_not_routed_into_the_tunnel() {
    let config =
        config(&["203.0.113.7", "2001:db8::7"], &["203.0.113.7/32", "203.0.113.8/32", "2001:db8::7/128", "2001:db8::8/128", "0.0.0.0/0", "::/0"]);
    let routes: HashSet<_> = vpn_routes(&config, "utun64").unwrap().into_iter().collect();
    assert_eq!(routes, ["203.0.113.8/32", "2001:db8::8/128", "0.0.0.0/0", "::/0"].map(|prefix| route(prefix, "utun64")).into());
}

#[test]
fn vpn_prefixes_are_canonical_for_both_address_families() {
    let config = config(&[], &["198.19.0.5/24", "2001:db8::abcd/64"]);
    let routes = vpn_routes(&config, "utun65").unwrap();
    assert_eq!(routes, [route("198.19.0.0/24", "utun65"), route("2001:db8::/64", "utun65")]);
}

#[test]
fn identical_leases_survive_both_session_close_orders_without_starting_a_manager() {
    for first in [0, 1] {
        let mut registry = RouteRegistry::default();
        let shared = Arc::new(route("203.0.113.7/32", "eth0"));
        registry.routes.insert(shared.prefix, shared.clone());
        let first_lease = vec![shared];
        let mut second_lease = vec![];
        registry.acquire(route("203.0.113.7/32", "eth0"), &mut second_lease).unwrap();
        registry.acquire(route("203.0.113.7/32", "eth0"), &mut second_lease).unwrap();
        assert_eq!(second_lease.len(), 1, "one session must not acquire the same route twice");
        let mut sessions = [Some(first_lease), Some(second_lease)];
        registry.release(sessions[first].take().unwrap()).unwrap();
        assert_eq!(registry.routes.len(), 1);
        registry.release(sessions[1 - first].take().unwrap()).unwrap();
        assert!(registry.routes.is_empty());
        assert!(registry.manager.is_none());
    }
}

#[test]
fn conflicting_prefixes_fail_before_native_routing_or_changing_the_existing_lease() {
    let mut registry = RouteRegistry::default();
    let existing = Arc::new(route("198.19.0.0/24", "utun64"));
    registry.routes.insert(existing.prefix, existing.clone());
    let mut rejected = vec![];
    let error = registry.acquire(route("198.19.0.0/24", "utun65"), &mut rejected).unwrap_err();
    assert!(error.to_string().contains("already owned"));
    assert!(rejected.is_empty());
    assert!(Arc::ptr_eq(registry.routes.values().next().unwrap(), &existing));
    assert!(registry.manager.is_none());
}

#[test]
fn empty_endpoint_resolution_holds_the_setup_transaction_without_native_work() {
    use std::{sync::mpsc, time::Duration};
    let endpoints = resolve_endpoint_routes(&config(&[], &[])).unwrap();
    assert!(endpoints.routes.is_empty());
    let (started_tx, started_rx) = mpsc::channel();
    let (locked_tx, locked_rx) = mpsc::channel();
    let worker = std::thread::spawn(move || {
        started_tx.send(()).unwrap();
        let _registry = registry().unwrap();
        locked_tx.send(()).unwrap();
    });
    started_rx.recv_timeout(Duration::from_secs(2)).unwrap();
    assert!(matches!(locked_rx.recv_timeout(Duration::from_millis(50)), Err(mpsc::RecvTimeoutError::Timeout)));
    drop(endpoints);
    locked_rx.recv_timeout(Duration::from_secs(2)).unwrap();
    worker.join().unwrap();
}

#[test]
fn malformed_endpoints_are_rejected_before_native_routing() {
    let mut config = config(&[], &[]);
    config.peer_endpoints = vec![None];
    let error = resolve_endpoint_routes(&config).err().expect("malformed endpoint must fail");
    assert!(error.to_string().contains("invalid peer endpoint"));
}

#[cfg(windows)]
#[test]
fn upstream_windows_errors_keep_nested_native_codes_in_the_protocol_cause() {
    let native = talpid_routing::Error::AddRoutesFailed(Box::new(talpid_routing::Error::AddToRouteTable(std::io::Error::from_raw_os_error(5))));
    let error = upstream_error(native);
    let text = format!("{error:#}");
    assert!(text.contains("code: 5"), "{text}");
    assert!(text.contains("Failed to add routes"), "{text}");
}

#[cfg(target_os = "macos")]
#[tokio::test]
#[ignore = "root on macOS; upstream manages default routes; temporary benchmark routes and two TUNs; run serially"]
async fn native_macos_original_talpid_shares_endpoints_and_keeps_other_sessions() {
    assert_eq!(unsafe { libc::geteuid() }, 0, "run this ignored test as root");
    tokio::task::spawn_blocking(|| {
        fn exact(prefix: &str) -> Vec<route_manager::Route> {
            let prefix: IpNetwork = prefix.parse().unwrap();
            route_manager::RouteManager::new()
                .unwrap()
                .list()
                .unwrap()
                .into_iter()
                .filter(|route| route.network() == prefix.network() && route.prefix() == prefix.prefix())
                .collect()
        }
        struct NativeSession(Option<crate::session::TunSession>);
        impl NativeSession {
            fn close(&mut self) {
                self.0.take().unwrap().close().unwrap();
            }
        }
        impl Drop for NativeSession {
            fn drop(&mut self) {
                if let Some(session) = self.0.take() {
                    let _ = session.close();
                }
            }
        }
        let prefixes = ["198.18.254.129/32", "198.19.64.0/24", "198.19.65.0/24", "192.0.2.64/32", "192.0.2.65/32"];
        for prefix in prefixes {
            assert!(exact(prefix).is_empty(), "test route is already in use: {prefix}");
        }
        for first_to_close in [0, 1] {
            let names: Vec<_> = (64..256)
                .map(|index| format!("utun{index}"))
                .filter(|name| unsafe { libc::if_nametoindex(std::ffi::CString::new(name.as_str()).unwrap().as_ptr()) == 0 })
                .take(2)
                .collect();
            assert_eq!(names.len(), 2);
            let create = crate::session::factory().unwrap();
            let mut sessions = Vec::new();
            for index in 0..2 {
                let mut requested = config(&["198.18.254.129"], &[prefixes[index + 1]]);
                requested.interface_name = names[index].clone();
                requested.addresses = vec![(format!("192.0.2.{}", 64 + index).parse().unwrap(), 32)];
                sessions.push(NativeSession(Some(create(&requested).unwrap())));
            }
            assert_eq!(exact(prefixes[0]).len(), 1);
            sessions[first_to_close].close();
            assert_eq!(exact(prefixes[0]).len(), 1, "shared endpoint must still be installed");
            let remaining = exact(prefixes[2 - first_to_close]);
            assert_eq!(remaining.len(), 1);
            assert_eq!(remaining[0].if_name(), Some(&names[1 - first_to_close]));
            sessions[1 - first_to_close].close();
            for prefix in prefixes {
                assert!(exact(prefix).is_empty(), "route remains after final cleanup: {prefix}");
            }
            eprintln!("original Talpid interfaces={names:?} first_close={first_to_close}: shared endpoint, remaining VPN and final cleanup OK");
        }
    })
    .await
    .unwrap();
}
