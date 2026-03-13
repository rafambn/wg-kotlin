use boringtun::noise::{Tunn, TunnResult};
use boringtun::x25519::{PublicKey, StaticSecret};
use rand_core::OsRng;
use std::sync::{Arc, Mutex};

#[uniffi::export]
pub fn generate_secret_key() -> Vec<u8> {
    StaticSecret::random_from_rng(OsRng).to_bytes().to_vec()
}

#[uniffi::export]
pub fn generate_public_key(secret_key: Vec<u8>) -> Option<Vec<u8>> {
    if secret_key.len() != 32 {
        return None;
    }

    let mut key = [0u8; 32];
    key.copy_from_slice(&secret_key);
    let private = StaticSecret::from(key);
    let public = PublicKey::from(&private);
    Some(public.to_bytes().to_vec())
}

#[uniffi::export]
pub fn convert_x25519_key_to_hex(key: Vec<u8>) -> Option<String> {
    if key.len() != 32 {
        return None;
    }

    let mut key32 = [0u8; 32];
    key32.copy_from_slice(&key);
    Some(hex::encode(key32))
}

#[uniffi::export]
pub fn convert_x25519_key_to_base64(key: Vec<u8>) -> Option<String> {
    if key.len() != 32 {
        return None;
    }

    let mut key32 = [0u8; 32];
    key32.copy_from_slice(&key);
    Some(base64::encode(key32))
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum TunnelError {
    #[error("invalid base64 key")]
    InvalidBase64Key,
    #[error("internal tunnel error")]
    Internal,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct TunnelPacketResult {
    pub op: u8,
    pub size: u32,
    pub packet: Vec<u8>,
}

#[derive(uniffi::Object)]
pub struct TunnelSession {
    tunnel: Mutex<Tunn>,
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
        let private = StaticSecret::from(parse_key_bytes(&arg_secret_key)
            .ok_or(TunnelError::InvalidBase64Key)?);
        let public = PublicKey::from(parse_key_bytes(&arg_public_key)
            .ok_or(TunnelError::InvalidBase64Key)?);
        let preshared = match arg_preshared_key {
            Some(v) => Some(parse_key_bytes(&v)
                .ok_or(TunnelError::InvalidBase64Key)?),
            None => None,
        };

        let tunnel = Tunn::new(
            private,
            public,
            preshared,
            if keep_alive == 0 { None } else { Some(keep_alive) },
            index,
            None,
        );

        Ok(Arc::new(Self {
            tunnel: Mutex::new(tunnel),
        }))
    }

    pub fn encrypt_raw_packet(
        &self,
        src: Vec<u8>,
        dst_size: u32,
    ) -> Result<TunnelPacketResult, TunnelError> {
        let mut dst = vec![0u8; dst_size as usize];
        let mut tunnel = self.tunnel.lock().map_err(|_| TunnelError::Internal)?;
        Ok(map_tunn_result(tunnel.encapsulate(&src, &mut dst)))
    }

    pub fn decrypt_to_raw_packet(
        &self,
        src: Vec<u8>,
        dst_size: u32,
    ) -> Result<TunnelPacketResult, TunnelError> {
        let mut dst = vec![0u8; dst_size as usize];
        let mut tunnel = self.tunnel.lock().map_err(|_| TunnelError::Internal)?;
        Ok(map_tunn_result(tunnel.decapsulate(None, &src, &mut dst)))
    }

    pub fn run_periodic_task(&self, dst_size: u32) -> Result<TunnelPacketResult, TunnelError> {
        let mut dst = vec![0u8; dst_size as usize];
        let mut tunnel = self.tunnel.lock().map_err(|_| TunnelError::Internal)?;
        Ok(map_tunn_result(tunnel.update_timers(&mut dst)))
    }
}

fn parse_key_bytes(encoded: &str) -> Option<[u8; 32]> {
    let decoded = base64::decode(encoded).ok()?;
    if decoded.len() != 32 {
        return None;
    }
    let mut key = [0u8; 32];
    key.copy_from_slice(&decoded);
    Some(key)
}

fn map_tunn_result(result: TunnResult<'_>) -> TunnelPacketResult {
    match result {
        TunnResult::Done => TunnelPacketResult {
            op: 0,
            size: 0,
            packet: Vec::new(),
        },
        TunnResult::Err(err) => TunnelPacketResult {
            op: 2,
            size: err as u32,
            packet: Vec::new(),
        },
        TunnResult::WriteToNetwork(packet) => TunnelPacketResult {
            op: 1,
            size: packet.len() as u32,
            packet: packet.to_vec(),
        },
        TunnResult::WriteToTunnelV4(packet, _) => TunnelPacketResult {
            op: 4,
            size: packet.len() as u32,
            packet: packet.to_vec(),
        },
        TunnResult::WriteToTunnelV6(packet, _) => TunnelPacketResult {
            op: 6,
            size: packet.len() as u32,
            packet: packet.to_vec(),
        },
    }
}

uniffi::setup_scaffolding!();
