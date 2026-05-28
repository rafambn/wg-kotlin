use daemon_proto::pb::{DnsConfig, ip_addr};
use prost::Message;

const DNS_CONFIG_FIXTURE: &[u8] =
    &[0x0a, 0x06, 0x0a, 0x04, 0x01, 0x01, 0x01, 0x01, 0x12, 0x0c, 0x63, 0x6f, 0x72, 0x70, 0x2e, 0x65, 0x78, 0x61, 0x6d, 0x70, 0x6c, 0x65];

#[test]
fn dns_fixture_decodes_and_reencodes() {
    let decoded = DnsConfig::decode(DNS_CONFIG_FIXTURE).expect("fixture should decode");

    assert_eq!(decoded.servers.len(), 1);
    let server = &decoded.servers[0];
    assert!(server.prefix.is_none());
    match &server.ip {
        Some(ip_addr::Ip::V4(bytes)) => assert_eq!(bytes.as_slice(), &[1, 1, 1, 1]),
        _ => panic!("expected v4 server"),
    }
    assert_eq!(decoded.search_domains, vec!["corp.example".to_string()]);

    let encoded = decoded.encode_to_vec();
    assert_eq!(encoded, DNS_CONFIG_FIXTURE);
}
