# Repository Guidelines

## Project Structure & Module Organization
`wg-kotlin` (WireGuard Kotlin) is a multi-module Gradle project for a Kotlin Multiplatform WireGuard implementation.

- `wg-kotlin/`: core Kotlin Multiplatform library. Kotlin lives under `src/commonMain`, `src/jvmMain`, `src/commonTest`, and `src/jvmTest`. Rust sources live in `src/commonMain/rust`.
- `docs/`: repository notes and design scratch files.

## Daemon Platform Policy
- Daemon sessions intentionally accept only interface names matching `utun[0-9]+`, even on Linux and Windows, to keep cross-platform behavior consistent.
- Linux route installation intentionally uses host route replacement/deletion semantics instead of separate routing tables or route snapshot restoration. This keeps platform behavior simple and aligned across adapters; callers are expected to understand the routing impact of the routes they request.
- Linux daemon startup intentionally requires `resolvectl`, even for sessions that do not configure DNS.

## Build, Test, and Development Commands
- `./gradlew build`: compile all modules and run their default verification tasks.
- `./gradlew :wg-kotlin:check`: run core module compilation and tests.
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

## Logging Policy
- Daemon logging uses **only Scribe Scrolls** (`newScroll` / `seal`). Do not use Scribe `note(...)` anywhere in the daemon module. All events, including lifecycle and errors, are modeled as wide contextual scrolls.

## Communication
Use your caveman skill. Default communication mode is `/caveman full` unless explicitly changed by the user.
