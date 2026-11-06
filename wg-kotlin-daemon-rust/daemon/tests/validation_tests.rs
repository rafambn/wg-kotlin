use daemon::validation::validate_config;
use daemon_proto::pb::{Cidr, DnsConfig, Ip, TunSessionConfig, ip};

fn ip_v4(bytes: &[u8]) -> Ip {
    Ip { value: Some(ip::Value::V4(bytes.to_vec())) }
}

fn ip_v6(bytes: &[u8]) -> Ip {
    Ip { value: Some(ip::Value::V6(bytes.to_vec())) }
}

fn cidr_v4(bytes: &[u8], prefix: u32) -> Cidr {
    Cidr { ip: Some(ip_v4(bytes)), prefix }
}

fn cidr_v6(bytes: &[u8], prefix: u32) -> Cidr {
    Cidr { ip: Some(ip_v6(bytes)), prefix }
}

#[test]
fn rejects_non_utun_interface() {
    let config = TunSessionConfig { interface_name: "wg0".to_string(), addresses: vec![cidr_v4(&[10, 0, 0, 1], 24)], ..Default::default() };

    let error = validate_config(&config).expect_err("config should be rejected");
    assert!(error.contains("utun"));
}

#[cfg(target_os = "macos")]
#[test]
fn checks_macos_interface_index_before_native_session_start() {
    for (name, accepted) in [
        ("utun0", true),
        ("utun00000000000", true),
        ("utun4294967294", true),
        ("utun04294967294", true),
        ("utun4294967295", false),
        ("utun04294967295", false),
        ("utun4294967296", false),
        ("utun99999999999", false),
    ] {
        let mut config = TunSessionConfig { interface_name: name.to_string(), addresses: vec![cidr_v4(&[10, 0, 0, 1], 24)], ..Default::default() };
        assert_eq!(validate_config(&config).is_ok(), accepted, "{name}");
        assert_eq!(daemon::session::is_supported_interface_name(name), accepted, "{name}");
        if !accepted {
            // A regression fails route resolution before it can create a TUN.
            config.peer_endpoints = vec![Ip { value: None }];
            let error = daemon::session::SessionManager::start(&config).err().expect("invalid index must be rejected");
            assert!(error.contains("4294967294"), "{name}: {error}");
        }
    }
}

#[test]
fn rejects_incomplete_dns() {
    let config = TunSessionConfig {
        interface_name: "utun0".to_string(),
        addresses: vec![cidr_v4(&[10, 0, 0, 1], 24)],
        dns: Some(DnsConfig { search_domains: vec!["corp.local".to_string()], servers: vec![] }),
        ..Default::default()
    };

    let error = validate_config(&config).expect_err("dns should be rejected");
    assert!(error.contains("both searchDomains and servers"));
}

#[test]
fn rejects_ipv6_mtu_below_minimum() {
    let config = TunSessionConfig {
        interface_name: "utun0".to_string(),
        mtu: 1000,
        addresses: vec![cidr_v6(&[0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1], 64)],
        ..Default::default()
    };

    let error = validate_config(&config).expect_err("mtu should be rejected");
    assert!(error.contains("at least 1280"));
}

#[test]
fn accepts_valid_complete_config() {
    let config = TunSessionConfig {
        interface_name: "utun0".to_string(),
        addresses: vec![cidr_v4(&[10, 0, 0, 1], 24)],
        peer_allowed_ips: vec![cidr_v4(&[0, 0, 0, 0], 0)],
        dns: Some(DnsConfig { search_domains: vec!["corp.local".to_string()], servers: vec![ip_v4(&[1, 1, 1, 1])] }),
        ..Default::default()
    };

    validate_config(&config).expect("config should be accepted");
}

    #[test]
    fn rejects_empty_addresses() {
        let config = TunSessionConfig { interface_name: "utun0".to_string(), addresses: vec![], ..Default::default() };

        let error = validate_config(&config).expect_err("config should be rejected");
        assert!(error.contains("at least one"));
    }

    #[test]
    fn rejects_invalid_cidr_ip() {
    let config = TunSessionConfig {
        interface_name: "utun0".to_string(),
        addresses: vec![Cidr { ip: None, prefix: 24 }],
        ..Default::default()
    };

    let error = validate_config(&config).expect_err("config should be rejected");
    assert!(error.contains("invalid IP"));
}

#[test]
fn rejects_dns_server_with_prefix() {
    // Test is now semantic: Cidr used as DNS server — which is no longer possible
    // since DnsConfig.servers is Vec<Ip>. This test validates an Ip with no value
    // is rejected.
    let config = TunSessionConfig {
        interface_name: "utun0".to_string(),
        addresses: vec![cidr_v4(&[10, 0, 0, 1], 24)],
        dns: Some(DnsConfig {
            search_domains: vec!["corp.local".to_string()],
            servers: vec![Ip { value: None }],
        }),
        ..Default::default()
    };

    let error = validate_config(&config).expect_err("dns should be rejected");
    assert!(error.contains("valid"));
}

#[test]
fn limits_dns_domains_before_normalization() {
    let mut config = TunSessionConfig {
        interface_name: "utun0".to_string(),
        addresses: vec![cidr_v4(&[10, 0, 0, 1], 24)],
        dns: Some(DnsConfig {
            search_domains: (0..64).map(|index| format!("domain{index}.test")).collect(),
            servers: vec![ip_v4(&[1, 1, 1, 1])],
        }),
        ..Default::default()
    };
    validate_config(&config).unwrap();
    config.dns.as_mut().unwrap().search_domains.push("domain0.test".to_string());
    assert!(validate_config(&config).unwrap_err().contains("dns.searchDomains supports at most 64"));
}

#[test]
fn bounds_raw_domain_length_before_trimming() {
    let domain = format!("{}.{}.{}.{}", "a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(61));
    let mut config = TunSessionConfig {
        interface_name: "utun0".to_string(),
        addresses: vec![cidr_v4(&[10, 0, 0, 1], 24)],
        dns: Some(DnsConfig { search_domains: vec![domain.clone()], servers: vec![ip_v4(&[1, 1, 1, 1])] }),
        ..Default::default()
    };
    validate_config(&config).unwrap();
    config.dns.as_mut().unwrap().search_domains[0] = format!(" {domain}");
    assert!(validate_config(&config).unwrap_err().contains("dns.searchDomains[0] must be at most 253"));
    config.dns.as_mut().unwrap().search_domains[0] = " .corp.test ".to_string();
    validate_config(&config).unwrap();
}
