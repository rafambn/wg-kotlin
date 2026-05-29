use daemon_proto::pb::{Cidr, Ip, ip};
use std::net::IpAddr as StdIpAddr;

pub fn parse_proto_ip(addr: &Ip) -> Option<StdIpAddr> {
    let ip = match addr.value.as_ref()? {
        ip::Value::V4(bytes) if bytes.len() == 4 => {
            let mut arr = [0u8; 4];
            arr.copy_from_slice(bytes);
            StdIpAddr::V4(arr.into())
        }
        ip::Value::V6(bytes) if bytes.len() == 16 => {
            let mut arr = [0u8; 16];
            arr.copy_from_slice(bytes);
            StdIpAddr::V6(arr.into())
        }
        _ => return None,
    };
    Some(ip)
}

pub fn parse_proto_cidr(addr: &Cidr) -> Option<(StdIpAddr, u32)> {
    let ip = parse_proto_ip(addr.ip.as_ref()?)?;
    Some((ip, addr.prefix))
}

pub fn proto_ip_to_string(addr: &Ip) -> String {
    let ip = parse_proto_ip(addr).expect("invalid proto Ip");
    ip.to_string()
}

pub fn proto_cidr_to_string(addr: &Cidr) -> String {
    let (ip, prefix) = parse_proto_cidr(addr).expect("invalid proto Cidr");
    format!("{}/{}", ip, prefix)
}
