use crate::platform::{
    CleanupHook, cidrs_to_args, ensure_required_binaries, ips_to_args, is_ipv6_literal, normalize_domains, run_command,
};
use daemon_proto::pb::TunSessionConfig;

const NOT_FOUND_PATTERNS: &[&str] = &["not in table", "not found", "no such process", "can't assign requested address"];

pub fn configure_session(config: &TunSessionConfig, interface_name: &str) -> Result<CleanupHook, String> {
    ensure_required_binaries(&["route", "scutil"])?;
    validate_interface_name_for_scutil(interface_name)?;

    let routes = cidrs_to_args(&config.routes);
    let dns_servers = ips_to_args(config.dns.as_ref().map(|dns| &dns.servers[..]).unwrap_or(&[]));
    let dns_domains = normalize_domains(&config.dns.as_ref().map(|dns| dns.search_domains.clone()).unwrap_or_default());

    let setup_result = (|| -> Result<(), String> {
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
                Some(format!("d.init\nd.add UserDefinedName {}\nset {}\nquit\n", interface_name, resolver_root_path,).as_str()),
                &[],
                &[],
            )?;
        }
        Ok(())
    })();

    if let Err(setup_error) = setup_result {
        return match cleanup_macos_session(&routes, interface_name) {
            Ok(()) => Err(setup_error),
            Err(cleanup_error) => Err(format!("{setup_error}; cleanup failed: {cleanup_error}")),
        };
    }

    let cleanup_interface = interface_name.to_string();
    Ok(Box::new(move || cleanup_macos_session(&routes, &cleanup_interface)))
}

fn cleanup_macos_session(routes: &[String], interface_name: &str) -> Result<(), String> {
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
    capture_error(clear_dns_entries(interface_name));

    match cleanup_error {
        Some(error) => Err(error),
        None => Ok(()),
    }
}

fn add_route(interface_name: &str, route: &str) -> Result<(), String> {
    run_command("add-route", "route", &route_args("add", interface_name, route), None, &[], &[]).map(|_| ())
}

fn delete_route(interface_name: &str, route: &str) -> Result<(), String> {
    run_command("delete-route", "route", &route_args("delete", interface_name, route), None, &[], NOT_FOUND_PATTERNS).map(|_| ())
}

fn route_args(command: &str, interface_name: &str, route: &str) -> Vec<String> {
    let family = if is_ipv6_literal(route) { "-inet6" } else { "-inet" };
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

fn clear_dns_entries(interface_name: &str) -> Result<(), String> {
    let payload = format!("remove {}\nremove {}\nquit\n", resolver_path(interface_name), resolver_root_path(interface_name),);

    run_command("clear-dns", "scutil", &Vec::new(), Some(payload.as_str()), &[], &[]).map(|_| ())
}

fn resolver_path(interface_name: &str) -> String {
    format!("State:/Network/Interface/{interface_name}/DNS")
}

fn resolver_root_path(interface_name: &str) -> String {
    format!("State:/Network/Interface/{interface_name}")
}

fn validate_interface_name_for_scutil(interface_name: &str) -> Result<(), String> {
    let valid = interface_name
        .chars()
        .enumerate()
        .all(|(idx, ch)| if idx == 0 { ch.is_ascii_alphabetic() } else { ch.is_ascii_alphanumeric() || ch == '_' || ch == '-' || ch == '.' })
        && interface_name.len() <= 32;

    if valid { Ok(()) } else { Err(format!("interface name contains unsafe characters for scutil: {interface_name}")) }
}
