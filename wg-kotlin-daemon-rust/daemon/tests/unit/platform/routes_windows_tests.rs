use super::*;

#[test]
fn host_exception_preserves_all_optional_attribute_combinations_including_zero() {
    for endpoint in ["198.51.100.10", "2001:db8::10"].map(|ip| ip.parse::<IpAddr>().unwrap()) {
        for zero in [false, true] {
            for mask in 0..32 {
                let mut original = Route::new(if endpoint.is_ipv4() { "0.0.0.0" } else { "::" }.parse().unwrap(), 0);
                if mask & 1 != 0 {
                    let gateway = match (endpoint.is_ipv4(), zero) {
                        (true, false) => "192.0.2.1",
                        (true, true) => "0.0.0.0",
                        (false, false) => "fe80::1",
                        (false, true) => "::",
                    };
                    original = original.with_gateway(gateway.parse().unwrap());
                }
                if mask & 2 != 0 {
                    original = original.with_if_name(if zero { "" } else { "interface-ç-接口" }.to_owned());
                }
                if mask & 4 != 0 {
                    original = original.with_if_index(if zero { 0 } else { 12 });
                }
                if mask & 8 != 0 {
                    original = original.with_metric(if zero { 0 } else { 71 });
                }
                if mask & 16 != 0 {
                    original = original.with_luid(if zero { 0 } else { 0x6008000000000 });
                }
                let actual = EndpointRoute::from_route(endpoint, original.clone()).0;
                assert_eq!(actual.destination(), endpoint);
                assert_eq!(actual.prefix(), if endpoint.is_ipv4() { 32 } else { 128 });
                assert_eq!(actual.gateway(), original.gateway());
                assert_eq!(actual.if_name(), original.if_name());
                assert_eq!(actual.if_index(), original.if_index());
                assert_eq!(actual.metric(), original.metric());
                assert_eq!(actual.luid(), original.luid());
            }
        }
    }
}

#[test]
fn preserves_ipv6_gateway_interface_luid_and_metric() {
    let endpoint: IpAddr = "2001:db8::10".parse().unwrap();
    let original =
        Route::new("::".parse().unwrap(), 0).with_gateway("fe80::1".parse().unwrap()).with_if_index(12).with_luid(0x6008000000000).with_metric(71);
    assert_eq!(
        EndpointRoute::from_route(endpoint, original).0,
        Route::new(endpoint, 128).with_gateway("fe80::1".parse().unwrap()).with_if_index(12).with_luid(0x6008000000000).with_metric(71)
    );
}

#[test]
fn pin_identity_uses_native_row_selectors_and_normalizes_an_absent_gateway() {
    for endpoint in ["198.51.100.10", "2001:db8::10"].map(|ip| ip.parse::<IpAddr>().unwrap()) {
        let original = EndpointRoute::from_route(endpoint, Route::new(endpoint, 0).with_if_index(12).with_luid(0x6008000000000));
        let zero = if endpoint.is_ipv4() { IpAddr::from([0; 4]) } else { IpAddr::from([0; 16]) };
        let same = EndpointRoute::from_route(
            endpoint,
            Route::new(endpoint, 0).with_if_index(99).with_luid(0x6008000000000).with_if_name("renamed".into()).with_metric(71).with_gateway(zero),
        );
        assert!(original.same_slot(&same));
        let changed_interface = EndpointRoute::from_route(endpoint, Route::new(endpoint, 0).with_luid(0x6009000000000));
        assert!(!original.same_slot(&changed_interface));
        let gateway: IpAddr = if endpoint.is_ipv4() { "192.0.2.1" } else { "fe80::1" }.parse().unwrap();
        let changed_gateway = EndpointRoute::from_route(endpoint, Route::new(endpoint, 0).with_luid(0x6008000000000).with_gateway(gateway));
        assert!(!original.same_slot(&changed_gateway));
    }
}
