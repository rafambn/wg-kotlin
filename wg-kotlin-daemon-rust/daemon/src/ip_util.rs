use daemon_proto::pb::{ip_addr, IpAddr};
use std::net::IpAddr as StdIpAddr;

pub fn parse_proto_ip(addr: &IpAddr) -> Option<(StdIpAddr, Option<u32>)> {
    let ip = match addr.ip.as_ref()? {
        ip_addr::Ip::V4(bytes) if bytes.len() == 4 => {
            let mut arr = [0u8; 4];
            arr.copy_from_slice(bytes);
            StdIpAddr::V4(arr.into())
        }
        ip_addr::Ip::V6(bytes) if bytes.len() == 16 => {
            let mut arr = [0u8; 16];
            arr.copy_from_slice(bytes);
            StdIpAddr::V6(arr.into())
        }
        _ => return None,
    };
    Some((ip, addr.prefix))
}

pub fn proto_ip_to_cidr(addr: &IpAddr) -> String {
    let (ip, prefix) = parse_proto_ip(addr).expect("invalid proto IpAddr");
    match prefix {
        Some(p) => format!("{}/{}", ip, p),
        None => ip.to_string(),
    }
}
