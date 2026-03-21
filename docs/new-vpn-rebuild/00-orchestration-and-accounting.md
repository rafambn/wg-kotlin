# New VPN Rebuild: Orchestration and Accounting

## Mission

Build a new architecture in `:new-vpn` where:

- `Vpn` is only the orchestrator.
- `SessionManager` owns transport/session lifecycle.
- Packet I/O loop (`TUN <-> UDP`, encrypt/decrypt/timers) runs in `:new-vpn` common code.
- `VpnInterface` owns OS interface lifecycle.
- JVM privileged operations run in a dedicated subprocess daemon (control-plane only).

This plan assumes the current baseline in `new-vpn`:

- Kotlin: minimal in-memory `Vpn` and merged `VpnInterface` configuration/lifecycle models.
- Rust: working `TunnelSession` UniFFI binding with packet operations.

## Target Module Topology

1. `:new-vpn`
Purpose: KMP core API, orchestration, and common data-plane runtime (`Vpn`, `SessionManager`, `VpnInterface`, packet loop contracts).
2. `:new-vpn-daemon-protocol`
Purpose: typed command/request/response models shared by daemon and JVM client.
3. `:new-vpn-daemon-jvm`
Purpose: privileged daemon executable with strict allowlist execution for OS commands only.
4. `:new-vpn-daemon-client-jvm` (optional; can stay in `:new-vpn` jvmMain initially)
Purpose: kRPC client used by JVM `VpnInterface` implementation, defaulting to local loopback transport.

## Phase Map

1. Phase 01: Foundation and module scaffolding.
2. Phase 02: Domain model and contract design.
3. Phase 03: SessionManager and common data-plane loop.
4. Phase 04: VpnInterface and platform edge adapters.
5. Phase 05: Daemon protocol and local RPC IPC.
6. Phase 06: Privileged daemon implementation.
7. Phase 07: Vpn orchestrator integration and lifecycle semantics.
8. Phase 08: Security/observability/performance hardening.
9. Phase 09: Migration cutover, docs, and release.

## Dependency Rules

1. `:new-vpn` must not depend on daemon executable module.
2. `:new-vpn-daemon-jvm` depends on `:new-vpn-daemon-protocol` only.
3. JVM client depends on `:new-vpn-daemon-protocol`.
4. Rust tunnel binding stays in `:new-vpn` until a dedicated transport native module is needed.
5. Daemon protocol must not carry raw packet payloads for runtime forwarding.

## Accounting Model

Tracking unit: `Effort Point (EP)` where 1 EP ~= 0.5 engineering day.

Planned EP by phase:

1. Phase 01: 8 EP
2. Phase 02: 10 EP
3. Phase 03: 14 EP
4. Phase 04: 16 EP
5. Phase 05: 12 EP
6. Phase 06: 16 EP
7. Phase 07: 12 EP
8. Phase 08: 14 EP
9. Phase 09: 8 EP

Total: `110 EP`

## Progress Ledger

Use this table as the source of truth during execution.

| Phase | Status | Planned EP | Spent EP | Start Date | End Date | Gate Result | Notes |
|---|---|---:|---:|---|---|---|---|
| 01 | Completed | 8 | 8 | 2026-03-19 | 2026-03-19 | Passed | Module scaffolding, architecture checks, and CI entry tasks added |
| 02 | Completed | 10 | 10 | 2026-03-19 | 2026-03-19 | Passed | Core contracts added, `VpnAdapter` merged into `VpnInterface`, and state simplified to live observations |
| 03 | Completed | 14 | 14 | 2026-03-19 | 2026-03-19 | Passed | Session reconciliation finalized, engine factories wired, common packet loop (`TunPort`/`UdpPort`/ticker) implemented, and loop behavior covered by tests |
| 04 | Completed | 16 | 16 | 2026-03-20 | 2026-03-20 | Passed | `PlatformInterfaceFactory` (`expect/actual`) added, JVM interface layer split behind daemon-only `InterfaceCommandExecutor` boundary, and packet I/O adapter `KtorDatagramUdpPort` delivered in `commonMain` with rollback/idempotency tests |
| 05 | In progress | 12 | 6 | 2026-03-21 | - | In progress | `:new-vpn-daemon-protocol` now has typed serializable request/response models plus shared `@Rpc` contract; `:new-vpn-daemon-jvm` exposes Ktor+kRPC control-plane service; `:new-vpn-daemon-client-jvm` executes typed requests with timeout/protocol/failure mapping and smoke tests |
| 06 | Not started | 16 | 0 | - | - | - | - |
| 07 | Not started | 12 | 0 | - | - | - | - |
| 08 | Not started | 14 | 0 | - | - | - | - |
| 09 | Not started | 8 | 0 | - | - | - | - |

## Stage Gates

Each phase exits only if all gate criteria pass:

1. `Scope Gate`: all listed deliverables merged.
2. `Quality Gate`: tests and static checks pass.
3. `Contract Gate`: public APIs reviewed and frozen for the next phase.
4. `Security Gate` (phases 05+): threat checks completed for added attack surface.

## Risk Register

| ID | Risk | Impact | Mitigation |
|---|---|---|---|
| R1 | Re-coupling core and platform layers | High | Enforce dependency rules in build files and code reviews |
| R2 | Daemon command injection | Critical | Typed protocol + allowlist + no shell execution |
| R3 | Incomplete lifecycle rollback | High | Explicit rollback paths and integration tests |
| R4 | Rust/Kotlin mismatch in session semantics | High | Session conformance tests around UniFFI wrapper |
| R5 | Scope drift | Medium | Gate phases strictly and use this accounting file as authority |
| R6 | Data-plane accidentally re-coupled to daemon | High | Keep packet loop in `common`; daemon command set explicitly excludes packet forwarding |

## Decision Log Template

Record architecture decisions in this file as appended entries.

- Decision ID:
- Date:
- Context:
- Decision:
- Consequence:

### Decision Entry ADR-02

- Decision ID: ADR-02
- Date: 2026-03-19
- Context: Phase 02 needs stable orchestrator/session/interface semantics before implementation phases 03 and 04.
- Decision: Freeze `VpnState`, `SessionManager`, `VpnInterface`, and `VpnEvent` contracts with invariant rules for interface name, peer uniqueness, and idempotent stop/delete.
- Consequence: Next phases can implement concrete backends without changing core orchestration contracts.

### Decision Entry ADR-03

- Decision ID: ADR-03
- Date: 2026-03-19
- Context: `VpnInterface` and `VpnAdapter` overlapped in lifecycle/configuration ownership.
- Decision: Merge `VpnAdapter` semantics into `VpnInterface`; `Vpn` orchestrates `SessionManager` + `VpnInterface` only.
- Consequence: Phase 04 focuses on concrete platform implementations and command execution boundaries, while phase 07 no longer includes adapter-removal work.

### Decision Entry ADR-04

- Decision ID: ADR-04
- Date: 2026-03-19
- Context: Persisted synthetic state markers (`deletedByOrchestrator`, `lastFailure`, `lastObservedState`) created divergence from real OS/daemon observations.
- Decision: Remove synthetic state memory from `Vpn`; `state()` now derives from live interface/session observations only.
- Consequence: Phase 07 state-machine work is reduced; failure/delete meaning is represented by operation result and `VpnEvent` stream.

### Decision Entry ADR-05

- Decision ID: ADR-05
- Date: 2026-03-19
- Context: Architecture ownership for WireGuard runtime packet flow was ambiguous between common runtime and privileged daemon.
- Decision: Packet data-plane (`TUN <-> UDP`, encryption/decryption, timer-driven handshake/keepalive tasks) runs in `:new-vpn` common runtime; daemon is limited to privileged OS control-plane commands.
- Consequence: Phase 03 must deliver common packet loop contracts and runtime behavior; phases 04 and 06 provide platform I/O adapters and privileged command execution without owning transport packet forwarding.

### Decision Entry ADR-06

- Decision ID: ADR-06
- Date: 2026-03-20
- Context: Phase 04 requires JVM interface operations now, but daemon IPC will only be introduced in phase 05.
- Decision: Introduce `InterfaceCommandExecutor` as the only command boundary consumed by `JvmVpnInterface`; forbid local OS command execution in `:new-vpn` JVM code and reserve privileged command execution for daemon-backed executors only.
- Consequence: Phase 05 plugs daemon IPC implementations into `InterfaceCommandExecutor` without changing `Vpn`, `SessionManager`, or `VpnInterface` contracts.

### Decision Entry ADR-07

- Decision ID: ADR-07
- Date: 2026-03-21
- Context: Phase 05 needed a first transport choice balancing implementation speed, typed safety, and future migration options.
- Decision: Adopt `kotlinx-rpc` (kRPC) over Ktor WebSocket as the default local IPC transport for daemon control-plane requests, with shared `@Rpc` contracts and typed envelopes in `:new-vpn-daemon-protocol`.
- Consequence: The daemon/client integration is delivered earlier with transport-specific logic isolated in `:new-vpn-daemon-client-jvm` and `:new-vpn-daemon-jvm`, preserving a migration path to alternative transports (UDS, remote endpoint, or gRPC) without changing core command payload models.

## Definition of Done (Program Level)

1. `Vpn` orchestrates `SessionManager` and `VpnInterface` only.
2. Packet data-plane loop runs in common runtime and is independent from daemon IPC.
3. JVM privileged actions run only through daemon subprocess protocol.
4. No generic command execution path exists.
5. Core module can be tested with fake interface/session dependencies.
6. End-to-end lifecycle tests pass: `create`, `start`, `stop`, `delete`, peer updates, failure rollback.
