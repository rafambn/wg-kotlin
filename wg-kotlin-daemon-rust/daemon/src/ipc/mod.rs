use prost::Message;
use std::io;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

#[cfg_attr(unix, path = "unix.rs")]
#[cfg_attr(windows, path = "windows.rs")]
mod local;
pub use local::LocalListener;

pub const PROTOCOL_VERSION: u32 = 2;
pub const MAX_FRAME_SIZE: usize = 4 * 1024 * 1024;

pub async fn read_frame<M: Message + Default>(input: &mut (impl AsyncRead + Unpin)) -> io::Result<Option<M>> {
    let mut header = [0; 4];
    if input.read(&mut header[..1]).await? == 0 {
        return Ok(None);
    }
    input.read_exact(&mut header[1..]).await?;
    let size = u32::from_be_bytes(header) as usize;
    if size == 0 || size > MAX_FRAME_SIZE {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid IPC frame length"));
    }
    let mut bytes = vec![0; size];
    input.read_exact(&mut bytes).await?;
    M::decode(bytes.as_slice()).map(Some).map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))
}

pub async fn write_frame<M: Message>(output: &mut (impl AsyncWrite + Unpin), message: &M) -> io::Result<()> {
    let bytes = message.encode_to_vec();
    if bytes.is_empty() || bytes.len() > MAX_FRAME_SIZE {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid IPC frame length"));
    }
    output.write_all(&(bytes.len() as u32).to_be_bytes()).await?;
    output.write_all(&bytes).await?;
    output.flush().await
}
