use super::EndpointRoute;
use super::dns_macos::{clear_dns_entries, set_dns_entries};
use super::routes_macos::SessionRoutes;
use crate::platform::{CleanupHook, build_and_filter_routes, ips_to_args, normalize_domains};
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
            set_dns_entries(interface_name, &dns_servers, &dns_domains)?;
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
