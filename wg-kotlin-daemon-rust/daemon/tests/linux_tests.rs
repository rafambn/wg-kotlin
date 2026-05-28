use daemon::platform::linux::filter_routes_for_endpoints;
use route_manager::Route;
use std::net::IpAddr;

#[cfg(target_os = "linux")]
mod linux_tests {
    use super::*;

    #[test]
    fn filter_routes_for_endpoints_removes_endpoint_ip_routes() {
        let routes = vec![
            Route::new("10.0.0.0".parse::<IpAddr>().unwrap(), 24).with_if_name("utun0".to_string()),
            Route::new("203.0.113.10".parse::<IpAddr>().unwrap(), 32).with_if_name("utun0".to_string()),
            Route::new("2001:db8::".parse::<IpAddr>().unwrap(), 64).with_if_name("utun0".to_string()),
            Route::new("2001:db8::10".parse::<IpAddr>().unwrap(), 128).with_if_name("utun0".to_string()),
        ];
        let endpoint_ips = vec!["203.0.113.10".parse::<IpAddr>().unwrap(), "2001:db8::10".parse::<IpAddr>().unwrap()];

        let filtered = filter_routes_for_endpoints(&routes, &endpoint_ips);

        assert_eq!(filtered.len(), 2);
        assert_eq!(filtered[0].destination(), "10.0.0.0".parse::<IpAddr>().unwrap());
        assert_eq!(filtered[1].destination(), "2001:db8::".parse::<IpAddr>().unwrap());
    }
}
