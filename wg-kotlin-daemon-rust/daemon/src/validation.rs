use std::net::IpAddr;
use std::str::FromStr;
use daemon_proto::pb::{DnsConfig, TunSessionConfig};

const MIN_MTU: i32 = 576;
const MIN_IPV6_MTU: i32 = 1280;
const MAX_MTU: i32 = 65535;
const MAX_DNS_DOMAINS: usize = 64;
const MAX_DNS_SERVERS: usize = 64;
const MAX_ADDRESSES: usize = 64;
const MAX_ROUTES: usize = 256;
const MAX_INTERFACE_NAME_LENGTH: usize = 15;
const MAX_ENDPOINT_LENGTH: usize = 253;
const MAX_CIDR_LENGTH: usize = 64;
const MAX_DOMAIN_LENGTH: usize = 253;

pub fn validate_config(config: &TunSessionConfig) -> Result<(), String> {
    validate_interface_name(&config.interface_name)?;
    validate_addresses(&config.addresses)?;
    validate_routes(&config.routes)?;
    if config.mtu != 0 {
        validate_mtu_for_addresses(config.mtu, &config.addresses)?;
    }
    validate_dns(config.dns.as_ref().unwrap_or(&DnsConfig::default()))?;
    Ok(())
}

fn validate_interface_name(interface_name: &str) -> Result<(), String> {
    if interface_name.len() > MAX_INTERFACE_NAME_LENGTH {
        return Err(format!(
            "interface name must be at most {MAX_INTERFACE_NAME_LENGTH} characters and match `utun[0-9]+`",
        ));
    }
    let Some(suffix) = interface_name.strip_prefix("utun") else {
        return Err(
            "interface name must be at most 15 characters and match `utun[0-9]+`".to_string(),
        );
    };
    if suffix.is_empty() || !suffix.chars().all(|ch| ch.is_ascii_digit()) {
        return Err(
            "interface name must be at most 15 characters and match `utun[0-9]+`".to_string(),
        );
    }
    Ok(())
}

fn validate_mtu(mtu: i32) -> Result<(), String> {
    if !(MIN_MTU..=MAX_MTU).contains(&mtu) {
        return Err(format!("MTU must be between {MIN_MTU} and {MAX_MTU}"));
    }
    Ok(())
}

fn validate_mtu_for_addresses(mtu: i32, addresses: &[String]) -> Result<(), String> {
    validate_mtu(mtu)?;
    let has_ipv6 = addresses
        .iter()
        .map(|address| address.trim().split('/').next().unwrap_or_default())
        .any(is_valid_ipv6);

    if has_ipv6 && mtu < MIN_IPV6_MTU {
        return Err(format!(
            "MTU must be at least {MIN_IPV6_MTU} when IPv6 addresses are configured"
        ));
    }

    Ok(())
}

fn validate_dns(dns: &DnsConfig) -> Result<(), String> {
    let domains: Vec<String> = dns
        .search_domains
        .iter()
        .map(|domain| domain.trim().trim_start_matches('.').to_string())
        .collect();
    let dns_servers: Vec<String> = dns
        .servers
        .iter()
        .map(|server| server.trim().to_string())
        .collect();

    validate_max_count("dns.searchDomains", domains.len(), MAX_DNS_DOMAINS)?;
    validate_max_count("dns.servers", dns_servers.len(), MAX_DNS_SERVERS)?;

    if (domains.is_empty() && !dns_servers.is_empty())
        || (!domains.is_empty() && dns_servers.is_empty())
    {
        return Err("dns must provide both searchDomains and servers, or neither".to_string());
    }

    for (idx, domain) in domains.iter().enumerate() {
        validate_max_length(
            format!("dns.searchDomains[{idx}]").as_str(),
            domain,
            MAX_DOMAIN_LENGTH,
        )?;
        if domain.is_empty() || !is_valid_hostname(domain) {
            return Err(format!("dns.searchDomains[{idx}] must be a valid hostname"));
        }
    }

    for (idx, server) in dns_servers.iter().enumerate() {
        validate_max_length(
            format!("dns.servers[{idx}]").as_str(),
            server,
            MAX_ENDPOINT_LENGTH,
        )?;
        if !is_valid_ipv4(server) && !is_valid_ipv6(server) {
            return Err(format!(
                "dns.servers[{idx}] must be a valid IPv4 or IPv6 address"
            ));
        }
    }

    Ok(())
}

fn validate_addresses(addresses: &[String]) -> Result<(), String> {
    validate_max_count("addresses", addresses.len(), MAX_ADDRESSES)?;
    validate_cidrs("addresses", addresses)
}

fn validate_routes(routes: &[String]) -> Result<(), String> {
    validate_max_count("routes", routes.len(), MAX_ROUTES)?;
    validate_cidrs("routes", routes)
}

fn validate_cidrs(field_name: &str, values: &[String]) -> Result<(), String> {
    for (idx, cidr) in values.iter().enumerate() {
        let normalized = cidr.trim();
        validate_max_length(
            format!("{field_name}[{idx}]").as_str(),
            normalized,
            MAX_CIDR_LENGTH,
        )?;

        let mut parts = normalized.splitn(3, '/');
        let ip_part = parts.next().unwrap_or_default();
        let prefix_part = parts.next().unwrap_or_default();
        let extra_part = parts.next();

        if ip_part.is_empty() || prefix_part.is_empty() || extra_part.is_some() {
            return Err(format!("{field_name}[{idx}] must use CIDR format"));
        }

        let prefix = prefix_part
            .parse::<i32>()
            .map_err(|_| format!("{field_name}[{idx}] prefix must be numeric"))?;

        let ip_text = ip_part.trim();
        let is_v4 = is_valid_ipv4(ip_text);
        let is_v6 = is_valid_ipv6(ip_text);
        if !is_v4 && !is_v6 {
            return Err(format!("{field_name}[{idx}] has invalid IP address"));
        }

        let max_prefix = if is_v4 { 32 } else { 128 };
        if !(0..=max_prefix).contains(&prefix) {
            return Err(format!(
                "{field_name}[{idx}] prefix must be between 0 and {max_prefix}"
            ));
        }
    }

    Ok(())
}

fn validate_max_count(field_name: &str, count: usize, max: usize) -> Result<(), String> {
    if count > max {
        return Err(format!(
            "{field_name} supports at most {max} entries (received: {count})"
        ));
    }
    Ok(())
}

fn validate_max_length(field_name: &str, value: &str, max: usize) -> Result<(), String> {
    if value.len() > max {
        return Err(format!("{field_name} must be at most {max} characters"));
    }
    Ok(())
}

fn is_valid_ipv4(value: &str) -> bool {
    IpAddr::from_str(value)
        .map(|ip| ip.is_ipv4())
        .unwrap_or(false)
}

fn is_valid_ipv6(value: &str) -> bool {
    let ip_part = value.split('%').next().unwrap_or(value);
    IpAddr::from_str(ip_part)
        .map(|ip| ip.is_ipv6())
        .unwrap_or(false)
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
        if !bytes
            .iter()
            .all(|b| b.is_ascii_alphanumeric() || *b == b'-')
        {
            return false;
        }
    }

    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use daemon_proto::pb::TunSessionConfig;

    #[test]
    fn rejects_non_utun_interface() {
        let config = TunSessionConfig {
            interface_name: "wg0".to_string(),
            addresses: vec!["10.0.0.1/24".to_string()],
            ..Default::default()
        };

        let error = validate_config(&config).expect_err("config should be rejected");
        assert!(error.contains("utun"));
    }

    #[test]
    fn rejects_incomplete_dns() {
        let config = TunSessionConfig {
            interface_name: "utun0".to_string(),
            addresses: vec!["10.0.0.1/24".to_string()],
            dns: Some(DnsConfig {
                search_domains: vec!["corp.local".to_string()],
                servers: vec![],
            }),
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
            addresses: vec!["fd00::1/64".to_string()],
            ..Default::default()
        };

        let error = validate_config(&config).expect_err("mtu should be rejected");
        assert!(error.contains("at least 1280"));
    }

    #[test]
    fn accepts_ipv6_scope_id_in_address_and_dns_server() {
        let config = TunSessionConfig {
            interface_name: "utun0".to_string(),
            addresses: vec!["fe80::1%utun0/64".to_string()],
            dns: Some(DnsConfig {
                search_domains: vec!["corp.local".to_string()],
                servers: vec!["fe80::1%utun0".to_string()],
            }),
            ..Default::default()
        };

        validate_config(&config).expect("scope-id should be accepted");
    }

    #[test]
    fn accepts_valid_complete_config() {
        let config = TunSessionConfig {
            interface_name: "utun0".to_string(),
            addresses: vec!["10.0.0.1/24".to_string()],
            routes: vec!["0.0.0.0/0".to_string()],
            dns: Some(DnsConfig {
                search_domains: vec!["corp.local".to_string()],
                servers: vec!["1.1.1.1".to_string(), "2001:4860:4860::8888".to_string()],
            }),
            ..Default::default()
        };

        validate_config(&config).expect("config should be accepted");
    }

    #[test]
    fn accepts_config_without_dns() {
        let config = TunSessionConfig {
            interface_name: "utun0".to_string(),
            addresses: vec!["10.0.0.1/24".to_string()],
            ..Default::default()
        };

        validate_config(&config).expect("config without dns should be accepted");
    }

    #[test]
    fn accepts_config_with_mtu_zero() {
        let config = TunSessionConfig {
            interface_name: "utun0".to_string(),
            addresses: vec!["10.0.0.1/24".to_string()],
            mtu: 0,
            ..Default::default()
        };

        validate_config(&config).expect("config with mtu 0 should be accepted");
    }

    #[test]
    fn rejects_empty_interface_name() {
        let config = TunSessionConfig {
            interface_name: "".to_string(),
            addresses: vec!["10.0.0.1/24".to_string()],
            ..Default::default()
        };

        let error = validate_config(&config).expect_err("config should be rejected");
        assert!(error.contains("utun"));
    }

    #[test]
    fn rejects_interface_name_exceeding_max_length() {
        let config = TunSessionConfig {
            interface_name: "utun1234567890123".to_string(),
            addresses: vec!["10.0.0.1/24".to_string()],
            ..Default::default()
        };

        let error = validate_config(&config).expect_err("config should be rejected");
        assert!(error.contains("utun"));
    }

    #[test]
    fn rejects_too_many_addresses() {
        let config = TunSessionConfig {
            interface_name: "utun0".to_string(),
            addresses: (0..=MAX_ADDRESSES).map(|i| format!("10.0.0.{}/32", i % 256)).collect(),
            ..Default::default()
        };

        let error = validate_config(&config).expect_err("config should be rejected");
        assert!(error.contains("at most"));
    }

    #[test]
    fn rejects_too_many_routes() {
        let config = TunSessionConfig {
            interface_name: "utun0".to_string(),
            addresses: vec!["10.0.0.1/24".to_string()],
            routes: (0..=MAX_ROUTES).map(|i| format!("10.0.0.{}/32", i % 256)).collect(),
            ..Default::default()
        };

        let error = validate_config(&config).expect_err("config should be rejected");
        assert!(error.contains("at most"));
    }

    #[test]
    fn rejects_invalid_cidr_format() {
        let config = TunSessionConfig {
            interface_name: "utun0".to_string(),
            addresses: vec!["10.0.0.1".to_string()],
            ..Default::default()
        };

        let error = validate_config(&config).expect_err("config should be rejected");
        assert!(error.contains("CIDR"));
    }

    #[test]
    fn rejects_invalid_dns_server() {
        let config = TunSessionConfig {
            interface_name: "utun0".to_string(),
            addresses: vec!["10.0.0.1/24".to_string()],
            dns: Some(DnsConfig {
                search_domains: vec!["corp.local".to_string()],
                servers: vec!["not-an-ip".to_string()],
            }),
            ..Default::default()
        };

        let error = validate_config(&config).expect_err("config should be rejected");
        assert!(error.contains("valid IPv4 or IPv6"));
    }

    #[test]
    fn rejects_invalid_search_domain() {
        let config = TunSessionConfig {
            interface_name: "utun0".to_string(),
            addresses: vec!["10.0.0.1/24".to_string()],
            dns: Some(DnsConfig {
                search_domains: vec!["-invalid".to_string()],
                servers: vec!["1.1.1.1".to_string()],
            }),
            ..Default::default()
        };

        let error = validate_config(&config).expect_err("config should be rejected");
        assert!(error.contains("valid hostname"));
    }
}
