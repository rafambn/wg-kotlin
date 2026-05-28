use crate::platform::{
    add_routes_with_predelete, build_and_filter_routes, into_cleanup_hook, CleanupHook,
    ips_to_args, normalize_domains, run_command,
};
use daemon_proto::pb::TunSessionConfig;

pub fn configure_session(config: &TunSessionConfig, interface_name: &str) -> Result<CleanupHook, String> {
    let (mut mgr, filtered_routes, endpoint_routes) = build_and_filter_routes(config, interface_name)?;

    let dns_servers = ips_to_args(config.dns.as_ref().map(|dns| &dns.servers[..]).unwrap_or(&[]));
    let dns_domains: Vec<String> = normalize_domains(&config.dns.as_ref().map(|dns| dns.search_domains.clone()).unwrap_or_default())
        .into_iter()
        .map(|domain| format!("~{domain}"))
        .collect();

    let setup_result = (|| -> Result<(), String> {
        add_routes_with_predelete(&mut mgr, &filtered_routes, &endpoint_routes)?;

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

    into_cleanup_hook(setup_result, filtered_routes, endpoint_routes, interface_name.to_string(), revert_dns)
}

fn revert_dns(interface_name: &str) -> Result<(), String> {
    run_command("revert-dns", "resolvectl", &["revert".to_string(), interface_name.to_string()], None, &[], &[]).map(|_| ())
}
