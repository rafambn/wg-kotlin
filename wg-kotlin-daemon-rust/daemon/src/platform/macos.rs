use super::EndpointRoute;
use super::routes_macos::SessionRoutes;
use crate::platform::{CleanupHook, build_and_filter_routes, ips_to_args, normalize_domains, run_command};
use daemon_proto::pb::TunSessionConfig;

pub fn configure_session(config: &TunSessionConfig, interface_name: &str, endpoint_routes: Vec<EndpointRoute>) -> Result<CleanupHook, String> {
    let (_, filtered_routes, endpoint_routes) = build_and_filter_routes(config, interface_name, endpoint_routes)?;
    let mut routes = SessionRoutes::new();

    let dns_servers = ips_to_args(config.dns.as_ref().map(|dns| &dns.servers[..]).unwrap_or(&[]));
    let dns_domains = normalize_domains(&config.dns.as_ref().map(|dns| dns.search_domains.clone()).unwrap_or_default());

    let setup_result = (|| -> Result<(), String> {
        routes.install(&filtered_routes, &endpoint_routes).map_err(|error| format!("failed to configure routes: {error}"))?;

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

    let cleanup_interface = interface_name.to_string();
    let mut cleanup = move || {
        let route_result = routes.restore();
        let dns_result = clear_dns_entries(&cleanup_interface);
        match (route_result, dns_result) {
            (Ok(()), result) | (result, Ok(())) => result,
            (Err(routes), Err(dns)) => Err(format!("{routes}; {dns}")),
        }
    };
    match setup_result {
        Ok(()) => Ok(Box::new(cleanup)),
        Err(error) => match cleanup() {
            Ok(()) => Err(error),
            Err(cleanup_error) => Err(format!("{error}; cleanup failed: {cleanup_error}")),
        },
    }
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
