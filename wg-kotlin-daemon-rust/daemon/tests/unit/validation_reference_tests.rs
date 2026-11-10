use super::*;
use daemon_proto::pb::{DnsConfig, Ip, ip};

// Frozen pre-refactor validator: compare acceptance and the first failure.
// MTU text is standardized; all other error text remains unchanged.
mod reference {
    use crate::validation::parse_proto_ip;
    fn parse_proto_cidr(value: &daemon_proto::pb::Cidr) -> Option<(std::net::IpAddr, u32)> {
        Some((parse_proto_ip(value.ip.as_ref()?)?, value.prefix))
    }
    use daemon_proto::pb::{Cidr, DnsConfig, TunSessionConfig};

    const MIN_MTU: i32 = 576;
    const MIN_IPV6_MTU: i32 = 1280;
    const MAX_MTU: i32 = 65535;
    const MAX_DNS_SERVERS: usize = 64;
    const MAX_DNS_DOMAINS: usize = 64;
    const MAX_ADDRESSES: usize = 64;
    const MAX_ROUTES: usize = 256;
    const MAX_INTERFACE_NAME_LENGTH: usize = 15;
    const MAX_DOMAIN_LENGTH: usize = 253;

    pub fn validate_config(config: &TunSessionConfig) -> Result<(), String> {
        validate_interface_name(&config.interface_name)?;
        validate_non_empty("addresses", &config.addresses)?;
        validate_cidr_list("addresses", &config.addresses)?;
        validate_max_count("addresses", config.addresses.len(), MAX_ADDRESSES)?;
        validate_cidr_list("peer_allowed_ips", &config.peer_allowed_ips)?;
        validate_max_count("peer_allowed_ips", config.peer_allowed_ips.len(), MAX_ROUTES)?;
        if config.mtu != 0 {
            validate_mtu_for_addresses(config.mtu, &config.addresses)?;
        }
        validate_dns(config.dns.as_ref().unwrap_or(&DnsConfig::default()))?;
        Ok(())
    }

    pub(crate) fn validate_interface_name(interface_name: &str) -> Result<(), String> {
        if interface_name.len() > MAX_INTERFACE_NAME_LENGTH {
            return Err(format!("interface name must be at most {MAX_INTERFACE_NAME_LENGTH} characters and match `utun[0-9]+`",));
        }
        let Some(suffix) = interface_name.strip_prefix("utun") else {
            return Err("interface name must be at most 15 characters and match `utun[0-9]+`".to_string());
        };
        if suffix.is_empty() || !suffix.chars().all(|ch| ch.is_ascii_digit()) {
            return Err("interface name must be at most 15 characters and match `utun[0-9]+`".to_string());
        }
        #[cfg(target_os = "macos")]
        if suffix.parse::<u32>().ok().and_then(|index| index.checked_add(1)).is_none() {
            return Err(format!("macOS interface index must be between 0 and {}", u32::MAX - 1));
        }
        Ok(())
    }

    fn validate_mtu_for_addresses(mtu: i32, addresses: &[Cidr]) -> Result<(), String> {
        if !(MIN_MTU..=MAX_MTU).contains(&mtu) {
            return Err(format!("MTU must be between {MIN_MTU} and {MAX_MTU}"));
        }
        let has_ipv6 = addresses.iter().any(|addr| parse_proto_cidr(addr).map(|(ip, _)| ip.is_ipv6()).unwrap_or(false));

        if has_ipv6 && mtu < MIN_IPV6_MTU {
            return Err(format!("MTU must be at least {MIN_IPV6_MTU} when IPv6 addresses are configured"));
        }

        Ok(())
    }

    fn validate_dns(dns: &DnsConfig) -> Result<(), String> {
        validate_max_count("dns.servers", dns.servers.len(), MAX_DNS_SERVERS)?;
        validate_max_count("dns.searchDomains", dns.search_domains.len(), MAX_DNS_DOMAINS)?;

        if (dns.search_domains.is_empty() && !dns.servers.is_empty()) || (!dns.search_domains.is_empty() && dns.servers.is_empty()) {
            return Err("dns must provide both searchDomains and servers, or neither".to_string());
        }

        for (idx, domain) in dns.search_domains.iter().enumerate() {
            validate_max_length(format!("dns.searchDomains[{idx}]").as_str(), domain, MAX_DOMAIN_LENGTH)?;
            let domain = domain.trim().trim_start_matches('.');
            if domain.is_empty() || !is_valid_hostname(domain) {
                return Err(format!("dns.searchDomains[{idx}] must be a valid hostname"));
            }
        }

        for (idx, server) in dns.servers.iter().enumerate() {
            if parse_proto_ip(server).is_none() {
                return Err(format!("dns.servers[{idx}] must be a valid IPv4 or IPv6 address"));
            }
        }

        Ok(())
    }

    fn validate_cidr_list(field_name: &str, values: &[Cidr]) -> Result<(), String> {
        for (idx, addr) in values.iter().enumerate() {
            let (ip, prefix) = parse_proto_cidr(addr).ok_or_else(|| format!("{field_name}[{idx}] has an invalid IP address"))?;

            let max_prefix = if ip.is_ipv4() { 32 } else { 128 };
            if prefix > max_prefix {
                return Err(format!("{field_name}[{idx}] prefix must be between 0 and {max_prefix}"));
            }
        }
        Ok(())
    }

    fn validate_non_empty(field_name: &str, values: &[Cidr]) -> Result<(), String> {
        if values.is_empty() {
            return Err(format!("{field_name} must contain at least one entry"));
        }
        Ok(())
    }

    fn validate_max_count(field_name: &str, count: usize, max: usize) -> Result<(), String> {
        if count > max {
            return Err(format!("{field_name} supports at most {max} entries (received: {count})"));
        }
        Ok(())
    }

    fn validate_max_length(field_name: &str, value: &str, max: usize) -> Result<(), String> {
        if value.len() > max {
            return Err(format!("{field_name} must be at most {max} characters"));
        }
        Ok(())
    }

    fn is_valid_hostname(domain: &str) -> bool {
        if domain.is_empty() || domain.len() > MAX_DOMAIN_LENGTH {
            return false;
        }

        for label in domain.split('.') {
            if label.is_empty() || label.len() > 63 {
                return false;
            }
            let bytes = label.as_bytes();
            if bytes.first() == Some(&b'-') || bytes.last() == Some(&b'-') {
                return false;
            }
            if !bytes.iter().all(|b| b.is_ascii_alphanumeric() || *b == b'-') {
                return false;
            }
        }

        true
    }
}

#[test]
fn preserves_validation_results_across_boundary_and_combined_inputs() {
    let ipv4 = Ip { value: Some(ip::Value::V4(vec![10, 0, 0, 1])) };
    let ipv6 = Ip { value: Some(ip::Value::V6(vec![0; 16])) };
    let ips = [None, Some(Ip { value: None }), Some(Ip { value: Some(ip::Value::V4(vec![1; 3])) }), Some(ipv4.clone()), Some(ipv6)];
    let names = ["", "wg0", "utun", "utun1a", "utun0", "utun01", "utun000000000001", "utun4294967294", "utun4294967295"];
    let domains = ["", " .corp.test ", "-corp.test", "corp-.test", "corp..test", "é.test", "corp_test", "corp.test"];
    let mut state = 11235813_u64;
    for _ in 0..10000 {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        let pick = |shift, len| ((state >> shift) as usize) % len;
        let address = Cidr { ip: ips[pick(8, ips.len())].clone(), prefix: [0, 24, 32, 33, 64, 128, 129][pick(16, 7)] };
        let domain = if pick(24, 4) == 0 { "x".repeat([63, 64, 253, 254][pick(32, 4)]) } else { domains[pick(32, domains.len())].into() };
        let config = TunSessionConfig {
            interface_name: names[pick(0, names.len())].into(),
            addresses: vec![address.clone(); [0, 1, 64, 65][pick(40, 4)]],
            peer_allowed_ips: vec![address; [0, 1, 256, 257][pick(48, 4)]],
            mtu: [i32::MIN, 0, 575, 576, 1279, 1280, 65535, 65536][pick(52, 8)],
            dns: Some(DnsConfig {
                search_domains: vec![domain; [0, 1, 64, 65][pick(12, 4)]],
                servers: vec![ipv4.clone(); [0, 1, 64, 65][pick(28, 4)]],
            }),
            ..Default::default()
        };
        let actual = validate_config(&config).map(|_| ()).map_err(|error| error.to_string());
        let expected = reference::validate_config(&config);
        if expected.as_ref().is_err_and(|error| error.starts_with("MTU ")) {
            assert!(actual.as_ref().is_err_and(|error| error.starts_with("MTU ")), "actual={actual:?}, expected={expected:?}, config={config:?}");
        } else {
            assert_eq!(actual, expected, "{config:?}");
        }
    }
}

#[test]
fn every_mtu_value_through_the_wire_limit_preserves_acceptance_for_each_address_family() {
    let ipv4 = Cidr { ip: Some(Ip { value: Some(ip::Value::V4(vec![10, 0, 0, 1])) }), prefix: 24 };
    let ipv6 = Cidr { ip: Some(Ip { value: Some(ip::Value::V6(vec![0; 16])) }), prefix: 64 };
    let cases = [
        (vec![ipv4.clone()], "MTU must be between 576 and 65535"),
        (vec![ipv6.clone()], "MTU must be between 1280 and 65535"),
        (vec![ipv4.clone(), ipv6.clone()], "MTU must be between 1280 and 65535"),
        (vec![ipv6, ipv4], "MTU must be between 1280 and 65535"),
    ];
    for (addresses, expected_error) in cases {
        let mut config = TunSessionConfig { interface_name: "utun0".into(), addresses, ..Default::default() };
        for mtu in [i32::MIN, -65536, -1280, -576, -1].into_iter().chain(0..=65536).chain([i32::MAX]) {
            config.mtu = mtu;
            let expected = reference::validate_config(&config);
            let actual = validate_config(&config);
            assert_eq!(actual.is_ok(), expected.is_ok(), "mtu={mtu}, addresses={:?}, reference={expected:?}", config.addresses);
            match actual {
                Ok(config) => assert_eq!(config.mtu.map(NonZeroU16::get), (mtu != 0).then_some(mtu as u16)),
                Err(error) => assert_eq!(error.to_string(), expected_error, "mtu={mtu}, addresses={:?}", config.addresses),
            }
        }
    }
}

#[test]
fn mtu_zero_leaves_the_platform_default_and_explicit_values_are_retained() {
    let mut config = TunSessionConfig {
        interface_name: "utun0".into(),
        addresses: vec![Cidr { ip: Some(Ip { value: Some(ip::Value::V4(vec![10, 0, 0, 1])) }), prefix: 24 }],
        ..Default::default()
    };
    for (requested, expected) in [(0, None), (576, Some(576)), (1280, Some(1280)), (1400, Some(1400)), (65535, Some(65535))] {
        config.mtu = requested;
        let actual = validate_config(&config).unwrap();
        assert_eq!(actual.mtu.map(NonZeroU16::get), expected);
    }
}

#[test]
fn produces_typed_networks_and_preserves_dns_order() {
    let config = TunSessionConfig {
        interface_name: "utun0".into(),
        addresses: vec![Cidr { ip: Some(Ip { value: Some(ip::Value::V4(vec![10, 0, 0, 1])) }), prefix: 24 }],
        peer_endpoints: vec![
            Ip { value: Some(ip::Value::V4(vec![203, 0, 113, 7])) },
            Ip { value: None },
            Ip { value: Some(ip::Value::V6(vec![0; 16])) },
        ],
        dns: Some(DnsConfig {
            search_domains: [" .z.test ", "a.test", "z.test"].map(str::to_string).to_vec(),
            servers: [vec![1, 1, 1, 1], vec![8, 8, 8, 8], vec![1, 1, 1, 1]]
                .into_iter()
                .map(|bytes| Ip { value: Some(ip::Value::V4(bytes)) })
                .collect(),
        }),
        ..Default::default()
    };
    let actual = validate_config(&config).unwrap();
    assert_eq!(actual.addresses, [("10.0.0.1".parse().unwrap(), 24)]);
    assert_eq!(actual.peer_endpoints, [Some("203.0.113.7".parse().unwrap()), None, Some("::".parse().unwrap())]);
    assert_eq!(actual.dns_domains, ["z.test", "a.test"]);
    assert_eq!(actual.dns_servers, ["1.1.1.1", "8.8.8.8"]);
}

#[test]
fn dns_hostname_validation_matches_the_previous_policy() {
    let mut config = TunSessionConfig {
        interface_name: "utun0".into(),
        addresses: vec![Cidr { ip: Some(Ip { value: Some(ip::Value::V4(vec![10, 0, 0, 1])) }), prefix: 24 }],
        dns: Some(DnsConfig { search_domains: vec![], servers: vec![Ip { value: Some(ip::Value::V4(vec![9, 9, 9, 9])) }] }),
        ..Default::default()
    };
    let mut compare = |domain: String| {
        config.dns.as_mut().unwrap().search_domains = vec![domain];
        assert_eq!(validate_config(&config).map(|_| ()).map_err(|error| error.to_string()), reference::validate_config(&config), "{config:?}");
    };
    for byte in 0..=127u8 {
        let ch = char::from(byte);
        for domain in [format!("{ch}abc"), format!("abc{ch}"), format!("a{ch}b.example"), format!("ab.{ch}cd"), format!(" .a{ch}b ")] {
            compare(domain);
        }
    }
    let alphabet = ['a', 'Z', '0', '-', '.', '_', ' ', '\0', 'é'];
    for length in 0..=4 {
        for mut index in 0..alphabet.len().pow(length) {
            let domain: String = (0..length)
                .map(|_| {
                    let ch = alphabet[index % alphabet.len()];
                    index /= alphabet.len();
                    ch
                })
                .collect();
            compare(domain);
        }
    }
    for length in [0, 1, 62, 63, 64, 65, 252, 253, 254] {
        let label = "a".repeat(length);
        for domain in [label.clone(), format!("{label}.test"), format!(" .{label} "), format!("{label}-"), format!("-{label}")] {
            compare(domain);
        }
    }
    let longest = ["a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(61)].join(".");
    assert_eq!(longest.len(), 253);
    for domain in [longest.clone(), format!(".{longest}"), format!("{longest}e"), "\u{a0}example.test\u{a0}".into(), "...".into()] {
        compare(domain);
    }
}
