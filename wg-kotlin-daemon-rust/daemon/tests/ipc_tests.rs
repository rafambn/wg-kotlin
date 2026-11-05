use daemon::ipc::{MAX_FRAME_SIZE, read_frame, write_frame};
use daemon_proto::pb::{ClientMessage, Packet, client_message};
use tokio::io::AsyncWriteExt;

#[test]
fn rejects_invalid_or_mismatched_owner_instances() {
    assert!(daemon::owner::OwnerProcess::open(0, 0).is_err());
    assert!(daemon::owner::OwnerProcess::open(std::process::id(), 0).is_err());
}

#[cfg(target_os = "linux")]
#[test]
fn linux_owner_identity_uses_boot_relative_ticks() {
    let pid = std::process::id();
    let stat = std::fs::read_to_string(format!("/proc/{pid}/stat")).unwrap();
    let start_ticks: u64 = stat.rsplit_once(')').unwrap().1.split_whitespace().nth(19).unwrap().parse().unwrap();
    let owner = daemon::owner::OwnerProcess::open(pid, start_ticks).unwrap();
    assert!(owner.authorizes(pid));
    assert!(daemon::owner::OwnerProcess::open(pid, start_ticks + 1).is_err());
}

#[tokio::test]
async fn framed_protobuf_round_trip_and_clean_eof() {
    let (mut client, mut server) = tokio::io::duplex(128);
    let message = ClientMessage { payload: Some(client_message::Payload::OutgoingPacket(Packet { data: vec![1, 2, 3] })) };
    write_frame(&mut client, &message).await.unwrap();
    drop(client);
    assert_eq!(read_frame::<ClientMessage>(&mut server).await.unwrap(), Some(message));
    assert!(read_frame::<ClientMessage>(&mut server).await.unwrap().is_none());
}

#[tokio::test]
async fn rejects_empty_oversized_truncated_and_malformed_frames() {
    for bytes in [vec![0, 0, 0, 0], ((MAX_FRAME_SIZE + 1) as u32).to_be_bytes().to_vec(), vec![0, 0], vec![0, 0, 0, 4, 1], vec![0, 0, 0, 1, 255]] {
        let (mut client, mut server) = tokio::io::duplex(128);
        client.write_all(&bytes).await.unwrap();
        drop(client);
        assert!(read_frame::<ClientMessage>(&mut server).await.is_err());
    }
}
