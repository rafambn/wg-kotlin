# Repository Guidelines

## Project Structure & Module Organization
`KMP-VPN` is a multi-module Gradle project centered on the `new-vpn` rebuild.

- `new-vpn/`: core Kotlin Multiplatform library. Kotlin lives under `src/commonMain`, `src/jvmMain`, `src/commonTest`, and `src/jvmTest`. Rust sources live in `src/commonMain/rust`.
- `new-vpn-daemon-protocol/`: shared daemon RPC models and transport contracts.
- `new-vpn-daemon-jvm/`: privileged JVM daemon executable.
- `new-vpn-daemon-client-jvm/`: JVM client used by the core module to talk to the daemon.
- `docs/`: repository notes and design scratch files.

Keep module boundaries intact: `:new-vpn` must not depend on `:new-vpn-daemon-jvm`; daemon modules communicate through `:new-vpn-daemon-protocol`.

## Build, Test, and Development Commands
- `./gradlew build`: compile all modules and run their default verification tasks.
- `./gradlew ciPhase01`: run the phase 01 CI entry task, including architecture boundary checks.
- `./gradlew :new-vpn:check`: run core module compilation and tests.
- `./gradlew :new-vpn-daemon-jvm:test`: run daemon JVM tests only.
- `./gradlew :new-vpn-daemon-client-jvm:test`: run client JVM tests only.
- `cargo test --manifest-path new-vpn/Cargo.toml`: run Rust tests when changing the embedded Rust library directly.

Use JDK 17 for Gradle builds.

## Coding Style & Naming Conventions
Follow the existing Kotlin style:

- 4-space indentation, no tabs.
- Packages under `com.rafambn.kmpvpn`.
- `PascalCase` for types, `camelCase` for functions/properties, `UPPER_SNAKE_CASE` for constants.
- Prefer small, explicit APIs and descriptive filenames such as `DaemonPayloadValidator.kt` or `VpnStateTransitionTest.kt`.

No formatter config is checked in here, so keep changes consistent with surrounding code.

## Testing Guidelines
Tests use `kotlin("test")` with JUnit 5 (`useJUnitPlatform()` in all JVM modules). Place tests beside the relevant source set and end filenames with `Test`, for example `src/jvmTest/kotlin/.../JvmInterfaceManagerTest.kt`.

Cover both happy path and failure behavior, especially around state transitions, daemon IPC, and platform-specific command planning.

## Code decisions

In the `docs` folder there are some text files that establish some business logic for some parts of the code.

In `docs/rules.txt` you will find instructions on how this rules behave

You should always read the rules of the file that you are about change

## Communication
Use your caveman skill. Default communication mode is `/caveman full` unless explicitly changed by the user.
