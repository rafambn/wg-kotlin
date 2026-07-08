<h1 align="center">WG-Kotlin</h1>

<p align="center">A Kotlin Multiplatform WireGuard runtime backed by BoringTun and a native Rust daemon.</p>

<p align="center">
  <a href="https://github.com/rafambn/wg-kotlin/actions/workflows/build-and-test.yml">
    <img alt="Build and Test" src="https://github.com/rafambn/wg-kotlin/actions/workflows/build-and-test.yml/badge.svg">
  </a>
  <img alt="Version" src="https://img.shields.io/badge/version-0.4.5-0A7EA4">
  <img alt="Platform Targets" src="https://img.shields.io/badge/targets-jvm-0A7EA4">
  <img alt="Daemon Targets" src="https://img.shields.io/badge/daemon-linux%20%7C%20macos%20%7C%20windows-0A7EA4">
</p>

<p align="center">
  WG-Kotlin provides a small Kotlin API for opening WireGuard userspace VPN sessions. Crypto and key helpers are backed by BoringTun through UniFFI, while privileged TUN, route, and DNS work is handled by a localhost Rust daemon.
</p>

## Project Status

- Current public library target: JVM.
- Real VPN sessions require the Rust daemon running with platform network privileges.
- Maven publishing is configured, but local publishing is the current setup path.
- Interface names intentionally use `utun[0-9]+` on every daemon platform.

## Key Features

- **Small Kotlin API**: Create a `Vpn`, pass a `VpnConfiguration`, then query `information()` or call `stop()`.
- **BoringTun Runtime**: WireGuard key generation and peer sessions are backed by Rust BoringTun bindings.
- **Native Daemon**: TUN packet I/O, route installation, and DNS configuration run in a standalone Rust process.
- **gRPC Protocol**: Kotlin and Rust share daemon messages through generated protobuf contracts.
- **Runtime Snapshots**: Interface state includes addresses, DNS, MTU, original configuration, and peer stats.
- **Cross-Platform Daemon Adapters**: Linux, macOS, and Windows route/DNS adapters live in the Rust daemon.

## Setup

Publish the Kotlin library to Maven Local:

```shell
./gradlew :wg-kotlin:publishToMavenLocal
```

Then add it to your JVM/KMP project:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.rafambn:wg-kotlin:0.4.5")
        }
    }
}
```

## Daemon Runtime

Build the daemon:

```shell
cargo build --manifest-path wg-kotlin-daemon-rust/daemon/Cargo.toml --release
```

Run the daemon with the privileges required by your OS network stack:

```shell
wg-kotlin-daemon-rust/target/release/wg-kotlin-daemon --host 127.0.0.1 --port 8787
```

The JVM client uses `127.0.0.1:8787` by default. Override it with system properties:

```shell
-Dwgkotlin.daemon.host=127.0.0.1
-Dwgkotlin.daemon.port=8787
```

## Usage

Generate a key pair:

```kotlin
import com.rafambn.wgkotlin.util.WireGuardKeys

val localKeys = WireGuardKeys.generateKeyPair()
val presharedKey = WireGuardKeys.generatePresharedKey()
```

Open a VPN session:

```kotlin
import com.rafambn.wgkotlin.DnsConfig
import com.rafambn.wgkotlin.Vpn
import com.rafambn.wgkotlin.VpnConfiguration
import com.rafambn.wgkotlin.VpnPeer

val vpn = Vpn(interfaceName = "utun0")

vpn.open(
    VpnConfiguration(
        interfaceName = "utun0",
        privateKey = localKeys.privateKey,
        addresses = mutableListOf("10.0.0.2/32"),
        dns = DnsConfig(
            servers = listOf("1.1.1.1"),
            searchDomains = listOf("example.com"),
        ),
        peers = listOf(
            VpnPeer(
                publicKey = "remote-peer-public-key-base64",
                presharedKey = presharedKey,
                endpointAddress = "203.0.113.10",
                endpointPort = 51820,
                allowedIps = listOf("0.0.0.0/0"),
                persistentKeepalive = 25,
            ),
        ),
    ),
)

val information = vpn.information()

vpn.stop()
```

## Runtime Notes

- Interface names must match `utun[0-9]+`, even on Linux and Windows.
- Linux daemon startup requires `resolvectl` in `PATH`, even when a session does not configure DNS.
- Linux route handling uses host route replacement/deletion semantics; callers should understand the routing impact of requested routes.
- The daemon binds only to loopback addresses.
- Default WireGuard listen port is `51820` when `listenPort` is not set.

## Development

Build and test Kotlin:

```shell
./gradlew :wg-kotlin:check
```

Run Rust daemon tests:

```shell
cargo test --manifest-path wg-kotlin-daemon-rust/Cargo.toml
```

Run everything:

```shell
./gradlew build
```

JDK 17 is required for Gradle builds.
