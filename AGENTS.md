# Repository Guidelines

## Project Structure & Module Organization
`wg-kotlin` (WireGuard Kotlin) is a multi-module Gradle project for a Kotlin Multiplatform WireGuard implementation.

- `wg-kotlin/`: core Kotlin Multiplatform library. Kotlin lives under `src/commonMain`, `src/jvmMain`, `src/commonTest`, and `src/jvmTest`. Rust sources live in `src/commonMain/rust`.
- `wg-kotlin-daemon-protocol/`: shared daemon RPC models and transport contracts.
- `wg-kotlin-daemon-jvm/`: privileged JVM daemon executable.
- `docs/`: repository notes and design scratch files.

Keep module boundaries intact: `:wg-kotlin` must not depend on `:wg-kotlin-daemon-jvm`; daemon modules communicate through `:wg-kotlin-daemon-protocol`.

## Build, Test, and Development Commands
- `./gradlew build`: compile all modules and run their default verification tasks.
- `./gradlew ciPhase01`: run the phase 01 CI entry task, including architecture boundary checks.
- `./gradlew :wg-kotlin:check`: run core module compilation and tests.
- `./gradlew :wg-kotlin-daemon-jvm:test`: run daemon JVM tests only.
- `cargo test --manifest-path wg-kotlin/Cargo.toml`: run Rust tests when changing the embedded Rust library directly.

Use JDK 17 for Gradle builds.

## Coding Style & Naming Conventions
Follow the existing Kotlin style:

- 4-space indentation, no tabs.
- Packages under `com.rafambn.wgkotlin`.
- `PascalCase` for types, `camelCase` for functions/properties, `UPPER_SNAKE_CASE` for constants.
- Prefer small, explicit APIs and descriptive filenames such as `DaemonPayloadValidator.kt` or `VpnStateTransitionTest.kt`.

No formatter config is checked in here, so keep changes consistent with surrounding code.

## Testing Guidelines
Tests use `kotlin("test")` with JUnit 5 (`useJUnitPlatform()` in all JVM modules). Place tests beside the relevant source set and end filenames with `Test`, for example `src/jvmTest/kotlin/.../JvmInterfaceManagerTest.kt`.

Cover both happy path and failure behavior, especially around state transitions, daemon IPC, and platform-specific command planning.

## Communication
Use your caveman skill. Default communication mode is `/caveman full` unless explicitly changed by the user.
