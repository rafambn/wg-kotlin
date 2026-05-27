use crate::platform::{
    ensure_required_binaries, ip_literal, is_ipv6_literal, normalize_domains, normalize_items,
    run_command, CleanupHook,
};
use daemon_proto::pb::TunSessionConfig;

const NOT_FOUND_PATTERNS: &[&str] = &[
    "not in table",
    "not found",
    "no such process",
    "can't assign requested address",
];

pub fn configure_session(
    config: &TunSessionConfig,
    interface_name: &str,
) -> Result<CleanupHook, String> {
    ensure_required_binaries(&["ifconfig", "route", "scutil"])?;
    validate_interface_name_for_scutil(interface_name)?;

    let normalized_addresses = normalize_items(&config.addresses);
    let primary_address = normalized_addresses.first().cloned().unwrap_or_default();
    let addresses: Vec<String> = normalized_addresses
        .iter()
        .filter(|address| !is_primary_tun_address(address, &primary_address))
        .cloned()
        .collect();
    let routes = normalize_items(&config.routes);
    let dns_servers = normalize_items(
        &config
            .dns
            .as_ref()
            .map(|dns| dns.servers.clone())
            .unwrap_or_default(),
    );
    let dns_domains = normalize_domains(
        &config
            .dns
            .as_ref()
            .map(|dns| dns.search_domains.clone())
            .unwrap_or_default(),
    );

    let setup_result = (|| -> Result<(), String> {
        if config.mtu > 0 {
            let mtu = config.mtu;
            run_command(
                "apply-mtu",
                "ifconfig",
                &vec![
                    interface_name.to_string(),
                    "mtu".to_string(),
                    mtu.to_string(),
                ],
                None,
                &[],
                &[],
            )?;
        }

        for address in &addresses {
            delete_address(interface_name, address)?;

            let args = if is_ipv6_literal(address) {
                vec![
                    interface_name.to_string(),
                    "inet6".to_string(),
                    address.to_string(),
                    "add".to_string(),
                ]
            } else {
                vec![
                    interface_name.to_string(),
                    "inet".to_string(),
                    address.to_string(),
                    "alias".to_string(),
                ]
            };

            run_command("add-address", "ifconfig", &args, None, &[], &[])?;
        }

        for route in &routes {
            delete_route(interface_name, route)?;
            add_route(interface_name, route)?;
        }

        clear_dns_entries(interface_name)?;
        if !dns_servers.is_empty() && !dns_domains.is_empty() {
            let resolver_path = resolver_path(interface_name);
            let resolver_root_path = resolver_root_path(interface_name);

            run_command(
                "set-dns",
                "scutil",
                &Vec::new(),
                Some(
                    format!(
                        "d.init\nd.add ServerAddresses * {}\nd.add SupplementalMatchDomains * {}\nset {}\nquit\n",
                        dns_servers.join(" "),
                        dns_domains.join(" "),
                        resolver_path,
                    )
                    .as_str(),
                ),
                &[],
                &[],
            )?;

            run_command(
                "set-dns-root",
                "scutil",
                &Vec::new(),
                Some(
                    format!(
                        "d.init\nd.add UserDefinedName {}\nset {}\nquit\n",
                        interface_name, resolver_root_path,
                    )
                    .as_str(),
                ),
                &[],
                &[],
            )?;
        }
        Ok(())
    })();

    if let Err(setup_error) = setup_result {
        return match cleanup_macos_session(&routes, &addresses, interface_name) {
            Ok(()) => Err(setup_error),
            Err(cleanup_error) => Err(format!("{setup_error}; cleanup failed: {cleanup_error}")),
        };
    }

    let cleanup_interface = interface_name.to_string();
    Ok(Box::new(move || {
        cleanup_macos_session(&routes, &addresses, &cleanup_interface)
    }))
}

fn cleanup_macos_session(
    routes: &[String],
    addresses: &[String],
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

    for route in routes.iter().rev() {
        capture_error(delete_route(interface_name, route));
    }
    for address in addresses.iter().rev() {
        capture_error(delete_address(interface_name, address));
    }
    capture_error(clear_dns_entries(interface_name));

    match cleanup_error {
        Some(error) => Err(error),
        None => Ok(()),
    }
}

fn add_route(interface_name: &str, route: &str) -> Result<(), String> {
    run_command(
        "add-route",
        "route",
        &route_args("add", interface_name, route),
        None,
        &[],
        &[],
    )
    .map(|_| ())
}

fn delete_route(interface_name: &str, route: &str) -> Result<(), String> {
    run_command(
        "delete-route",
        "route",
        &route_args("delete", interface_name, route),
        None,
        &[],
        NOT_FOUND_PATTERNS,
    )
    .map(|_| ())
}

fn route_args(command: &str, interface_name: &str, route: &str) -> Vec<String> {
    let family = if is_ipv6_literal(route) {
        "-inet6"
    } else {
        "-inet"
    };
    vec![
        "-n".to_string(),
        family.to_string(),
        command.to_string(),
        "-net".to_string(),
        route.to_string(),
        "-interface".to_string(),
        interface_name.to_string(),
    ]
}

fn delete_address(interface_name: &str, address: &str) -> Result<(), String> {
    let address_literal = ip_literal(address);
    let args = if is_ipv6_literal(address) {
        vec![
            interface_name.to_string(),
            "inet6".to_string(),
            address_literal.to_string(),
            "-alias".to_string(),
        ]
    } else {
        vec![
            interface_name.to_string(),
            "inet".to_string(),
            address_literal.to_string(),
            "-alias".to_string(),
        ]
    };

    run_command(
        "delete-address",
        "ifconfig",
        &args,
        None,
        &[],
        NOT_FOUND_PATTERNS,
    )
    .map(|_| ())
}

fn clear_dns_entries(interface_name: &str) -> Result<(), String> {
    let payload = format!(
        "remove {}\nremove {}\nquit\n",
        resolver_path(interface_name),
        resolver_root_path(interface_name),
    );

    run_command(
        "clear-dns",
        "scutil",
        &Vec::new(),
        Some(payload.as_str()),
        &[],
        &[],
    )
    .map(|_| ())
}

fn resolver_path(interface_name: &str) -> String {
    format!("State:/Network/Interface/{interface_name}/DNS")
}

fn resolver_root_path(interface_name: &str) -> String {
    format!("State:/Network/Interface/{interface_name}")
}

fn validate_interface_name_for_scutil(interface_name: &str) -> Result<(), String> {
    let valid = interface_name.chars().enumerate().all(|(idx, ch)| {
        if idx == 0 {
            ch.is_ascii_alphabetic()
        } else {
            ch.is_ascii_alphanumeric() || ch == '_' || ch == '-' || ch == '.'
        }
    }) && interface_name.len() <= 32;

    if valid {
        Ok(())
    } else {
        Err(format!(
            "interface name contains unsafe characters for scutil: {interface_name}"
        ))
    }
}

fn is_primary_tun_address(address: &str, primary_address: &str) -> bool {
    let (address_ip, address_prefix) = match split_cidr_parts(address) {
        Some(parts) => parts,
        None => return false,
    };
    let (primary_ip, primary_prefix) = match split_cidr_parts(primary_address) {
        Some(parts) => parts,
        None => return false,
    };

    address_ip == primary_ip
        && address_prefix
            .parse::<u16>()
            .ok()
            .zip(primary_prefix.parse::<u16>().ok())
            .map(|(left, right)| left == right)
            .unwrap_or(false)
}

fn split_cidr_parts(cidr: &str) -> Option<(String, String)> {
    let (ip, prefix) = cidr.split_once('/')?;
    let normalized_ip = ip.trim().to_string();
    let normalized_prefix = prefix.trim().to_string();

    if normalized_ip.is_empty() || normalized_prefix.is_empty() {
        return None;
    }

    Some((normalized_ip, normalized_prefix))
}
