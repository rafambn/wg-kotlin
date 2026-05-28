use crate::ip_util::proto_ip_to_cidr;
use crate::platform::{
    CleanupHook, EndpointRoute, cidrs_to_args, ensure_required_binaries, ip_literal, ips_to_args, is_ipv6_literal, normalize_domains, run_command,
};
use daemon_proto::pb::{IpAddr, TunSessionConfig};

const NOT_FOUND_PATTERNS: &[&str] = &["not found", "no such process", "cannot find", "cannot assign requested address"];

pub fn configure_session(config: &TunSessionConfig, interface_name: &str) -> Result<CleanupHook, String> {
    ensure_required_binaries(&["ip", "resolvectl"])?;

    let routes = cidrs_to_args(&config.routes);
    let endpoint_routes = resolve_endpoint_routes(&config.endpoints);
    let endpoint_ips: Vec<String> = endpoint_routes.iter().map(|(endpoint, _)| endpoint.to_string()).collect();
    let dns_servers = ips_to_args(config.dns.as_ref().map(|dns| &dns.servers[..]).unwrap_or(&[]));
    let dns_domains: Vec<String> = normalize_domains(&config.dns.as_ref().map(|dns| dns.search_domains.clone()).unwrap_or_default())
        .into_iter()
        .map(|domain| format!("~{domain}"))
        .collect();

    let filtered_routes = filter_routes_for_endpoints(routes, &endpoint_ips);

    let setup_result = (|| -> Result<(), String> {
        for (endpoint, route) in &endpoint_routes {
            add_endpoint_route(endpoint, route)?;
        }

        for route in &filtered_routes {
            add_route(route, interface_name)?;
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
    Ok(Box::new(move || cleanup_linux_session(&filtered_routes, &endpoint_routes, &cleanup_interface)))
}

fn cleanup_linux_session(filtered_routes: &[String], endpoint_routes: &[(String, EndpointRoute)], interface_name: &str) -> Result<(), String> {
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

    for route in filtered_routes.iter().rev() {
        capture_error(delete_route(route, interface_name));
    }
    for (endpoint, route) in endpoint_routes.iter().rev() {
        capture_error(delete_endpoint_route(endpoint, route));
    }
    capture_error(revert_dns(interface_name));

    match cleanup_error {
        Some(error) => Err(error),
        None => Ok(()),
    }
}

fn resolve_endpoint_routes(endpoints: &[IpAddr]) -> Vec<(String, EndpointRoute)> {
    let mut resolved = Vec::<(String, EndpointRoute)>::new();

    for endpoint in endpoints {
        let endpoint_ip = endpoint_host(&proto_ip_to_cidr(endpoint));

        if endpoint_ip.is_empty() {
            continue;
        }

        if let Some(route) = resolve_endpoint_route(&endpoint_ip) {
            resolved.push((endpoint_ip, route));
        }
    }

    resolved
}

fn resolve_endpoint_route(endpoint: &str) -> Option<EndpointRoute> {
    let stdout = match run_command("resolve-endpoint-route", "ip", &["route".to_string(), "get".to_string(), endpoint.to_string()], None, &[], &[]) {
        Ok(stdout) => stdout,
        Err(_) => return None,
    };

    parse_ip_route_get_output(&stdout)
}

fn add_route(route: &str, interface_name: &str) -> Result<(), String> {
    run_command("add-route", "ip", &route_args("replace", route, interface_name), None, &[], &[]).map(|_| ())
}

fn delete_route(route: &str, interface_name: &str) -> Result<(), String> {
    run_command("delete-route", "ip", &route_args("delete", route, interface_name), None, &[], NOT_FOUND_PATTERNS).map(|_| ())
}

pub fn route_args(command: &str, route: &str, interface_name: &str) -> Vec<String> {
    let mut args = Vec::<String>::new();

    if is_ipv6_literal(route) {
        args.push("-6".to_string());
    }

    args.extend(["route".to_string(), command.to_string(), route.to_string(), "dev".to_string(), interface_name.to_string()]);

    args
}

fn add_endpoint_route(endpoint: &str, route: &EndpointRoute) -> Result<(), String> {
    run_command("add-endpoint-route", "ip", &endpoint_route_args("replace", endpoint, route), None, &[], &[]).map(|_| ())
}

fn delete_endpoint_route(endpoint: &str, route: &EndpointRoute) -> Result<(), String> {
    run_command("delete-endpoint-route", "ip", &endpoint_route_args("delete", endpoint, route), None, &[], NOT_FOUND_PATTERNS).map(|_| ())
}

pub fn endpoint_route_args(command: &str, endpoint: &str, route: &EndpointRoute) -> Vec<String> {
    let mut args = Vec::<String>::new();

    if is_ipv6_literal(endpoint) {
        args.push("-6".to_string());
    }

    let endpoint_cidr = format!("{endpoint}/32");

    args.extend(["route".to_string(), command.to_string(), endpoint_cidr]);

    if let Some(gateway) = &route.gateway {
        args.push("via".to_string());
        args.push(gateway.clone());
    }

    args.push("dev".to_string());
    args.push(route.device.clone());

    args
}

fn revert_dns(interface_name: &str) -> Result<(), String> {
    run_command("revert-dns", "resolvectl", &["revert".to_string(), interface_name.to_string()], None, &[], &[]).map(|_| ())
}

fn endpoint_host(endpoint: &str) -> String {
    endpoint.trim().to_string()
}

pub fn filter_routes_for_endpoints(routes: Vec<String>, endpoint_ips: &[String]) -> Vec<String> {
    routes.into_iter().filter(|route| !endpoint_ips.iter().any(|endpoint| endpoint == ip_literal(route))).collect()
}

pub fn parse_ip_route_get_output(stdout: &str) -> Option<EndpointRoute> {
    let first_line = stdout.lines().next().unwrap_or_default();
    if first_line.trim().is_empty() {
        return None;
    }

    let tokens: Vec<&str> = first_line.split_whitespace().collect();
    let gateway = tokens.windows(2).find(|window| window[0] == "via").map(|window| window[1].to_string());
    let device = tokens.windows(2).find(|window| window[0] == "dev").map(|window| window[1].to_string());

    device.map(|device| EndpointRoute { gateway, device })
}
