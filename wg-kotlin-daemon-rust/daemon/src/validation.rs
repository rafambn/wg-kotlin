use anyhow::{Context, Result, ensure};
use daemon_proto::pb::{Cidr, Ip, TunSessionConfig, ip};
use itertools::Itertools;
use std::{net::IpAddr, num::NonZeroU16};

#[derive(Debug)]
pub struct Config {
    pub(crate) interface_name: String,
    pub(crate) addresses: Vec<(IpAddr, u8)>,
    pub(crate) peer_allowed_ips: Vec<(IpAddr, u8)>,
    // Invalid endpoints remain None until route lookup, before TUN creation.
    pub(crate) peer_endpoints: Vec<Option<IpAddr>>,
    pub(crate) mtu: Option<NonZeroU16>,
    pub(crate) dns_servers: Vec<String>,
    pub(crate) dns_domains: Vec<String>,
}

pub fn validate_config(config: &TunSessionConfig) -> Result<Config> {
    let interface_name = validate_interface_name(&config.interface_name)?;
    ensure!(!config.addresses.is_empty(), "addresses must contain at least one entry");
    let addresses = networks("addresses", &config.addresses, 64)?;
    let peer_allowed_ips = networks("peer_allowed_ips", &config.peer_allowed_ips, 256)?;
    let minimum_mtu = if addresses.iter().any(|(ip, _)| ip.is_ipv6()) { 1280 } else { 576 };
    ensure!(config.mtu == 0 || (minimum_mtu..=65535).contains(&config.mtu), "MTU must be between {minimum_mtu} and 65535");
    let mut servers = Vec::new();
    let mut domains = Vec::new();
    if let Some(dns) = &config.dns {
        ensure!(dns.servers.len() <= 64, "dns.servers supports at most 64 entries (received: {})", dns.servers.len());
        ensure!(dns.search_domains.len() <= 64, "dns.searchDomains supports at most 64 entries (received: {})", dns.search_domains.len());
        ensure!(dns.servers.is_empty() == dns.search_domains.is_empty(), "dns must provide both searchDomains and servers, or neither");
        for (index, domain) in dns.search_domains.iter().enumerate() {
            ensure!(domain.len() <= 253, "dns.searchDomains[{index}] must be at most 253 characters");
            let domain = domain.trim().trim_start_matches('.');
            ensure!(hostname_validator::is_valid(domain), "dns.searchDomains[{index}] must be a valid hostname");
            domains.push(domain.to_string());
        }
        for (index, address) in dns.servers.iter().enumerate() {
            servers.push(parse_proto_ip(address).with_context(|| format!("dns.servers[{index}] must be a valid IPv4 or IPv6 address"))?.to_string());
        }
    }
    Ok(Config {
        interface_name,
        addresses,
        peer_allowed_ips,
        peer_endpoints: config.peer_endpoints.iter().map(parse_proto_ip).collect(),
        mtu: NonZeroU16::new(config.mtu as u16),
        dns_servers: servers.into_iter().unique().collect(),
        dns_domains: domains.into_iter().unique().collect(),
    })
}

pub fn validate_interface_name(name: &str) -> Result<String> {
    let suffix = name.strip_prefix("utun").unwrap_or("");
    ensure!(
        name.len() <= 15 && !suffix.is_empty() && suffix.bytes().all(|c| c.is_ascii_digit()),
        "interface name must be at most 15 characters and match `utun[0-9]+`"
    );
    if cfg!(target_os = "macos") {
        // Name validation permits at most eleven ASCII digits, which fit in u64.
        let index = suffix.parse::<u64>()?;
        ensure!(index < u64::from(u32::MAX), "macOS interface index must be between 0 and {}", u32::MAX - 1);
        Ok(format!("utun{index}"))
    } else {
        Ok(name.into())
    }
}

fn networks(field: &str, values: &[Cidr], max: usize) -> Result<Vec<(IpAddr, u8)>> {
    let mut result = Vec::new();
    for (index, value) in values.iter().enumerate() {
        let ip = value.ip.as_ref().and_then(parse_proto_ip).with_context(|| format!("{field}[{index}] has an invalid IP address"))?;
        let max_prefix = if ip.is_ipv4() { 32 } else { 128 };
        ensure!(value.prefix <= max_prefix, "{field}[{index}] prefix must be between 0 and {max_prefix}");
        result.push((ip, value.prefix as u8));
    }
    ensure!(values.len() <= max, "{field} supports at most {max} entries (received: {})", values.len());
    Ok(result)
}

fn parse_proto_ip(address: &Ip) -> Option<IpAddr> {
    match address.value.as_ref()? {
        ip::Value::V4(bytes) => Some(IpAddr::from(<[u8; 4]>::try_from(bytes.as_slice()).ok()?)),
        ip::Value::V6(bytes) => Some(IpAddr::from(<[u8; 16]>::try_from(bytes.as_slice()).ok()?)),
    }
}

#[cfg(test)]
#[path = "../tests/unit/validation_reference_tests.rs"]
mod tests;
