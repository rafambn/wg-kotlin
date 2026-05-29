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
