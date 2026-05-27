use std::error::Error;
use std::path::PathBuf;

fn main() -> Result<(), Box<dyn Error>> {
    let protoc = protoc_bin_vendored::protoc_bin_path()?;
    unsafe { std::env::set_var("PROTOC", protoc) };

    let proto_dir = PathBuf::from("../../daemon-protocol");

    tonic_prost_build::configure()
        .build_server(true)
        .build_client(true)
        .compile_protos(
            &[
                proto_dir.join("daemon.proto"),
                proto_dir.join("tun_session_config.proto"),
                proto_dir.join("dns_config.proto"),
            ],
            &[proto_dir],
        )?;

    println!("cargo:rerun-if-changed=../../daemon-protocol/daemon.proto");
    println!("cargo:rerun-if-changed=../../daemon-protocol/tun_session_config.proto");
    println!("cargo:rerun-if-changed=../../daemon-protocol/dns_config.proto");

    Ok(())
}
