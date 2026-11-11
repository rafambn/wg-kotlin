<h1 align="center">WG-Kotlin</h1>

<p align="center">A Kotlin Multiplatform WireGuard runtime backed by BoringTun and a native Rust daemon.</p>

<p align="center">
  <img src="wg-kotlin-logo.svg" alt="WG-Kotlin logo" width="180" height="180">
</p>

<p align="center">
  <a href="https://github.com/rafambn/wg-kotlin/actions/workflows/build-and-test.yml"><img alt="Build and Test" src="https://github.com/rafambn/wg-kotlin/actions/workflows/build-and-test.yml/badge.svg"></a>
  <img alt="Version" src="https://img.shields.io/badge/version-0.5.0-0A7EA4">
  <img alt="Platform Targets" src="https://img.shields.io/badge/targets-jvm-0A7EA4">
  <img alt="Daemon Targets" src="https://img.shields.io/badge/daemon-linux%20%7C%20macos%20%7C%20windows-0A7EA4">
</p>

<p align="center">
  <img alt="Repository views" src="https://profile.rafambn.com/badge/rafambn/wg-kotlin.svg">
</p>

<p align="center">
  WG-Kotlin provides a small Kotlin API for opening WireGuard userspace VPN sessions. Crypto and key helpers are backed by BoringTun through UniFFI, while privileged TUN, route, and DNS work is handled by a local Rust daemon.
</p>

## Project Status

- Current public library target: JVM.
- Real VPN sessions require the Rust daemon running with platform network privileges.
- Release publishing bundles the JVM bindings and native BoringTun runtimes.
- Interface names intentionally use `utun[0-9]+` on every daemon platform.

## Key Features

- **Small Kotlin API**: Launch an owned daemon, create a `Vpn`, then start, inspect, or stop TUN sessions by name.
- **BoringTun Runtime**: WireGuard key generation and peer sessions are backed by Rust BoringTun bindings.
- **Native Daemon**: TUN packet I/O, route installation, and DNS configuration run in a standalone Rust process.
- **Local IPC**: One named-pipe or Unix-socket connection per TUN, using protobuf messages and OS process authentication.
- **Runtime Snapshots**: Interface state includes addresses, DNS, MTU, original configuration, and peer stats.
- **Cross-Platform Daemon Adapters**: Linux, macOS, and Windows route/DNS adapters live in the Rust daemon.

## Setup

Publish WG-Kotlin to Maven Local:

```shell
./gradlew publishToMavenLocal -PsignAllPublications=false
```

Then add it to your JVM/KMP project:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
    // Required by the current kotlinx.rpc protobuf runtime.
    maven("https://redirector.kotlinlang.org/maven/kxrpc-grpc")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.rafambn:wg-kotlin:0.5.0")
        }
    }
}
```

The `wg-kotlin` JVM JAR includes the generated UniFFI bindings and BoringTun for
Windows x64, macOS x64/ARM64, and Linux x64/ARM64.
JNA loads the library for the current platform. Consumers need no classifiers,
extra runtime dependencies, Rust toolchain, or manual native-library installation.
Linux x64 builds use Ubuntu 22.04 and Linux ARM64 builds use Ubuntu 24.04; older
libc versions are not covered by these builds.

Local development builds include only the build host's native runtime. Release
publishing assembles all five runtimes through the Publish Maven workflow and
rejects incomplete sets before uploading. The daemon is distributed separately.

## Daemon Runtime

Download the executable for your platform from
[GitHub Releases](https://github.com/rafambn/wg-kotlin/releases), or build it:

```shell
cargo build --manifest-path wg-kotlin-daemon-rust/daemon/Cargo.toml --release
```

Publishing a GitHub release, including a prerelease, runs the Build and Test
workflow for Windows x64, macOS x64/ARM64, and Linux x64/ARM64. It attaches the
Windows `.exe` and Unix `.tar.gz` archives to that release. The archives preserve
the daemon's executable permission. Creating a tag alone does not trigger it.
The separate Publish Maven workflow builds BoringTun for all five platforms,
verifies a JVM consumer using one dependency, and publishes WG-Kotlin
to Maven Central. Running it manually performs the builds and verification without
publishing to Central. Neither workflow starts an elevated daemon.

The application supplies the privileged launcher. The library verifies OS peer
identity, opens local IPC sessions, and controls daemon termination:

```kotlin
import com.rafambn.wgkotlin.daemon.DaemonProcess

// Linux desktop example. The system polkit agent handles the permission dialog.
val daemon = DaemonProcess.launch { arguments ->
    ProcessBuilder(listOf("pkexec", "/absolute/path/wg-kotlin-daemon") + arguments)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
}
```

`DaemonProcess.launch(...)` registers the daemon used by `Vpn(...)`; it does not
create VPN objects. A `Vpn(engine)` manages multiple TUNs through
`start(configuration)`, `information(tunName)`, and `stop(tunName)`.

Each daemon has one endpoint: a named pipe on Windows or a Unix domain socket on
Linux/macOS. Each TUN owns an independent connection to that endpoint. The daemon
checks the app's kernel-reported PID and live process identity before accepting
configuration. The client verifies the daemon's identity too. There is no bearer
secret or TCP listener.

For macOS and Windows, supply an app-specific native launcher returning control
of the actual daemon process. Standard streams need not survive elevation.
See the [launcher contract and platform integration](docs/daemon-launching.md).

## Usage

Generate a key pair:

```kotlin
import com.rafambn.wgkotlin.util.WireGuardKeys

val localKeys = WireGuardKeys.generateKeyPair()
val presharedKey = WireGuardKeys.generatePresharedKey()
```

Start a VPN session from a coroutine. IP addresses and CIDRs must already be parsed:

```kotlin
import com.rafambn.wgkotlin.DnsConfig
import com.rafambn.wgkotlin.Vpn
import com.rafambn.wgkotlin.VpnConfiguration
import com.rafambn.wgkotlin.VpnPeer
import com.rafambn.wgkotlin.daemon.proto.Cidr
import com.rafambn.wgkotlin.daemon.proto.Ip
import com.rafambn.wgkotlin.daemon.proto.invoke
import kotlinx.io.bytestring.ByteString

val vpn = Vpn()
val address = Ip { value = Ip.Value.V4(ByteString(byteArrayOf(10, 0, 0, 2))) }
val dnsServer = Ip { value = Ip.Value.V4(ByteString(byteArrayOf(1, 1, 1, 1))) }
val peerAddress = Ip { value = Ip.Value.V4(ByteString(byteArrayOf(203.toByte(), 0, 113, 10))) }
val defaultRoute = Cidr {
    ip = Ip { value = Ip.Value.V4(ByteString(ByteArray(4))) }
    prefix = 0u
}

vpn.start(
    VpnConfiguration(
        interfaceName = "utun0",
        privateKey = localKeys.privateKey,
        listenPort = 0,
        addresses = listOf(Cidr { ip = address; prefix = 32u }),
        dns = DnsConfig(
            servers = listOf(dnsServer),
            searchDomains = listOf("example.com"),
        ),
        peers = listOf(
            VpnPeer(
                publicKey = "remote-peer-public-key-base64",
                presharedKey = presharedKey,
                endpointAddress = peerAddress,
                endpointPort = 51820,
                allowedIps = listOf(defaultRoute),
                persistentKeepalive = 25,
            ),
        ),
    ),
).getOrThrow()

val information = vpn.information("utun0")

vpn.stop("utun0").getOrThrow()

// Close all VPN sessions first. Requests daemon shutdown, then forces exit
// after five seconds if necessary.
daemon.close()

// For an unresponsive daemon, daemon.kill() forces OS termination directly.
```

## Runtime Notes

- Interface names must match `utun[0-9]+`, even on Linux and Windows.
- Linux daemon startup requires `resolvectl` in `PATH`, even when a session does not configure DNS.
- Linux route handling uses host route replacement/deletion semantics; callers should understand the routing impact of requested routes.
- The daemon accepts only local IPC connections from its owning app instance.
- Linux requires kernel 5.3+ for pidfd-based owner lifetime tracking.
- On macOS/Linux the daemon retains effective UID 0 and sets its real UID to the app's UID so the app can force termination. Windows launchers must retain a handle with termination rights.
- Default WireGuard listen port is `51820` when `listenPort` is not set.

## Development

WG-Kotlin uses the `com.rafambn` Maven namespace and Apache 2.0 license.
Maven Central publishing requires a Central user token and a signing key.
Provide `mavenCentralUsername`,
`mavenCentralPassword`, `signingInMemoryKey`, and `signingInMemoryKeyPassword` as
Gradle properties or `ORG_GRADLE_PROJECT_` environment variables. See the
[publishing plugin documentation](https://vanniktech.github.io/gradle-maven-publish-plugin/central/).

The Rust crate lives in `boringtun/` with the standard `src/lib.rs` layout.
The `wg-kotlin` Gradle module builds it and generates its bindings directly; the crate has no separate
Gradle module or Maven publication.

To upload WG-Kotlin for manual release in the Central Portal:

```shell
./gradlew publishToMavenCentral -PnativeRuntimeDirectory=/absolute/path/native-runtimes
```

`native-runtimes` must contain the shared libraries under their JNA resource
prefixes: `darwin-aarch64`, `darwin-x86-64`, `linux-aarch64`, `linux-x86-64`, and
`win32-x86-64`. The Publish Maven workflow builds and assembles this directory.
For release publishing, configure the repository secrets `MAVEN_CENTRAL_USERNAME`,
`MAVEN_CENTRAL_PASSWORD`, `GPG_KEY_CONTENTS`, and `SIGNING_PASSWORD`. The release tag
must match the Gradle version, optionally prefixed with `v`.

After publishing locally, verify an independent JVM consumer that loads BoringTun
without a daemon:

```shell
./gradlew -p tests/jvm-consumer run
```

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
