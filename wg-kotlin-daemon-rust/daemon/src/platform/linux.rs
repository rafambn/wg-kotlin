use crate::platform::{
    ensure_required_binaries, ip_literal, is_ipv6_literal, normalize_domains, normalize_items,
    run_command, CleanupHook, EndpointRoute,
};
use daemon_proto::pb::TunSessionConfig;

const NOT_FOUND_PATTERNS: &[&str] = &[
    "not found",
    "no such process",
    "cannot find",
    "cannot assign requested address",
];

pub fn configure_session(
    config: &TunSessionConfig,
    interface_name: &str,
) -> Result<CleanupHook, String> {
    ensure_required_binaries(&["ip", "resolvectl"])?;

    let addresses = normalize_items(&config.addresses);
    let routes = normalize_items(&config.routes);
    let endpoint_routes = resolve_endpoint_routes(&config.endpoints);
    let endpoint_ips: Vec<String> = endpoint_routes
        .iter()
        .map(|(endpoint, _)| endpoint.to_string())
        .collect();
    let dns_servers = normalize_items(
        &config
            .dns
            .as_ref()
            .map(|dns| dns.servers.clone())
            .unwrap_or_default(),
    );
    let dns_domains: Vec<String> = normalize_domains(
        &config
            .dns
            .as_ref()
            .map(|dns| dns.search_domains.clone())
            .unwrap_or_default(),
    )
    .into_iter()
    .map(|domain| format!("~{domain}"))
    .collect();

    let filtered_routes = filter_routes_for_endpoints(routes, &endpoint_ips);

    let setup_result = (|| -> Result<(), String> {
        if config.mtu > 0 {
            let mtu = config.mtu;
            run_command(
                "apply-mtu",
                "ip",
                &[
                    "link".to_string(),
                    "set".to_string(),
                    "dev".to_string(),
                    interface_name.to_string(),
                    "mtu".to_string(),
                    mtu.to_string(),
                ],
                None,
                &[],
                &[],
            )?;
        }

        run_command(
            "bring-interface-up",
            "ip",
            &[
                "link".to_string(),
                "set".to_string(),
                "dev".to_string(),
                interface_name.to_string(),
                "up".to_string(),
            ],
            None,
            &[],
            &[],
        )?;

        run_command(
            "flush-addresses",
            "ip",
            &[
                "address".to_string(),
                "flush".to_string(),
                "dev".to_string(),
                interface_name.to_string(),
            ],
            None,
            &[],
            &[],
        )?;

        for address in &addresses {
            run_command(
                "add-address",
                "ip",
                &[
                    "address".to_string(),
                    "add".to_string(),
                    address.to_string(),
                    "dev".to_string(),
                    interface_name.to_string(),
                ],
                None,
                &[],
                &[],
            )?;
        }

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
    Ok(Box::new(move || {
        cleanup_linux_session(&filtered_routes, &endpoint_routes, &cleanup_interface)
    }))
}

fn cleanup_linux_session(
    filtered_routes: &[String],
    endpoint_routes: &[(String, EndpointRoute)],
    interface_name: &str,
) -> Result<(), String> {
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

fn resolve_endpoint_routes(endpoints: &[String]) -> Vec<(String, EndpointRoute)> {
    let mut resolved = Vec::<(String, EndpointRoute)>::new();

    for endpoint in endpoints {
        let endpoint_ip = endpoint_host(endpoint);

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
    let stdout = match run_command(
        "resolve-endpoint-route",
        "ip",
        &["route".to_string(), "get".to_string(), endpoint.to_string()],
        None,
        &[],
        &[],
    ) {
        Ok(stdout) => stdout,
        Err(_) => return None,
    };

    parse_ip_route_get_output(&stdout)
}

fn add_route(route: &str, interface_name: &str) -> Result<(), String> {
    run_command(
        "add-route",
        "ip",
        &route_args("replace", route, interface_name),
        None,
        &[],
        &[],
    )
    .map(|_| ())
}

fn delete_route(route: &str, interface_name: &str) -> Result<(), String> {
    run_command(
        "delete-route",
        "ip",
        &route_args("delete", route, interface_name),
        None,
        &[],
        NOT_FOUND_PATTERNS,
    )
    .map(|_| ())
}

fn route_args(command: &str, route: &str, interface_name: &str) -> Vec<String> {
    let mut args = Vec::<String>::new();

    if is_ipv6_literal(route) {
        args.push("-6".to_string());
    }

    args.extend([
        "route".to_string(),
        command.to_string(),
        route.to_string(),
        "dev".to_string(),
        interface_name.to_string(),
    ]);

    args
}

fn add_endpoint_route(endpoint: &str, route: &EndpointRoute) -> Result<(), String> {
    run_command(
        "add-endpoint-route",
        "ip",
        &endpoint_route_args("replace", endpoint, route),
        None,
        &[],
        &[],
    )
    .map(|_| ())
}

fn delete_endpoint_route(endpoint: &str, route: &EndpointRoute) -> Result<(), String> {
    run_command(
        "delete-endpoint-route",
        "ip",
        &endpoint_route_args("delete", endpoint, route),
        None,
        &[],
        NOT_FOUND_PATTERNS,
    )
    .map(|_| ())
}

fn endpoint_route_args(command: &str, endpoint: &str, route: &EndpointRoute) -> Vec<String> {
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
    run_command(
        "revert-dns",
        "resolvectl",
        &["revert".to_string(), interface_name.to_string()],
        None,
        &[],
        &[],
    )
    .map(|_| ())
}

fn endpoint_host(endpoint: &str) -> String {
    endpoint.trim().to_string()
}

fn filter_routes_for_endpoints(routes: Vec<String>, endpoint_ips: &[String]) -> Vec<String> {
    routes
        .into_iter()
        .filter(|route| {
            !endpoint_ips
                .iter()
                .any(|endpoint| endpoint == ip_literal(route))
        })
        .collect()
}

fn parse_ip_route_get_output(stdout: &str) -> Option<EndpointRoute> {
    let first_line = stdout.lines().next().unwrap_or_default();
    if first_line.trim().is_empty() {
        return None;
    }

    let tokens: Vec<&str> = first_line.split_whitespace().collect();
    let gateway = tokens
        .windows(2)
        .find(|window| window[0] == "via")
        .map(|window| window[1].to_string());
    let device = tokens
        .windows(2)
        .find(|window| window[0] == "dev")
        .map(|window| window[1].to_string());

    device.map(|device| EndpointRoute { gateway, device })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn route_args_adds_ipv6_flag_for_ipv6_routes() {
        let args = route_args("replace", "2001:db8::/64", "utun0");
        assert_eq!(
            args,
            vec![
                "-6".to_string(),
                "route".to_string(),
                "replace".to_string(),
                "2001:db8::/64".to_string(),
                "dev".to_string(),
                "utun0".to_string(),
            ],
        );
    }

    #[test]
    fn endpoint_route_args_adds_ipv4_cidr_and_gateway() {
        let route = EndpointRoute {
            gateway: Some("192.168.1.1".to_string()),
            device: "eth0".to_string(),
        };

        let args = endpoint_route_args("replace", "203.0.113.10", &route);
        assert_eq!(
            args,
            vec![
                "route".to_string(),
                "replace".to_string(),
                "203.0.113.10/32".to_string(),
                "via".to_string(),
                "192.168.1.1".to_string(),
                "dev".to_string(),
                "eth0".to_string(),
            ],
        );
    }

    #[test]
    fn endpoint_route_args_adds_ipv6_flag_and_32_prefix() {
        let route = EndpointRoute {
            gateway: None,
            device: "en0".to_string(),
        };

        let args = endpoint_route_args("delete", "2001:db8::1234", &route);
        assert_eq!(
            args,
            vec![
                "-6".to_string(),
                "route".to_string(),
                "delete".to_string(),
                "2001:db8::1234/32".to_string(),
                "dev".to_string(),
                "en0".to_string(),
            ],
        );
    }

    #[test]
    fn parse_ip_route_get_output_extracts_gateway_and_device() {
        let output = "198.51.100.10 via 192.168.1.1 dev wlan0 src 192.168.1.50 uid 1000";
        let parsed = parse_ip_route_get_output(output).expect("route should parse");
        assert_eq!(parsed.gateway.as_deref(), Some("192.168.1.1"));
        assert_eq!(parsed.device, "wlan0");
    }

    #[test]
    fn parse_ip_route_get_output_returns_none_when_dev_missing() {
        let output = "198.51.100.10 via 192.168.1.1 src 192.168.1.50";
        assert!(parse_ip_route_get_output(output).is_none());
    }

    #[test]
    fn filter_routes_for_endpoints_removes_endpoint_ip_routes() {
        let filtered = filter_routes_for_endpoints(
            vec![
                "10.0.0.0/24".to_string(),
                "203.0.113.10/32".to_string(),
                "2001:db8::/64".to_string(),
                "2001:db8::10/128".to_string(),
            ],
            &["203.0.113.10".to_string(), "2001:db8::10".to_string()],
        );

        assert_eq!(
            filtered,
            vec!["10.0.0.0/24".to_string(), "2001:db8::/64".to_string()],
        );
    }
}
