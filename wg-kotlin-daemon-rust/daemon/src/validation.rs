use crate::ip_util::parse_proto_ip;
use daemon_proto::pb::{DnsConfig, IpAddr, TunSessionConfig};

const MIN_MTU: i32 = 576;
const MIN_IPV6_MTU: i32 = 1280;
const MAX_MTU: i32 = 65535;
const MAX_DNS_SERVERS: usize = 64;
const MAX_ADDRESSES: usize = 64;
const MAX_ROUTES: usize = 256;
const MAX_INTERFACE_NAME_LENGTH: usize = 15;
const MAX_DOMAIN_LENGTH: usize = 253;

pub fn validate_config(config: &TunSessionConfig) -> Result<(), String> {
    validate_interface_name(&config.interface_name)?;
    validate_ip_list("addresses", &config.addresses, true)?;
    validate_max_count("addresses", config.addresses.len(), MAX_ADDRESSES)?;
    validate_ip_list("routes", &config.routes, true)?;
    validate_max_count("routes", config.routes.len(), MAX_ROUTES)?;
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

fn validate_mtu_for_addresses(mtu: i32, addresses: &[IpAddr]) -> Result<(), String> {
    validate_mtu(mtu)?;
    let has_ipv6 = addresses.iter().any(|addr| {
        parse_proto_ip(addr)
            .map(|(ip, _)| ip.is_ipv6())
            .unwrap_or(false)
    });

    if has_ipv6 && mtu < MIN_IPV6_MTU {
        return Err(format!(
            "MTU must be at least {MIN_IPV6_MTU} when IPv6 addresses are configured"
        ));
    }

    Ok(())
}

fn validate_dns(dns: &DnsConfig) -> Result<(), String> {
    validate_max_count("dns.servers", dns.servers.len(), MAX_DNS_SERVERS)?;

    if (dns.search_domains.is_empty() && !dns.servers.is_empty())
        || (!dns.search_domains.is_empty() && dns.servers.is_empty())
    {
        return Err("dns must provide both searchDomains and servers, or neither".to_string());
    }

    for (idx, domain) in dns.search_domains.iter().enumerate() {
        let domain = domain.trim().trim_start_matches('.');
        validate_max_length(
            format!("dns.searchDomains[{idx}]").as_str(),
            domain,
            MAX_DOMAIN_LENGTH,
        )?;
        if domain.is_empty() || !is_valid_hostname(domain) {
            return Err(format!("dns.searchDomains[{idx}] must be a valid hostname"));
        }
    }

    for (idx, server) in dns.servers.iter().enumerate() {
        if server.prefix.is_some() {
            return Err(format!(
                "dns.servers[{idx}] must be a bare IP address, not a CIDR"
            ));
        }
        let Some(_ip) = parse_proto_ip(server) else {
            return Err(format!(
                "dns.servers[{idx}] must be a valid IPv4 or IPv6 address"
            ));
        };
    }

    Ok(())
}

fn validate_ip_list(field_name: &str, values: &[IpAddr], require_prefix: bool) -> Result<(), String> {
    for (idx, addr) in values.iter().enumerate() {
        let (ip, prefix) = parse_proto_ip(addr).ok_or_else(|| {
            format!("{field_name}[{idx}] has an invalid IP address")
        })?;

        if require_prefix {
            let prefix = prefix.ok_or_else(|| {
                format!("{field_name}[{idx}] must use CIDR format (prefix required)")
            })?;

            let max_prefix = if ip.is_ipv4() { 32 } else { 128 };
            if prefix > max_prefix {
                return Err(format!(
                    "{field_name}[{idx}] prefix must be between 0 and {max_prefix}"
                ));
            }
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
    use daemon_proto::pb::{ip_addr, DnsConfig, IpAddr, TunSessionConfig};

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
    fn rejects_non_utun_interface() {
        let config = TunSessionConfig {
            interface_name: "wg0".to_string(),
            addresses: vec![ipv4(&[10, 0, 0, 1], Some(24))],
            ..Default::default()
        };

        let error = validate_config(&config).expect_err("config should be rejected");
        assert!(error.contains("utun"));
    }

    #[test]
    fn rejects_incomplete_dns() {
        let config = TunSessionConfig {
            interface_name: "utun0".to_string(),
            addresses: vec![ipv4(&[10, 0, 0, 1], Some(24))],
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
            dns: Some(DnsConfig {
                search_domains: vec!["corp.local".to_string()],
                servers: vec![ipv4(&[1, 1, 1, 1], None)],
            }),
            ..Default::default()
        };

        validate_config(&config).expect("config should be accepted");
    }

    #[test]
    fn rejects_addresses_without_prefix() {
        let config = TunSessionConfig {
            interface_name: "utun0".to_string(),
            addresses: vec![ipv4(&[10, 0, 0, 1], None)],
            ..Default::default()
        };

        let error = validate_config(&config).expect_err("config should be rejected");
        assert!(error.contains("prefix"));
    }

    #[test]
    fn rejects_dns_server_with_prefix() {
        let config = TunSessionConfig {
            interface_name: "utun0".to_string(),
            addresses: vec![ipv4(&[10, 0, 0, 1], Some(24))],
            dns: Some(DnsConfig {
                search_domains: vec!["corp.local".to_string()],
                servers: vec![ipv4(&[1, 1, 1, 1], Some(32))],
            }),
            ..Default::default()
        };

        let error = validate_config(&config).expect_err("dns should be rejected");
        assert!(error.contains("CIDR"));
    }
}
