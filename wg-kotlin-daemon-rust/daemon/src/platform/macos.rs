use crate::platform::{
    CleanupHook, add_routes_with_predelete, build_and_filter_routes, into_cleanup_hook, ips_to_args, normalize_domains, run_command,
};
use daemon_proto::pb::TunSessionConfig;

pub fn configure_session(config: &TunSessionConfig, interface_name: &str) -> Result<CleanupHook, String> {
    let (mut mgr, filtered_routes, endpoint_routes) = build_and_filter_routes(config, interface_name)?;

    let dns_servers = ips_to_args(config.dns.as_ref().map(|dns| &dns.servers[..]).unwrap_or(&[]));
    let dns_domains = normalize_domains(&config.dns.as_ref().map(|dns| dns.search_domains.clone()).unwrap_or_default());

    let setup_result = (|| -> Result<(), String> {
        add_routes_with_predelete(&mut mgr, &filtered_routes, &endpoint_routes)?;

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

    into_cleanup_hook(setup_result, filtered_routes, endpoint_routes, interface_name.to_string(), clear_dns_entries)
}

fn clear_dns_entries(interface_name: &str) -> Result<(), String> {
    let payload = format!("remove {}\nremove {}\nquit\n", resolver_path(interface_name), resolver_root_path(interface_name));

    run_command("clear-dns", "scutil", &Vec::new(), Some(payload.as_str()), &[], &[]).map(|_| ())
}

fn resolver_path(interface_name: &str) -> String {
    format!("State:/Network/Interface/{interface_name}/DNS")
}

fn resolver_root_path(interface_name: &str) -> String {
    format!("State:/Network/Interface/{interface_name}")
}
