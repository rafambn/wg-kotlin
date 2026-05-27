# Rust Daemon Architecture Plan

## Status: Draft — updated v0.4.0

## 1. Motivation

The current Kotlin/JVM daemon works correctly as a fat-JAR, but GraalVM Native Image is incompatible with `kotlinx.rpc` (KRPC) because KRPC relies on runtime-generated service stubs that cannot be reliably discovered by native-image reflection configuration. After extensive testing, KRPC calls are silently dropped after the WebSocket handshake in the native image.

A Rust daemon solves this permanently:
- **True native binary** — no JVM, no reflection, no GraalVM metadata wrestling
- **Smaller footprint** — static binary ~5–15 MB vs. 60 MB GraalVM image or fat JAR
- **Faster startup** — milliseconds instead of JVM warm-up
- **Lower memory** — ~5–20 MB RSS vs. 80–200 MB for JVM
- **Smaller attack surface** — no JNI, no reflection, no dynamic class loading
- **Reuses existing Rust code** — `wg-kotlin-uniffi-tun-rs` already wraps `tun_rs`; the Rust daemon can link it directly

## 2. High-Level Design

```
┌─────────────────────────────────────────┐
│           uninet_app (Kotlin)           │
│  ┌───────────────────────────────────┐  │
│  │    gRPC bidirectional stream      │  │
│  │   Session(config, outgoingFlow)   │  │
│  │        → returns incomingFlow     │  │
│  └──────────────┬────────────────────┘  │
└─────────────────┼───────────────────────┘
                  │ HTTP/2 over TCP (TLS)
                  ▼
┌─────────────────────────────────────────┐
│           vpn-daemon (Rust)             │
│  ┌───────────────────────────────────┐  │
│  │         tonic gRPC server         │  │
│  │     bidirectional streaming       │  │
│  └──────────────┬────────────────────┘  │
│                 │                       │
│  ┌──────────────┴────────────────────┐  │
│  │      Session Manager              │  │
│  │  (start / stop / packet I/O)      │  │
│  └──────────────┬────────────────────┘  │
│                 │                       │
│  ┌──────────────┴──────┐ ┌─────────────┐│
│  │   Platform Adapter  │ │  TUN Device ││
│  │  (ip/netsh/resolve) │ │ (tun_rs)    ││
│  └─────────────────────┘ └─────────────┘│
│                                         │
│  ┌─────────────────────────────────────┐│
│  │      scribe-rs (logging)            ││
│  │   Scrolls only — no Notes           ││
│  └─────────────────────────────────────┘│
└─────────────────────────────────────────┘
```

## 3. Transport Protocol: gRPC Bidirectional Streaming

The Kotlin client sees a single RPC call that returns two flows, exactly like the current KRPC API:

```kotlin
// Kotlin client
interface DaemonApi {
    fun startSession(
        config: TunSessionConfig,
        outgoingPackets: Flow<ByteArray>,
    ): Flow<ByteArray>
}
```

Under the hood this maps to a **single gRPC bidirectional streaming call**:

```protobuf
syntax = "proto3";

package wgdaemon;

service Daemon {
    // Single call: client sends config + outgoing packets,
    // server sends incoming packets back.
    rpc Session(stream ClientMessage) returns (stream ServerMessage);
}

message ClientMessage {
    oneof payload {
        TunSessionConfig config = 1;  // sent first
        Packet outgoing_packet = 2;   // all subsequent messages
    }
}

message ServerMessage {
    oneof payload {
        SessionStarted started = 1;   // ack after TUN is up
        Packet incoming_packet = 2;   // continuous stream
        SessionError error = 3;       // on failure
    }
}

message Packet {
    bytes data = 1;
}

message SessionStarted {
    string interface_name = 1;
}

message SessionError {
    string code = 1;
    string message = 2;
}

message TunSessionConfig {
    string interface_name = 1;
    int32 mtu = 2;
    repeated string addresses = 3;
    repeated string routes = 4;
    repeated string endpoints = 5;
    DnsConfig dns = 6;
}

message DnsConfig {
    repeated string servers = 1;
    repeated string search_domains = 2;
}
```

### 3.1 Why This Design

- **Familiar API** — Kotlin client keeps `startSession(config, outgoingPackets): Flow<ByteArray>`
- **Single RPC call** — no separate HTTP + WebSocket connection dance
- **Native-image friendly** — `tonic` + `prost` use only generated code, zero reflection
- **Backpressure built-in** — gRPC flow control handles TUN ↔ network backpressure
- **Protobuf schema is the contract** — Rust and Kotlin generated from identical `.proto`

### 3.2 Message Flow

```
Client                              Daemon
  |                                   |
  |-- ClientMessage {config} -------->|  validate config
  |                                   |  platform_adapter.start_session(config)
  |                                   |    → open TUN
  |                                   |    → ip link set up
  |                                   |    → ip addr add
  |                                   |    → ip route replace
  |                                   |    → resolvectl dns/domain
  |<-- ServerMessage {started} -------|
  |                                   |
  |-- ClientMessage {packet} -------->|  write to TUN
  |<-- ServerMessage {packet} --------|  read from TUN
  |                                   |  (continuous bidirectional stream)
  |                                   |
  |-- client closes stream ---------->|  cleanup routes
  |                                   |  resolvectl revert
  |                                   |  close TUN
  |                                   |
```

### 3.3 TLS / mTLS

The gRPC channel uses **mTLS** (mutual TLS) on localhost:

- Daemon ships with a self-signed CA cert + server cert embedded at build time
- Client (Kotlin app) embeds the same CA cert + its own client cert
- Both sides verify each other
- This prevents any other process on the machine from connecting to the privileged daemon

```rust
// Rust daemon
let tls_config = ServerTlsConfig::new()
    .identity(identity)
    .client_auth_optional(false);

Server::builder()
    .tls_config(tls_config)?
    .add_service(daemon_server)
    .serve(addr)
    .await?;
```

```kotlin
// Kotlin client
val channel = NettyChannelBuilder.forAddress(host, port)
    .sslContext(GrpcSslContexts.forClient()
        .trustManager(caCert)
        .keyManager(clientCert, clientKey)
        .build())
    .build()
```

## 4. scribe-rs Crate

The Rust daemon uses a port of the Scribe library called `scribe-rs`. It lives in the same workspace as the daemon.

### 4.1 Why a Separate Crate?

- **Reusability** — Other Rust projects (e.g., CLI tools, test harnesses) can depend on `scribe-rs`
- **Clean boundaries** — Logging logic is isolated from daemon networking and TUN logic
- **Future publishable** — Can be published to crates.io if the format stabilizes
- **Testing** — Scroll serialization and savers can be unit-tested independently

### 4.2 Key Difference from Kotlin Scribe

**No `Note` support.** The Rust crate supports **only `Scroll` and `SealedScroll`**. The Kotlin Scribe library has `Note` for lightweight standalone messages, but the daemon logging policy already mandates scrolls-only (`newScroll` / `seal`). Removing `Note`:

- Simplifies the API surface
- Removes the temptation to use ad-hoc logging in the daemon
- Keeps 100% parity with the daemon's existing scroll-only output

### 4.3 API Surface

```rust
use scribe_rs::{Scribe, ScrollSaver, Margin};
use serde_json::{json, Map, Value};

#[tokio::main]
async fn main() {
    let mut scribe = Scribe::builder()
        .imprint("app", "vpn-daemon")
        .imprint("version", env!("CARGO_PKG_VERSION"))
        .saver(ScrollSaver::file("/var/log/vpn-daemon.jsonl"))
        .build();

    scribe.hire();

    // ── Scroll lifecycle (same as Kotlin) ──
    let mut scroll = scribe.new_scroll(None);
    scroll.insert("event", "daemon_session");
    scroll.insert("interface", "utun0");
    scribe.seal(scroll, true);

    scribe.retire().await;
}
```

### 4.4 Crate Layout

```
wg-kotlin-daemon-rust/
├── Cargo.toml              # workspace root
├── scribe-rs/              # NEW: Scribe port for Rust
│   ├── Cargo.toml
│   └── src/
│       ├── lib.rs          # Public API
│       ├── scribe.rs       # Core Scribe struct + builder
│       ├── scroll.rs       # Scroll type + seal/extend/append
│       ├── saver.rs        # Saver trait + built-in savers (file, stdout)
│       └── margin.rs       # Margin trait
├── proto/                  # shared .proto files
│   ├── daemon.proto
│   ├── tun_session_config.proto
│   └── dns_config.proto
├── daemon/
│   ├── Cargo.toml          # depends on scribe-rs
│   └── src/
│       ├── main.rs         # CLI entry point (clap)
│       ├── server.rs       # tonic gRPC service impl
│       ├── session.rs      # SessionManager (active sessions, cleanup)
│       ├── platform/
│       │   ├── mod.rs      # PlatformAdapter trait
│       │   ├── linux.rs    # ip route, resolvectl
│       │   ├── macos.rs    # ifconfig, route, scutil
│       │   └── windows.rs  # netsh, nrpt
│       ├── tun.rs          # Thin wrapper around tun_rs
│       └── logging.rs      # Daemon-specific scroll events
└── build.rs                # prost codegen for .proto files
```

### 4.5 Core Implementation

**`scribe-rs/src/scroll.rs`**

```rust
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

pub type Scroll = Map<String, Value>;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SealedScroll {
    pub success: bool,
    pub data: Map<String, Value>,
}

pub fn new_scroll_id() -> String {
    uuid::Uuid::new_v4().to_string()
}

pub trait ScrollExt {
    fn seal(self, success: bool) -> SealedScroll;
    fn extend(&mut self, other: &Scroll);
    fn append(&mut self, key: &str, nested: &Scroll);
}

impl ScrollExt for Scroll {
    fn seal(self, success: bool) -> SealedScroll {
        SealedScroll {
            success,
            data: self,
        }
    }

    fn extend(&mut self, other: &Scroll) {
        for (k, v) in other {
            if !self.contains_key(k) {
                self.insert(k.clone(), v.clone());
            }
        }
    }

    fn append(&mut self, key: &str, nested: &Scroll) {
        self.insert(key.to_string(), Value::Object(nested.clone()));
    }
}
```

**`scribe-rs/src/saver.rs`**

```rust
use crate::scroll::SealedScroll;
use std::fs::OpenOptions;
use std::io::{self, Write};
use std::sync::Mutex;

pub trait Saver: Send + Sync {
    fn save(&self, scroll: &SealedScroll);
}

pub struct FileSaver {
    path: String,
    writer: Mutex<io::BufWriter<std::fs::File>>,
}

impl FileSaver {
    pub fn new(path: &str) -> io::Result<Self> {
        let file = OpenOptions::new()
            .create(true)
            .append(true)
            .open(path)?;
        Ok(Self {
            path: path.to_string(),
            writer: Mutex::new(io::BufWriter::new(file)),
        })
    }
}

impl Saver for FileSaver {
    fn save(&self, scroll: &SealedScroll) {
        let json = match serde_json::to_string(scroll) {
            Ok(s) => s,
            Err(e) => {
                eprintln!("scribe-rs: failed to serialize scroll: {}", e);
                return;
            }
        };
        let mut writer = self.writer.lock().unwrap();
        if let Err(e) = writeln!(writer, "{}", json) {
            eprintln!("scribe-rs: failed to write to {}: {}", self.path, e);
        }
        let _ = writer.flush();
    }
}

pub struct StdoutSaver;

impl Saver for StdoutSaver {
    fn save(&self, scroll: &SealedScroll) {
        if let Ok(json) = serde_json::to_string(scroll) {
            println!("{}", json);
        }
    }
}
```

**`scribe-rs/src/margin.rs`**

```rust
use crate::scroll::Scroll;

pub trait Margin: Send + Sync {
    fn header(&self, _scroll: &mut Scroll) {}
    fn footer(&self, _scroll: &mut Scroll) {}
}
```

**`scribe-rs/src/scribe.rs`**

```rust
use crate::margin::Margin;
use crate::saver::Saver;
use crate::scroll::{new_scroll_id, SealedScroll, Scroll, ScrollExt};
use serde_json::{Map, Value};
use std::sync::Arc;
use tokio::sync::mpsc;

pub struct Scribe {
    imprint: Map<String, Value>,
    margin: Option<Arc<dyn Margin>>,
    savers: Vec<Arc<dyn Saver>>,
    tx: Option<mpsc::UnboundedSender<SealedScroll>>,
    handle: Option<tokio::task::JoinHandle<()>>,
}

impl Scribe {
    pub fn builder() -> ScribeBuilder {
        ScribeBuilder::default()
    }

    /// Equivalent to Kotlin `Scribe.hire()`
    pub fn hire(&mut self) {
        assert!(
            self.tx.is_none(),
            "Scribe already hired. Call retire() first."
        );

        let (tx, mut rx) = mpsc::unbounded_channel::<SealedScroll>();
        let savers = self.savers.clone();

        let handle = tokio::spawn(async move {
            while let Some(scroll) = rx.recv().await {
                for saver in &savers {
                    saver.save(&scroll);
                }
            }
        });

        self.tx = Some(tx);
        self.handle = Some(handle);
    }

    /// Equivalent to Kotlin `Scribe.newScroll()`
    pub fn new_scroll(&self, id: Option<String>) -> Scroll {
        let mut scroll = Map::new();
        scroll.insert(
            "scroll_id".to_string(),
            Value::String(id.unwrap_or_else(new_scroll_id)),
        );

        for (k, v) in &self.imprint {
            scroll.insert(k.clone(), v.clone());
        }

        if let Some(margin) = &self.margin {
            margin.header(&mut scroll);
        }

        scroll
    }

    /// Equivalent to Kotlin `Scroll.seal()` + enqueue
    pub fn seal(&self, mut scroll: Scroll, success: bool) -> SealedScroll {
        if let Some(margin) = &self.margin {
            margin.footer(&mut scroll);
        }

        let sealed = scroll.seal(success);
        self.enqueue(&sealed);
        sealed
    }

    /// Equivalent to Kotlin `Scribe.retire()`
    pub async fn retire(&mut self) {
        if let Some(tx) = self.tx.take() {
            drop(tx); // close channel
        }
        if let Some(handle) = self.handle.take() {
            let _ = handle.await;
        }
    }

    fn enqueue(&self, scroll: &SealedScroll) {
        if let Some(tx) = &self.tx {
            let _ = tx.send(scroll.clone()); // unbounded, never blocks
        } else {
            panic!("Scribe runtime is not active. Call hire() first.");
        }
    }
}

#[derive(Default)]
pub struct ScribeBuilder {
    imprint: Map<String, Value>,
    margin: Option<Arc<dyn Margin>>,
    savers: Vec<Arc<dyn Saver>>,
}

impl ScribeBuilder {
    pub fn imprint(mut self, key: &str, value: impl Into<Value>) -> Self {
        self.imprint.insert(key.to_string(), value.into());
        self
    }

    pub fn margin(mut self, margin: Arc<dyn Margin>) -> Self {
        self.margin = Some(margin);
        self
    }

    pub fn saver(mut self, saver: Arc<dyn Saver>) -> Self {
        self.savers.push(saver);
        self
    }

    pub fn build(self) -> Scribe {
        Scribe {
            imprint: self.imprint,
            margin: self.margin,
            savers: self.savers,
            tx: None,
            handle: None,
        }
    }
}
```

**`scribe-rs/src/lib.rs`**

```rust
pub mod margin;
pub mod saver;
pub mod scribe;
pub mod scroll;

pub use margin::Margin;
pub use saver::{FileSaver, Saver, StdoutSaver};
pub use scribe::{Scribe, ScribeBuilder};
pub use scroll::{Scroll, ScrollExt, SealedScroll};
```

**`scribe-rs/Cargo.toml`**

```toml
[package]
name = "scribe-rs"
version = "0.1.0"
edition = "2021"

[dependencies]
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"
tokio = { version = "1.0", features = ["sync", "rt"] }
uuid = { version = "1.0", features = ["v4"] }
```

### 4.6 Output Comparison

**Kotlin Scribe output:**
```json
{"success":true,"data":{"scroll_id":"550e8400-e29b-41d4-a716-446655440000","app":"vpn-daemon","version":"1.0.0","event":"daemon_session","interface":"utun0"}}
```

**Rust scribe-rs output (identical):**
```json
{"success":true,"data":{"scroll_id":"550e8400-e29b-41d4-a716-446655440000","app":"vpn-daemon","version":"1.0.0","event":"daemon_session","interface":"utun0"}}
```

### 4.7 Daemon Logging Integration

The daemon creates a single `Scribe` instance at startup and uses it throughout:

```rust
// daemon/src/logging.rs
use scribe_rs::{Scribe, ScribeBuilder, FileSaver};
use std::sync::Arc;

pub fn create_daemon_scribe(log_path: &str) -> Scribe {
    let file_saver = FileSaver::new(log_path)
        .expect("Failed to open log file");
    
    Scribe::builder()
        .imprint("service", "wg-daemon")
        .imprint("version", env!("CARGO_PKG_VERSION"))
        .saver(Arc::new(file_saver))
        .build()
}
```

```rust
// daemon/src/main.rs
use scribe_rs::Scribe;

struct DaemonState {
    scribe: Scribe,
}

#[tokio::main]
async fn main() {
    let mut scribe = create_daemon_scribe("/var/log/vpn-daemon.jsonl");
    scribe.hire();

    let mut scroll = scribe.new_scroll(None);
    scroll.insert("event", "daemon_startup");
    scroll.insert("pid", std::process::id() as i64);
    scribe.seal(scroll, true);

    // ... run gRPC server ...

    scribe.retire().await;
}
```

## 5. gRPC / Protobuf Stack Options

### 5.1 Why `tonic` + `prost`?

| Framework | Language | Maturity | Notes |
|---|---|---|---|
| **tonic** | Rust | Very mature | Built by Lucio Franco (Tokio ecosystem). Uses `hyper` + `h2`. Most popular Rust gRPC framework by far. |
| **grpc-rs** | Rust | Mature | Bindings to C++ gRPC core. Harder to cross-compile (needs C++ toolchain). Slower builds. |
| **volition** | Rust | Experimental | Alternative by Embark Studios. Not production-ready. |
| **grpc-rust** | Rust | Abandoned | Don't use. |

**Google's recommendation:** Google does not officially endorse a Rust gRPC implementation, but the Rust community overwhelmingly uses `tonic`. It is the de facto standard.

### 5.2 Why `prost`?

| Library | Maturity | Notes |
|---|---|---|
| **prost** | Very mature | Most popular Rust protobuf library. Clean API, fast, `no_std` support. |
| **protobuf** (stepancheg) | Mature | Alternative. Slightly different API. Less popular than `prost`. |
| **quick-protobuf** | Experimental | Zero-copy deserialization. Not widely used yet. |

`tonic` is tightly coupled to `prost` for code generation. This is the standard stack:
```
tonic (gRPC server/client)
├── tonic-build (build.rs codegen)
│   └── prost-build (generates Rust structs from .proto)
│       └── prost (runtime library)
```

### 5.3 Decision

**Use `tonic` + `prost`.** It is the only production-ready, actively maintained Rust gRPC stack.

## 6. CLI Options

### 6.1 Why `clap`?

| Library | Maturity | Features | Notes |
|---|---|---|---|
| **clap** | Very mature | Derive macros, subcommands, shell completions, color help | Most popular Rust CLI framework. Used by `cargo`, `ripgrep`, `bat`. |
| **structopt** | Deprecated | — | Was popular, merged into `clap` v3. |
| **argh** | Mature | Lightweight, Google-style | Used by Fuchsia. Smaller binary size than clap. |
| **bpaf** | Mature | Composable parser combinators | Functional style. Good for complex CLIs. |
| **xflags** | Experimental | — | Don't use. |

### 6.2 Decision

**Use `clap`.** It is the standard, has the best documentation, and the binary size overhead is negligible compared to the rest of the daemon (~50 KB).

```rust
use clap::Parser;

#[derive(Parser)]
#[command(name = "vpn-daemon", version)]
struct Cli {
    #[arg(long, default_value = "127.0.0.1")]
    host: String,
    
    #[arg(long, default_value_t = 8787)]
    port: u16,
    
    #[arg(long)]
    tls_cert: PathBuf,
    
    #[arg(long)]
    tls_key: PathBuf,
    
    #[arg(long, default_value = "/var/log/vpn-daemon.jsonl")]
    log_path: PathBuf,
}
```

## 7. Crate Layout

```
wg-kotlin-daemon-rust/
├── Cargo.toml              # workspace root
├── scribe-rs/              # Scribe port for Rust (Scrolls only)
│   ├── Cargo.toml
│   └── src/
│       ├── lib.rs          # Public API
│       ├── scribe.rs       # Core Scribe struct + builder
│       ├── scroll.rs       # Scroll type + seal/extend/append
│       ├── saver.rs        # Saver trait + built-in savers
│       └── margin.rs       # Margin trait
├── proto/                  # shared .proto files
│   ├── daemon.proto
│   ├── tun_session_config.proto
│   └── dns_config.proto
├── daemon/
│   ├── Cargo.toml          # depends on scribe-rs (path)
│   └── src/
│       ├── main.rs         # CLI entry point (clap)
│       ├── server.rs       # tonic gRPC service impl
│       ├── session.rs      # SessionManager (active sessions, cleanup)
│       ├── platform/
│       │   ├── mod.rs      # PlatformAdapter trait
│       │   ├── linux.rs    # ip route, resolvectl
│       │   ├── macos.rs    # ifconfig, route, scutil
│       │   └── windows.rs  # netsh, nrpt
│       ├── tun.rs          # Thin wrapper around tun_rs
│       └── logging.rs      # Daemon-specific scroll events + scribe init
└── build.rs                # prost codegen for .proto files
```

## 8. Key Dependencies

| Crate | Purpose | Version |
|---|---|---|
| `tokio` | Async runtime | 1.x |
| `tonic` | gRPC server (HTTP/2 + bidirectional streaming) | 0.12.x |
| `prost` + `prost-build` | Protobuf serialization | 0.13.x |
| `tun_rs` | Cross-platform TUN device | latest |
| `clap` | CLI argument parsing | 4.x |
| `scribe-rs` | Logging (workspace crate) | 0.1.0 |
| `uuid` | Scroll IDs | 1.x |
| `chrono` | Timestamps (for daemon-specific fields) | 0.4.x |
| `libc` | Privilege check (`getuid`) | std |

## 9. Platform Adapter Interface

```rust
#[async_trait]
pub trait PlatformAdapter: Send + Sync {
    fn platform_id(&self) -> &'static str;
    fn required_binaries(&self) -> Vec<&'static str>;

    async fn start_session(&self, config: &TunSessionConfig) -> Result<Box<dyn TunHandle>, PlatformError>;
}

pub trait TunHandle: Send + Sync {
    fn interface_name(&self) -> &str;
    async fn read_packet(&self) -> Result<Vec<u8>, TunError>;
    async fn write_packet(&self, packet: &[u8]) -> Result<(), TunError>;
    fn close(&self) -> Result<(), TunError>;
}
```

### 9.1 Linux Implementation Details

Replicates the current `LinuxPlatformAdapter` behavior:

1. `RealTunHandle::open_device()` via `tun_rs`
2. `ip link set dev {iface} up`
3. `ip address flush dev {iface}`
4. `ip address add {cidr} dev {iface}` for each address
5. `ip route replace {route} dev {iface}` for each route (filtered for endpoint IPs)
6. `ip route replace {endpoint}/32 via {gateway} dev {device}` for each endpoint
7. `resolvectl dns {iface} {servers}`
8. `resolvectl domain {iface} ~{domains}`

Cleanup is the reverse order, using `runCommand` wrappers with timeout and stderr capture.

## 10. Session Lifecycle

```
Client                              Daemon
  |                                   |
  |-- Session(stream) --------------->|  first message = config
  |                                   |  validate config
  |                                   |  platform_adapter.start_session(config)
  |                                   |    → open TUN
  |                                   |    → ip link set up
  |                                   |    → ip addr add
  |                                   |    → ip route replace
  |                                   |    → resolvectl dns/domain
  |<-- ServerMessage {started} -------|
  |                                   |
  |-- ClientMessage {packet} -------->|  write to TUN
  |<-- ServerMessage {packet} --------|  read from TUN
  |                                   |  (continuous bidirectional stream)
  |                                   |
  |-- client drops stream ----------->|  cleanup routes
  |                                   |  resolvectl revert
  |                                   |  close TUN
  |                                   |
```

## 11. Kotlin Client Changes

The Kotlin client in `wg-kotlin` replaces KRPC with a gRPC client:

```kotlin
// DaemonApi.kt — becomes pure interface, no @Rpc annotation
interface DaemonApi {
    fun startSession(
        config: TunSessionConfig,
        outgoingPackets: Flow<ByteArray>,
    ): Flow<ByteArray>
}
```

`DaemonBackedInterfaceCommandExecutor` is rewritten to use `grpc-java`/`grpc-kotlin`:

```kotlin
class GrpcDaemonClient(host: String, port: Int) : DaemonApi {
    private val channel = NettyChannelBuilder.forAddress(host, port)
        .sslContext(GrpcSslContexts.forClient()
            .trustManager(caCert)
            .keyManager(clientCert, clientKey)
            .build())
        .build()
    private val stub = DaemonGrpcKt.DaemonCoroutineStub(channel)

    override fun startSession(
        config: TunSessionConfig,
        outgoingPackets: Flow<ByteArray>,
    ): Flow<ByteArray> = flow {
        val requestFlow = outgoingPackets.toGrpcRequestFlow(config)
        stub.session(requestFlow).collect { response ->
            when (response.payloadCase) {
                ServerMessage.PayloadCase.INCOMING_PACKET ->
                    emit(response.incomingPacket.data.toByteArray())
                ServerMessage.PayloadCase.ERROR ->
                    throw IllegalStateException(response.error.message)
                else -> { /* ignore started ack after first */ }
            }
        }
    }
}
```

## 12. Build & Deployment

### 12.1 Workspace `Cargo.toml`

```toml
[workspace]
members = ["scribe-rs", "daemon"]
resolver = "2"
```

### 12.2 Local Build

```bash
cd wg-kotlin-daemon-rust
cargo build --release
# Binaries: target/release/vpn-daemon
```

### 12.3 Static Binary for Linux

```bash
cargo build --release --target x86_64-unknown-linux-musl
# Produces a fully static binary with no libc dependency
```

### 12.4 Cross-Platform CI Matrix

```yaml
# .github/workflows/daemon.yml
strategy:
  matrix:
    include:
      - target: x86_64-unknown-linux-musl
        os: ubuntu-latest
      - target: x86_64-apple-darwin
        os: macos-latest
      - target: aarch64-apple-darwin
        os: macos-latest
      - target: x86_64-pc-windows-msvc
        os: windows-latest
```

### 12.5 Integration with Kotlin Build

```kotlin
// wg-kotlin/build.gradle.kts
tasks.register<Exec>("buildRustDaemon") {
    workingDir = file("wg-kotlin-daemon-rust/daemon")
    commandLine("cargo", "build", "--release", "--target", "x86_64-unknown-linux-musl")
    doLast {
        copy {
            from("wg-kotlin-daemon-rust/daemon/target/x86_64-unknown-linux-musl/release/vpn-daemon")
            into("wg-kotlin-daemon-jvm/build/native/nativeCompile")
        }
    }
}
```

## 13. Migration Checklist

### Phase 1 — scribe-rs Crate (1 day)
- [x] Create `scribe-rs/` crate with workspace membership
- [x] Implement `Scroll`, `SealedScroll`, `ScrollExt` (seal, extend, append)
- [x] Implement `Saver` trait with `FileSaver` and `StdoutSaver`
- [x] Implement `Margin` trait
- [x] Implement `Scribe` with builder pattern (`hire`, `new_scroll`, `seal`, `retire`)
- [x] Unit tests: scroll creation, imprint inheritance, file output

### Phase 2 — Protocol Lock (1 day)
- [x] Write `.proto` files: `daemon.proto`, `tun_session_config.proto`, `dns_config.proto`
- [x] Generate Kotlin stubs with `grpc-kotlin` compiler plugin
- [x] Generate Rust stubs with `tonic-build` in `build.rs`
- [x] Verify wire compatibility: encode in Kotlin, decode in Rust and vice versa

### Phase 3 — Rust Daemon Skeleton (2 days)
- [x] `Cargo.toml` workspace with `daemon` crate depending on `scribe-rs`
- [x] CLI parsing with `clap` (host, port, tls-cert, tls-key, log-path)
- [x] Privilege check (`getuid() == 0` on Unix)
- [x] `tonic` gRPC server skeleton with `Session` method
- [x] `scribe-rs` integration: init, startup scroll, shutdown scroll

### Phase 4 — Session & TUN (2 days)
- [x] `tun_rs` integration (link existing crate or add as git dependency)
- [x] Handle `ClientMessage.config` → open TUN → respond `ServerMessage.started`
- [x] Handle `ClientMessage.packet` → write to TUN
- [x] Spawn read task: TUN → `ServerMessage.packet`
- [x] Session manager with cleanup on stream drop
- [x] Scroll events: `daemon_session`, `daemon_session_started`, `daemon_session_stopped`

### Phase 5 — Platform Adapters (2–3 days)
- [x] Linux: `ip`, `resolvectl` command wrappers
- [x] macOS: `ifconfig`, `route`, `scutil` wrappers
- [x] Windows: `netsh`, `powershell` NRPT wrappers
- [x] Endpoint route resolution (`ip route get` / `netstat -rn`)

### Phase 6 — TLS & Security (1 day)
- [x] Generate self-signed CA + server cert at build time
- [x] Embed certs in binary via `include_bytes!`
- [x] Configure `tonic` server with `ServerTlsConfig`
- [x] Configure Kotlin client with mutual TLS
- [x] Reject connections without valid client cert

### Phase 7 — Kotlin Client Update (1–2 days)
- [x] Replace `DaemonBackedInterfaceCommandExecutor` KRPC client with `grpc-java`/`grpc-kotlin`
- [x] Map `Flow<ByteArray>` ↔ gRPC bidirectional stream
- [x] Reuse existing `TunSessionConfig` protobuf messages
- [x] Configure mTLS with embedded certs

### Phase 8 — Test & Release (2 days)
- [x] Unit tests: `scribe-rs` scroll format, Linux command planning, route filtering
- [x] Integration test: Kotlin client ↔ Rust daemon end-to-end
- [x] CI pipeline: `cargo test`, `cargo clippy`, `cargo build --release`
- [x] Update app bundling to ship Rust binary instead of native image

**Total estimated effort: ~2.5–3 weeks for one developer.**

## 14. Risks & Mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| `tun_rs` lacks a feature the JNI wrapper provides | Low | Fork `tun_rs` or contribute upstream; it already supports sync/async, IPv4/IPv6, Windows/macOS/Linux |
| Cross-compilation for macOS/Windows from Linux CI | Medium | Use GitHub Actions matrix with `macos-latest` and `windows-latest` runners |
| Protobuf version mismatch between Kotlin and Rust | Low | Pin exact `prost` and `protobuf-kotlin` versions; add wire-compatibility test in CI |
| Platform command differences across Linux distros | Medium | Test on Ubuntu, Fedora, Arch in CI; commands use standard `iproute2` which is universal |
| mTLS certificate management | Medium | Embed certs at build time; rotation is unnecessary for localhost-only daemon |
| scribe-rs maintenance drift from Kotlin Scribe | Low | Keep API minimal (scroll-only); format is stable JSON with `scroll_id` + `success` + `data` |

## 15. Open Questions (Resolved)

1. **Should the Kotlin daemon module be deleted?**
   - **No.** Leave it as reference. Delete later when the Rust daemon is fully stable.

2. **Should the protocol be published separately?**
   - **No.** Keep `.proto` files in the monorepo. Anyone can copy-paste them if needed.

3. **Should gRPC use plaintext or TLS?**
   - **mTLS required.** The daemon runs privileged. Only the authenticated client app should be able to connect. Use embedded self-signed certs on both sides.

---

*Document version: 0.4.0*
*Author: OpenCode assistant*
*Date: 2026-05-27*
