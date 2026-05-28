use crate::ip_util::{parse_proto_ip, proto_ip_to_cidr};
use daemon_proto::pb::{IpAddr, TunSessionConfig};
use route_manager::{Route, RouteManager};
use std::collections::HashSet;
use std::net::IpAddr as StdIpAddr;

#[cfg(target_os = "linux")]
pub mod linux;
#[cfg(target_os = "macos")]
pub mod macos;
#[cfg(target_os = "windows")]
pub mod windows;

pub type CleanupHook = Box<dyn FnOnce() -> Result<(), String> + Send + 'static>;

pub fn configure_session(config: &TunSessionConfig, interface_name: &str) -> Result<CleanupHook, String> {
    #[cfg(target_os = "linux")]
    {
        return linux::configure_session(config, interface_name);
    }

    #[cfg(target_os = "macos")]
    {
        return macos::configure_session(config, interface_name);
    }

    #[cfg(target_os = "windows")]
    {
        return windows::configure_session(config, interface_name);
    }

    #[allow(unreachable_code)]
    Err("unsupported platform for daemon runtime".to_string())
}

pub fn prepare_session_start() -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        return windows::clear_stale_nrpt_rules_once();
    }

    #[allow(unreachable_code)]
    Ok(())
}

pub fn ips_to_args(values: &[IpAddr]) -> Vec<String> {
    let mut seen = HashSet::new();
    let mut result = Vec::new();
    for addr in values {
        let ip = proto_ip_to_cidr(addr);
        if seen.insert(ip.clone()) {
            result.push(ip);
        }
    }
    result
}

pub fn normalize_domains(values: &[String]) -> Vec<String> {
    let mut seen = HashSet::new();
    let mut normalized = Vec::new();

    for value in values {
        let trimmed = value.trim();
        if trimmed.is_empty() {
            continue;
        }

        let domain = trimmed.trim_start_matches('.').to_string();
        if domain.is_empty() {
            continue;
        }

        if seen.insert(domain.clone()) {
            normalized.push(domain);
        }
    }

    normalized
}

pub fn build_and_filter_routes(
    config: &TunSessionConfig,
    interface_name: &str,
) -> Result<(RouteManager, Vec<Route>, Vec<Route>), String> {
    let mut mgr = RouteManager::new().map_err(|e| format!("route manager: {e}"))?;

    let routes: Vec<Route> = config
        .routes
        .iter()
        .filter_map(|addr| {
            let (ip, _) = parse_proto_ip(addr)?;
            Some(Route::new(ip, u8::try_from(addr.prefix?).ok()?).with_if_name(interface_name.to_string()))
        })
        .collect();

    let mut endpoint_routes: Vec<Route> = Vec::new();
    let mut endpoint_ips: Vec<StdIpAddr> = Vec::new();
    for addr in &config.endpoints {
        let Some((ip, _)) = parse_proto_ip(addr) else { continue };
        if let Some(route) = mgr.find_route(&ip).ok().flatten() {
            endpoint_routes.push(route.with_if_name(interface_name.to_string()));
        }
        endpoint_ips.push(ip);
    }

    let filtered_routes: Vec<Route> = routes
        .iter()
        .filter(|route| !endpoint_ips.iter().any(|ep| *ep == route.destination()))
        .cloned()
        .collect();

    Ok((mgr, filtered_routes, endpoint_routes))
}

pub fn add_routes_with_predelete(mgr: &mut RouteManager, filtered_routes: &[Route], endpoint_routes: &[Route]) -> Result<(), String> {
    for route in endpoint_routes.iter().chain(filtered_routes.iter()) {
        let _ = mgr.delete(route);
        mgr.add(route).map_err(|e| format!("failed to add route: {e}"))?;
    }
    Ok(())
}

fn cleanup_routes<F>(filtered_routes: &[Route], endpoint_routes: &[Route], dns_teardown: F) -> Result<(), String>
where
    F: FnOnce() -> Result<(), String>,
{
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
            for route in filtered_routes.iter().rev().chain(endpoint_routes.iter().rev()) {
                capture_error(mgr.delete(route).map_err(|e| e.to_string()));
            }
        }
        Err(e) => capture_error(Err(e.to_string())),
    }
    capture_error(dns_teardown());

    match cleanup_error {
        Some(error) => Err(error),
        None => Ok(()),
    }
}

pub fn into_cleanup_hook(
    setup_result: Result<(), String>,
    filtered_routes: Vec<Route>,
    endpoint_routes: Vec<Route>,
    interface_name: String,
    dns_teardown: fn(&str) -> Result<(), String>,
) -> Result<CleanupHook, String> {
    let cleanup_interface = interface_name.clone();
    match setup_result {
        Ok(()) => Ok(Box::new(move || cleanup_routes(&filtered_routes, &endpoint_routes, || dns_teardown(&cleanup_interface)))),
        Err(setup_error) => {
            match cleanup_routes(&filtered_routes, &endpoint_routes, || dns_teardown(&interface_name)) {
                Ok(()) => Err(setup_error),
                Err(cleanup_error) => Err(format!("{setup_error}; cleanup failed: {cleanup_error}")),
            }
        }
    }
}
