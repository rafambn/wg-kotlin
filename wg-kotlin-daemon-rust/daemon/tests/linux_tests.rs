use daemon::platform::linux::{
    endpoint_route_args, filter_routes_for_endpoints, parse_ip_route_get_output, route_args,
};
use daemon::platform::EndpointRoute;

#[cfg(target_os = "linux")]
mod linux_tests {
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
