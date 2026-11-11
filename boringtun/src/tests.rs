use super::*;
use boringtun::noise::errors::WireGuardError;
use boringtun::noise::rate_limiter::RateLimiter;

const INITIATOR_KEY: [u8; 32] = [1; 32];
const RESPONDER_KEY: [u8; 32] = [2; 32];
const INITIATOR_IP: &str = "192.0.2.1";
const RESPONDER_IP: &str = "192.0.2.2";

#[test]
fn base64_key_format_remains_compatible() {
    let encoded = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    let key = [0u8; 32];

    assert_eq!(
        convert_x25519_key_to_base64(key.to_vec()).as_deref(),
        Some(encoded),
    );
    assert_eq!(parse_key_bytes(encoded).as_deref(), Some(&key));
    assert_eq!(
        parse_key_bytes(encoded.trim_end_matches('=')).as_deref(),
        Some(&key),
    );
}

#[test]
fn invalid_key_encodings_are_rejected() {
    for encoded in [
        String::new(),
        "A".repeat(42),
        BASE64_ENGINE.encode([0u8; 31]),
        "A".repeat(44), // Decodes to 33 bytes, not 32.
        "A".repeat(45),
        "A".repeat(1_000_000),
        "!".repeat(44),
    ] {
        assert!(parse_key_bytes(&encoded).is_none());
    }

    let valid_key = BASE64_ENGINE.encode(INITIATOR_KEY);
    for (private, public, preshared) in [
        ("invalid".into(), valid_key.clone(), None),
        (valid_key.clone(), "invalid".into(), None),
        (valid_key.clone(), valid_key.clone(), Some("invalid".into())),
    ] {
        assert!(matches!(
            TunnelSession::create_new_tunnel(private, public, preshared, 0, 1),
            Err(TunnelError::InvalidBase64Key),
        ));
    }
}

#[test]
fn peer_indexes_preserve_all_24_bits_and_reject_truncation() {
    let private_key = BASE64_ENGINE.encode(INITIATOR_KEY);
    let public_key =
        BASE64_ENGINE.encode(PublicKey::from(&StaticSecret::from(RESPONDER_KEY)).as_bytes());
    for index in [0, 0x00ff_ffff] {
        let session = TunnelSession::create_new_tunnel(
            private_key.clone(),
            public_key.clone(),
            None,
            0,
            index,
        )
        .unwrap();
        let initiation = network_packet(session.encrypt_raw_packet(ipv4_packet(20), 0).unwrap());
        let sender_index = u32::from_le_bytes(initiation[4..8].try_into().unwrap());
        assert_eq!(sender_index >> 8, index);
    }
    for index in [0x0100_0000, 0x0100_0001, u32::MAX] {
        assert!(matches!(
            TunnelSession::create_new_tunnel(
                private_key.clone(),
                public_key.clone(),
                None,
                0,
                index,
            ),
            Err(TunnelError::InvalidIndex),
        ));
    }
}

#[test]
fn malformed_datagram_returns_a_typed_packet_error() {
    let (_, responder) = tunnel_pair(None);
    let result = responder
        .decrypt_to_raw_packet(vec![0xff; 32], 0, INITIATOR_IP.into())
        .unwrap();
    assert_eq!(
        result,
        TunnelPacketResult::Error {
            code: WireGuardError::InvalidPacket as u32,
        }
    );
    assert_eq!(
        responder.run_periodic_task(0).unwrap(),
        TunnelPacketResult::Done
    );
}

#[test]
fn preshared_key_supports_handshake_and_packet_roundtrip() {
    let (initiator, responder) = tunnel_pair(Some(BASE64_ENGINE.encode([3u8; 32])));
    let packet = ipv4_packet(20);
    let initiation = network_packet(initiator.encrypt_raw_packet(packet.clone(), 0).unwrap());
    finish_handshake(&initiator, &responder, initiation);
    let encrypted = network_packet(
        initiator
            .decrypt_to_raw_packet(Vec::new(), 0, RESPONDER_IP.into())
            .unwrap(),
    );
    let decrypted = responder
        .decrypt_to_raw_packet(encrypted, 0, INITIATOR_IP.into())
        .unwrap();
    assert_eq!(decrypted, TunnelPacketResult::WriteToTunnelV4 { packet });
}

#[test]
fn short_buffers_support_handshake_queued_packets_and_established_sessions() {
    let (initiator, responder) = tunnel_pair(None);
    let initiator_buffer = initiator.state.lock().unwrap().buffer.as_ptr();
    let responder_buffer = responder.state.lock().unwrap().buffer.as_ptr();
    let packet = ipv4_packet(MAX_IP_PACKET_SIZE);
    let initiation = network_packet(initiator.encrypt_raw_packet(packet.clone(), 0).unwrap());
    assert_eq!(initiation.len(), 148);
    finish_handshake(&initiator, &responder, initiation);

    // Empty input must accommodate the large packet queued before the handshake.
    let encrypted = network_packet(
        initiator
            .decrypt_to_raw_packet(Vec::new(), 0, RESPONDER_IP.into())
            .unwrap(),
    );
    assert_eq!(encrypted.len(), MAX_PACKET_BUFFER_SIZE);
    let decrypted = responder
        .decrypt_to_raw_packet(encrypted, 0, INITIATOR_IP.into())
        .unwrap();
    assert_eq!(decrypted, TunnelPacketResult::WriteToTunnelV4 { packet });

    // This formerly panicked while holding the tunnel mutex.
    let packet = ipv4_packet(20);
    let encrypted = network_packet(initiator.encrypt_raw_packet(packet.clone(), 1).unwrap());
    let decrypted = responder
        .decrypt_to_raw_packet(encrypted, 1, INITIATOR_IP.into())
        .unwrap();
    assert_eq!(decrypted, TunnelPacketResult::WriteToTunnelV4 { packet });
    assert!(initiator.run_periodic_task(0).is_ok());
    assert!(responder.run_periodic_task(0).is_ok());
    assert_eq!(
        initiator.state.lock().unwrap().buffer.as_ptr(),
        initiator_buffer,
    );
    assert_eq!(
        responder.state.lock().unwrap().buffer.as_ptr(),
        responder_buffer,
    );
}

#[test]
fn excessive_sizes_are_rejected_without_damaging_the_session() {
    let (initiator, _) = tunnel_pair(None);
    assert!(matches!(
        initiator.encrypt_raw_packet(ipv4_packet(20), u32::MAX),
        Err(TunnelError::BufferTooLarge),
    ));
    assert!(matches!(
        initiator.decrypt_to_raw_packet(Vec::new(), u32::MAX, RESPONDER_IP.into()),
        Err(TunnelError::BufferTooLarge),
    ));
    assert!(matches!(
        initiator.run_periodic_task(u32::MAX),
        Err(TunnelError::BufferTooLarge),
    ));
    assert!(matches!(
        initiator.encrypt_raw_packet(vec![0; MAX_IP_PACKET_SIZE + 1], 0),
        Err(TunnelError::PacketTooLarge),
    ));
    assert!(matches!(
        initiator.decrypt_to_raw_packet(
            vec![0; MAX_PACKET_BUFFER_SIZE + 1],
            0,
            RESPONDER_IP.into(),
        ),
        Err(TunnelError::PacketTooLarge),
    ));
    let initiation = network_packet(initiator.encrypt_raw_packet(ipv4_packet(20), 0).unwrap());
    assert_eq!(initiation.len(), 148);
}

#[test]
fn invalid_source_ip_is_rejected_without_damaging_the_session() {
    let (initiator, _) = tunnel_pair(None);
    assert!(matches!(
        initiator.decrypt_to_raw_packet(Vec::new(), 0, "not-an-ip".into()),
        Err(TunnelError::InvalidSourceIp),
    ));
    assert!(initiator.run_periodic_task(0).is_ok());
}

#[test]
fn cookie_challenge_authenticates_ipv4_and_ipv6_source_addresses() {
    for (source_ip, other_ip, responder_ip) in [
        ("192.0.2.1", "192.0.2.3", "192.0.2.2"),
        ("2001:db8::1", "2001:db8::3", "2001:db8::2"),
    ] {
        let (initiator, responder) = tunnel_pair(None);
        let private = StaticSecret::from(RESPONDER_KEY);
        let public = PublicKey::from(&private);
        // Always demand a cookie, without wall-clock timing or a packet flood.
        responder.state.lock().unwrap().tunnel = Tunn::new(
            private,
            PublicKey::from(&StaticSecret::from(INITIATOR_KEY)),
            None,
            None,
            2,
            Some(Arc::new(RateLimiter::new(&public, 0))),
        );

        let initiation = network_packet(initiator.encrypt_raw_packet(ipv4_packet(20), 0).unwrap());
        let challenge = network_packet(
            responder
                .decrypt_to_raw_packet(initiation, 0, source_ip.into())
                .unwrap(),
        );
        assert_eq!(challenge.len(), 64);
        let accepted = initiator
            .decrypt_to_raw_packet(challenge, 0, responder_ip.into())
            .unwrap();
        assert_eq!(accepted, TunnelPacketResult::Done);

        // Force the retry so the test need not wait for the handshake timer.
        let mut buffer = [0u8; MIN_PACKET_BUFFER_SIZE];
        let retry = network_packet(map_tunn_result(
            initiator
                .state
                .lock()
                .unwrap()
                .tunnel
                .format_handshake_initiation(&mut buffer, true),
        ));
        let wrong_source = network_packet(
            responder
                .decrypt_to_raw_packet(retry.clone(), 0, other_ip.into())
                .unwrap(),
        );
        assert_eq!(wrong_source.len(), 64);

        let response = network_packet(
            responder
                .decrypt_to_raw_packet(retry, 0, source_ip.into())
                .unwrap(),
        );
        assert_eq!(response.len(), 92);
        let keepalive = network_packet(
            initiator
                .decrypt_to_raw_packet(response, 0, responder_ip.into())
                .unwrap(),
        );
        let done = responder
            .decrypt_to_raw_packet(keepalive, 0, source_ip.into())
            .unwrap();
        assert_eq!(done, TunnelPacketResult::Done);
    }
}

fn tunnel_pair(preshared_key: Option<String>) -> (Arc<TunnelSession>, Arc<TunnelSession>) {
    let initiator = TunnelSession::create_new_tunnel(
        BASE64_ENGINE.encode(INITIATOR_KEY),
        BASE64_ENGINE.encode(PublicKey::from(&StaticSecret::from(RESPONDER_KEY)).as_bytes()),
        preshared_key.clone(),
        0,
        1,
    )
    .unwrap();
    let responder = TunnelSession::create_new_tunnel(
        BASE64_ENGINE.encode(RESPONDER_KEY),
        BASE64_ENGINE.encode(PublicKey::from(&StaticSecret::from(INITIATOR_KEY)).as_bytes()),
        preshared_key,
        0,
        2,
    )
    .unwrap();
    (initiator, responder)
}

fn finish_handshake(initiator: &TunnelSession, responder: &TunnelSession, initiation: Vec<u8>) {
    let response = network_packet(
        responder
            .decrypt_to_raw_packet(initiation, 0, INITIATOR_IP.into())
            .unwrap(),
    );
    assert_eq!(response.len(), 92);
    let keepalive = network_packet(
        initiator
            .decrypt_to_raw_packet(response, 0, RESPONDER_IP.into())
            .unwrap(),
    );
    let done = responder
        .decrypt_to_raw_packet(keepalive, 0, INITIATOR_IP.into())
        .unwrap();
    assert_eq!(done, TunnelPacketResult::Done);
}

fn network_packet(result: TunnelPacketResult) -> Vec<u8> {
    match result {
        TunnelPacketResult::WriteToNetwork { packet } => packet,
        other => panic!("expected a network packet, got {other:?}"),
    }
}

fn ipv4_packet(size: usize) -> Vec<u8> {
    let mut packet = vec![0u8; size];
    packet[0] = 0x45;
    packet[2..4].copy_from_slice(&(size as u16).to_be_bytes());
    packet[8] = 64;
    packet[12..16].copy_from_slice(&[10, 0, 0, 1]);
    packet[16..20].copy_from_slice(&[10, 0, 0, 2]);
    packet
}
