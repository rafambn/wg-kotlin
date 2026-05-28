use daemon::validation::validate_config;
use daemon_proto::pb::{DnsConfig, IpAddr, TunSessionConfig, ip_addr};

fn ipv4(bytes: &[u8], prefix: Option<u32>) -> IpAddr {
    IpAddr { ip: Some(ip_addr::Ip::V4(bytes.to_vec())), prefix }
}

fn ipv6(bytes: &[u8], prefix: Option<u32>) -> IpAddr {
    IpAddr { ip: Some(ip_addr::Ip::V6(bytes.to_vec())), prefix }
}

#[test]
fn rejects_non_utun_interface() {
    let config = TunSessionConfig { interface_name: "wg0".to_string(), addresses: vec![ipv4(&[10, 0, 0, 1], Some(24))], ..Default::default() };

    let error = validate_config(&config).expect_err("config should be rejected");
    assert!(error.contains("utun"));
}

#[test]
fn rejects_incomplete_dns() {
    let config = TunSessionConfig {
        interface_name: "utun0".to_string(),
        addresses: vec![ipv4(&[10, 0, 0, 1], Some(24))],
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
        addresses: vec![ipv6(&[0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1], Some(64))],
        ..Default::default()
    };

    let error = validate_config(&config).expect_err("mtu should be rejected");
    assert!(error.contains("at least 1280"));
}

#[test]
fn accepts_valid_complete_config() {
    let config = TunSessionConfig {
        interface_name: "utun0".to_string(),
        addresses: vec![ipv4(&[10, 0, 0, 1], Some(24))],
        routes: vec![ipv4(&[0, 0, 0, 0], Some(0))],
        dns: Some(DnsConfig { search_domains: vec!["corp.local".to_string()], servers: vec![ipv4(&[1, 1, 1, 1], None)] }),
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
    fn rejects_addresses_without_prefix() {
    let config = TunSessionConfig { interface_name: "utun0".to_string(), addresses: vec![ipv4(&[10, 0, 0, 1], None)], ..Default::default() };

    let error = validate_config(&config).expect_err("config should be rejected");
    assert!(error.contains("prefix"));
}

#[test]
fn rejects_dns_server_with_prefix() {
    let config = TunSessionConfig {
        interface_name: "utun0".to_string(),
        addresses: vec![ipv4(&[10, 0, 0, 1], Some(24))],
        dns: Some(DnsConfig { search_domains: vec!["corp.local".to_string()], servers: vec![ipv4(&[1, 1, 1, 1], Some(32))] }),
        ..Default::default()
    };

    let error = validate_config(&config).expect_err("dns should be rejected");
    assert!(error.contains("CIDR"));
}
