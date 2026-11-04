use base64::engine::{DecodePaddingMode, GeneralPurpose, GeneralPurposeConfig};
use base64::{Engine as _, alphabet};
use boringtun::noise::{Tunn, TunnResult};
use boringtun::x25519::{PublicKey, StaticSecret};
use rand_core::OsRng;
use std::net::IpAddr;
use std::sync::{Arc, Mutex};
use zeroize::Zeroizing;

const MAX_IP_PACKET_SIZE: usize = u16::MAX as usize;
const DATA_PACKET_OVERHEAD: usize = 32;
const MIN_PACKET_BUFFER_SIZE: usize = 148;
const MAX_PACKET_BUFFER_SIZE: usize = MAX_IP_PACKET_SIZE + DATA_PACKET_OVERHEAD;
const MAX_PEER_INDEX: u32 = 0x00ff_ffff;

// Preserve base64 0.13's support for keys with or without padding.
const BASE64_ENGINE: GeneralPurpose = GeneralPurpose::new(
    &alphabet::STANDARD,
    GeneralPurposeConfig::new().with_decode_padding_mode(DecodePaddingMode::Indifferent),
);

#[uniffi::export]
pub fn generate_secret_key() -> Vec<u8> {
    let key = Zeroizing::new(StaticSecret::random_from_rng(OsRng).to_bytes());
    key.to_vec()
}

#[uniffi::export]
pub fn generate_public_key(secret_key: Vec<u8>) -> Option<Vec<u8>> {
    let secret_key = Zeroizing::new(secret_key);
    if secret_key.len() != 32 {
        return None;
    }

    let mut key = Zeroizing::new([0u8; 32]);
    key.copy_from_slice(&secret_key);
    let private = StaticSecret::from(*key);
    let public = PublicKey::from(&private);
    Some(public.to_bytes().to_vec())
}

#[uniffi::export]
pub fn convert_x25519_key_to_hex(key: Vec<u8>) -> Option<String> {
    let key = Zeroizing::new(key);
    if key.len() != 32 {
        return None;
    }

    Some(hex::encode(key.as_slice()))
}

#[uniffi::export]
pub fn convert_x25519_key_to_base64(key: Vec<u8>) -> Option<String> {
    let key = Zeroizing::new(key);
    if key.len() != 32 {
        return None;
    }

    Some(BASE64_ENGINE.encode(key.as_slice()))
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum TunnelError {
    #[error("peer index exceeds 24 bits")]
    InvalidIndex,
    #[error("invalid base64 key")]
    InvalidBase64Key,
    #[error("packet exceeds the supported size")]
    PacketTooLarge,
    #[error("requested buffer exceeds the supported size")]
    BufferTooLarge,
    #[error("invalid source IP address")]
    InvalidSourceIp,
    #[error("internal tunnel error")]
    Internal,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum TunnelPacketResult {
    Done,
    Error { code: u32 },
    WriteToNetwork { packet: Vec<u8> },
    WriteToTunnelV4 { packet: Vec<u8> },
    WriteToTunnelV6 { packet: Vec<u8> },
}

/// A WireGuard session with a fixed reusable packet buffer.
///
/// Packet methods treat `dst_size` as a bounded hint for the slice passed to
/// BoringTun, increasing the slice to meet protocol requirements without
/// resizing the allocation.
///
/// If a panic poisons the state mutex, later operations return
/// [`TunnelError::Internal`]. Recreate the session rather than reusing its state.
#[derive(uniffi::Object)]
pub struct TunnelSession {
    state: Mutex<TunnelState>,
}

struct TunnelState {
    tunnel: Tunn,
    // Fixed allocation shared by all operations and erased when the session drops.
    buffer: Zeroizing<Vec<u8>>,
}

#[uniffi::export]
impl TunnelSession {
    #[uniffi::constructor]
    pub fn create_new_tunnel(
        arg_secret_key: String,
        arg_public_key: String,
        arg_preshared_key: Option<String>,
        keep_alive: u16,
        index: u32,
    ) -> Result<Arc<Self>, TunnelError> {
        let secret_key = Zeroizing::new(arg_secret_key);
        let public_key = Zeroizing::new(arg_public_key);
        let preshared_key = arg_preshared_key.map(Zeroizing::new);

        // BoringTun reserves the low 8 bits with `index << 8`.
        if index > MAX_PEER_INDEX {
            return Err(TunnelError::InvalidIndex);
        }

        let private =
            StaticSecret::from(*parse_key_bytes(&secret_key).ok_or(TunnelError::InvalidBase64Key)?);
        let public =
            PublicKey::from(*parse_key_bytes(&public_key).ok_or(TunnelError::InvalidBase64Key)?);
        let preshared = match preshared_key.as_ref() {
            Some(v) => Some(parse_key_bytes(v).ok_or(TunnelError::InvalidBase64Key)?),
            None => None,
        };

        let tunnel = Tunn::new(
            private,
            public,
            preshared.as_deref().copied(),
            if keep_alive == 0 {
                None
            } else {
                Some(keep_alive)
            },
            index,
            None,
        );

        Ok(Arc::new(Self {
            state: Mutex::new(TunnelState {
                tunnel,
                buffer: Zeroizing::new(vec![0u8; MAX_PACKET_BUFFER_SIZE]),
            }),
        }))
    }

    pub fn encrypt_raw_packet(
        &self,
        src: Vec<u8>,
        dst_size: u32,
    ) -> Result<TunnelPacketResult, TunnelError> {
        if src.len() > MAX_IP_PACKET_SIZE {
            return Err(TunnelError::PacketTooLarge);
        }
        let minimum_size = (src.len() + DATA_PACKET_OVERHEAD).max(MIN_PACKET_BUFFER_SIZE);
        let dst_size = packet_buffer_size(dst_size, minimum_size)?;
        let mut state = self.state.lock().map_err(|_| TunnelError::Internal)?;
        let TunnelState { tunnel, buffer } = &mut *state;
        Ok(map_tunn_result(
            tunnel.encapsulate(&src, &mut buffer[..dst_size]),
        ))
    }

    pub fn decrypt_to_raw_packet(
        &self,
        src: Vec<u8>,
        dst_size: u32,
        source_ip: String,
    ) -> Result<TunnelPacketResult, TunnelError> {
        if src.len() > MAX_PACKET_BUFFER_SIZE {
            return Err(TunnelError::PacketTooLarge);
        }
        let source_ip: IpAddr = source_ip
            .parse()
            .map_err(|_| TunnelError::InvalidSourceIp)?;
        // An empty input drains plaintext queued by earlier encapsulate calls.
        let minimum_size = if src.is_empty() {
            MAX_PACKET_BUFFER_SIZE
        } else {
            src.len().max(MIN_PACKET_BUFFER_SIZE)
        };
        let dst_size = packet_buffer_size(dst_size, minimum_size)?;
        let mut state = self.state.lock().map_err(|_| TunnelError::Internal)?;
        let TunnelState { tunnel, buffer } = &mut *state;
        Ok(map_tunn_result(tunnel.decapsulate(
            Some(source_ip),
            &src,
            &mut buffer[..dst_size],
        )))
    }

    pub fn run_periodic_task(&self, dst_size: u32) -> Result<TunnelPacketResult, TunnelError> {
        let dst_size = packet_buffer_size(dst_size, MIN_PACKET_BUFFER_SIZE)?;
        let mut state = self.state.lock().map_err(|_| TunnelError::Internal)?;
        let TunnelState { tunnel, buffer } = &mut *state;
        Ok(map_tunn_result(
            tunnel.update_timers(&mut buffer[..dst_size]),
        ))
    }
}

fn packet_buffer_size(requested_size: u32, minimum_size: usize) -> Result<usize, TunnelError> {
    let requested_size = requested_size as usize;
    if requested_size > MAX_PACKET_BUFFER_SIZE {
        return Err(TunnelError::BufferTooLarge);
    }
    Ok(requested_size.max(minimum_size))
}

fn parse_key_bytes(encoded: &str) -> Option<Zeroizing<[u8; 32]>> {
    // A 32-byte key has 43 Base64 characters, or 44 with padding.
    if !(43..=44).contains(&encoded.len()) {
        return None;
    }
    let mut key = Zeroizing::new([0u8; 32]);
    let decoded_size = BASE64_ENGINE.decode_slice(encoded, &mut *key).ok()?;
    if decoded_size != key.len() {
        return None;
    }
    Some(key)
}

fn map_tunn_result(result: TunnResult<'_>) -> TunnelPacketResult {
    match result {
        TunnResult::Done => TunnelPacketResult::Done,
        TunnResult::Err(err) => TunnelPacketResult::Error { code: err as u32 },
        TunnResult::WriteToNetwork(packet) => TunnelPacketResult::WriteToNetwork {
            packet: packet.to_vec(),
        },
        TunnResult::WriteToTunnelV4(packet, _) => TunnelPacketResult::WriteToTunnelV4 {
            packet: packet.to_vec(),
        },
        TunnResult::WriteToTunnelV6(packet, _) => TunnelPacketResult::WriteToTunnelV6 {
            packet: packet.to_vec(),
        },
    }
}

#[cfg(test)]
mod tests;

uniffi::setup_scaffolding!();
