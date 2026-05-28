use daemon::session::{is_supported_interface_name, parse_primary_address};
use daemon_proto::pb::{ip_addr, IpAddr, TunSessionConfig};

fn ipv4(bytes: &[u8], prefix: Option<u32>) -> IpAddr {
    IpAddr {
        ip: Some(ip_addr::Ip::V4(bytes.to_vec())),
        prefix,
    }
}

fn ipv6(bytes: &[u8], prefix: Option<u32>) -> IpAddr {
    IpAddr {
        ip: Some(ip_addr::Ip::V6(bytes.to_vec())),
        prefix,
    }
}

#[test]
fn parse_primary_address_extracts_ipv4_and_prefix() {
    let config = TunSessionConfig {
        addresses: vec![ipv4(&[10, 0, 0, 1], Some(24))],
        ..Default::default()
    };
    let (ip, prefix) = parse_primary_address(&config).unwrap();
    assert!(ip.is_ipv4());
    assert_eq!(prefix, 24);
}

#[test]
fn parse_primary_address_extracts_ipv6_and_prefix() {
    let config = TunSessionConfig {
        addresses: vec![ipv6(
            &[0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1],
            Some(64),
        )],
        ..Default::default()
    };
    let (ip, prefix) = parse_primary_address(&config).unwrap();
    assert!(ip.is_ipv6());
    assert_eq!(prefix, 64);
}

#[test]
fn parse_primary_address_rejects_missing_prefix() {
    let config = TunSessionConfig {
        addresses: vec![ipv4(&[10, 0, 0, 1], None)],
        ..Default::default()
    };
    assert!(parse_primary_address(&config).is_err());
}

#[test]
fn parse_primary_address_rejects_prefix_too_large_for_ipv4() {
    let config = TunSessionConfig {
        addresses: vec![ipv4(&[10, 0, 0, 1], Some(33))],
        ..Default::default()
    };
    assert!(parse_primary_address(&config).is_err());
}

#[test]
fn parse_primary_address_rejects_prefix_too_large_for_ipv6() {
    let config = TunSessionConfig {
        addresses: vec![ipv6(
            &[0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1],
            Some(129),
        )],
        ..Default::default()
    };
    assert!(parse_primary_address(&config).is_err());
}

#[test]
fn parse_primary_address_rejects_empty_addresses() {
    let config = TunSessionConfig {
        addresses: vec![],
        ..Default::default()
    };
    assert!(parse_primary_address(&config).is_err());
}

#[test]
fn is_supported_interface_name_accepts_utun_names() {
    assert!(is_supported_interface_name("utun0"));
    assert!(is_supported_interface_name("utun99"));
    assert!(is_supported_interface_name("utun123"));
}

#[test]
fn is_supported_interface_name_rejects_non_utun_names() {
    assert!(!is_supported_interface_name("wg0"));
    assert!(!is_supported_interface_name("eth0"));
    assert!(!is_supported_interface_name("utun"));
    assert!(!is_supported_interface_name("utunabc"));
    assert!(!is_supported_interface_name("utun1a"));
}
