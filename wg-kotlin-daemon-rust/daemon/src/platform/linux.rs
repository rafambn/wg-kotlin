use crate::ip_util::parse_proto_ip;
use crate::platform::{
    CleanupHook, ensure_required_binaries, ips_to_args, normalize_domains, run_command,
};
use daemon_proto::pb::{IpAddr, TunSessionConfig};
use route_manager::{Route, RouteManager};
use std::net::IpAddr as StdIpAddr;

pub fn configure_session(config: &TunSessionConfig, interface_name: &str) -> Result<CleanupHook, String> {
    ensure_required_binaries(&["resolvectl"])?;

    let mut mgr = RouteManager::new().map_err(|e| format!("route manager: {e}"))?;

    let routes = build_routes(&config.routes, interface_name);
    let endpoint_routes = build_endpoint_routes(&mut mgr, &config.endpoints, interface_name);
    let endpoint_ips: Vec<StdIpAddr> = config.endpoints.iter().filter_map(|addr| parse_proto_ip(addr).map(|(ip, _)| ip)).collect();
    let filtered_routes = filter_routes_for_endpoints(&routes, &endpoint_ips);

    let dns_servers = ips_to_args(config.dns.as_ref().map(|dns| &dns.servers[..]).unwrap_or(&[]));
    let dns_domains: Vec<String> = normalize_domains(&config.dns.as_ref().map(|dns| dns.search_domains.clone()).unwrap_or_default())
        .into_iter()
        .map(|domain| format!("~{domain}"))
        .collect();

    let setup_result = (|| -> Result<(), String> {
        for route in endpoint_routes.iter().chain(filtered_routes.iter()) {
            mgr.add(route).map_err(|e| format!("failed to add route: {e}"))?;
        }

        revert_dns(interface_name)?;
        if !dns_servers.is_empty() && !dns_domains.is_empty() {
            let mut dns_args = vec!["dns".to_string(), interface_name.to_string()];
            dns_args.extend(dns_servers.clone());
            run_command("set-dns", "resolvectl", &dns_args, None, &[], &[])?;

            let mut domains_args = vec!["domain".to_string(), interface_name.to_string()];
            domains_args.extend(dns_domains.clone());
            run_command("set-domains", "resolvectl", &domains_args, None, &[], &[])?;
        }
        Ok(())
    })();

    if let Err(setup_error) = setup_result {
        return match cleanup_linux_session(&filtered_routes, &endpoint_routes, interface_name) {
            Ok(()) => Err(setup_error),
            Err(cleanup_error) => Err(format!("{setup_error}; cleanup failed: {cleanup_error}")),
        };
    }

    let cleanup_interface = interface_name.to_string();
    let cleanup_filtered = filtered_routes.clone();
    let cleanup_endpoint = endpoint_routes.clone();
    Ok(Box::new(move || cleanup_linux_session(&cleanup_filtered, &cleanup_endpoint, &cleanup_interface)))
}

fn build_routes(routes: &[IpAddr], interface_name: &str) -> Vec<Route> {
    routes
        .iter()
        .filter_map(|addr| {
            let (ip, _) = parse_proto_ip(addr)?;
            Some(Route::new(ip, u8::try_from(addr.prefix?).ok()?).with_if_name(interface_name.to_string()))
        })
        .collect()
}

fn build_endpoint_routes(mgr: &mut RouteManager, endpoints: &[IpAddr], interface_name: &str) -> Vec<Route> {
    endpoints
        .iter()
        .filter_map(|addr| {
            let (ip, _) = parse_proto_ip(addr)?;
            let found = mgr.find_route(&ip).ok()??;
            Some(found.with_if_name(interface_name.to_string()))
        })
        .collect()
}

pub fn filter_routes_for_endpoints(routes: &[Route], endpoint_ips: &[StdIpAddr]) -> Vec<Route> {
    routes
        .iter()
        .filter(|route| !endpoint_ips.iter().any(|ep| *ep == route.destination()))
        .cloned()
        .collect()
}

fn cleanup_linux_session(filtered_routes: &[Route], endpoint_routes: &[Route], interface_name: &str) -> Result<(), String> {
    let mut cleanup_error: Option<String> = None;
    let mut capture_error = |result: Result<(), String>| {
        if let Err(error) = result {
            if let Some(existing) = &mut cleanup_error {
                existing.push_str("; ");
                existing.push_str(&error);
            } else {
                cleanup_error = Some(error);
            }
        }
    };

    match RouteManager::new() {
        Ok(mut mgr) => {
            for route in filtered_routes.iter().rev() {
                capture_error(mgr.delete(route).map_err(|e| e.to_string()));
            }
            for route in endpoint_routes.iter().rev() {
                capture_error(mgr.delete(route).map_err(|e| e.to_string()));
            }
        }
        Err(e) => capture_error(Err(e.to_string())),
    }
    capture_error(revert_dns(interface_name));

    match cleanup_error {
        Some(error) => Err(error),
        None => Ok(()),
    }
}

fn revert_dns(interface_name: &str) -> Result<(), String> {
    run_command("revert-dns", "resolvectl", &["revert".to_string(), interface_name.to_string()], None, &[], &[]).map(|_| ())
}
