# New VPN Rebuild: Orchestration and Accounting

## Mission

Build a new architecture in `:new-vpn` where:

- `Vpn` is only the orchestrator.
- `SessionManager` owns transport/session lifecycle.
- Packet I/O loop (`TUN <-> UDP`, encrypt/decrypt/timers) runs in `:new-vpn` common code.
- `VpnInterface` owns OS interface lifecycle.
- JVM privileged operations run in a dedicated subprocess daemon (control-plane only).

This plan assumes the current baseline in `new-vpn`:

- Kotlin core: orchestrator `Vpn` lifecycle (`create/start/stop/delete/reconfigure`), observational `VpnState`, and `VpnEvent` telemetry.
- Session runtime: `SessionManager` reconciliation, engine factories (`BORINGTUN`/`QUIC` placeholder), `VpnPacketLoop`, and `UserspaceVpnRuntime` with endpoint-aware UDP routing and runtime peer stats.
- JVM interface layer: `JvmVpnInterface` behind `InterfaceCommandExecutor` plus local `TunProvider`, with `PlatformInterfaceFactory.jvm` currently wiring in-memory implementations.
- Daemon stack: shared `DaemonProcessApi` + `CommandResult`, JVM client (`DaemonProcessClient`), and daemon kRPC server with typed control-plane command handlers.
- Rust: working `TunnelSession` UniFFI binding with packet operations.

## Target Module Topology

1. `:new-vpn`
Purpose: KMP core API, orchestration, and common data-plane runtime (`Vpn`, `SessionManager`, `VpnInterface`, packet loop contracts).
2. `:new-vpn-daemon-protocol`
Purpose: typed command/request/response models shared by daemon and JVM client.
3. `:new-vpn-daemon-jvm`
Purpose: privileged daemon executable with strict allowlist execution for OS commands only.
4. `:new-vpn-daemon-client-jvm` (optional; can stay in `:new-vpn` jvmMain initially)
Purpose: kRPC client for daemon control-plane requests; currently not yet wired into `:new-vpn` default JVM factory.

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
| 03 | Completed (reconciled) | 14 | 14 | 2026-03-19 | 2026-04-08 | Passed | `UserspaceVpnRuntime`, endpoint-aware `UdpPort`, longest-prefix routing, endpoint demux, and runtime peer stats delivered |
| 04 | Completed (re-scoped) | 16 | 16 | 2026-03-20 | 2026-04-08 | Passed | `JvmVpnInterface` now owns a live `TunPort` through `TunProvider`; command boundary reduced to `InterfaceCommandExecutor`; default JVM factory remains in-memory until phase 07 cutover |
| 05 | Completed (reconciled) | 12 | 12 | 2026-03-21 | 2026-04-08 | Passed | Daemon protocol reduced to control-plane RPCs only; peer apply/create/delete/read-peer-stats removed from the shared surface |
| 06 | Completed (reconciled) | 16 | 16 | 2026-04-02 | 2026-04-08 | Passed | Daemon planners and validators now cover control-plane commands only; WireGuard-backend peer operations removed |
| 07 | Deferred | 12 | 0 | - | - | - | Remaining work is orchestrator-owned runtime-job lifecycle, production provider cutover, and live runtime stats wiring |
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
- Decision: Introduce a control-only executor boundary consumed by `JvmVpnInterface`; local TUN ownership stays in `:new-vpn`, while privileged OS command execution is reserved for daemon-backed executors.
- Consequence: Phase 05 can deliver daemon protocol/client/server scaffolds without changing `Vpn`, `SessionManager`, or `VpnInterface` contracts; phase 04 no longer implies OS WireGuard peer ownership.

### Decision Entry ADR-07

- Decision ID: ADR-07
- Date: 2026-03-21
- Context: Phase 05 needed a first transport choice balancing implementation speed, typed safety, and future migration options.
- Decision: Adopt `kotlinx-rpc` (kRPC) over Ktor WebSocket transport (`/services`) for daemon control-plane requests, with shared `@Rpc` contract (`DaemonProcessApi`) and typed results (`DaemonCommandResult`) in `:new-vpn-daemon-protocol`.
- Consequence: The daemon/client integration is delivered early with transport-specific logic isolated in `:new-vpn-daemon-client-jvm` and `:new-vpn-daemon-jvm`, preserving a migration path to alternative transports (UDS, remote endpoint, or gRPC) without changing core command payload models.

### Decision Entry ADR-08

- Decision ID: ADR-08
- Date: 2026-04-02
- Context: The ping/handshake endpoint carried an unused `nonce` parameter and a static `helloToken` response field that added complexity without value; a successful kRPC round-trip already proves connectivity and protocol compatibility.
- Decision: Simplify `ping()` to a zero-argument method returning a singleton `PingResponse` object; remove `DAEMON_HELLO_TOKEN` constant and token validation from `handshake()`.
- Consequence: Handshake is now a plain connectivity check; any future version negotiation can be added as a dedicated field without reintroducing the removed ceremony.

### Decision Entry ADR-09

- Decision ID: ADR-09
- Date: 2026-04-02
- Context: Phase 05 delivered standalone daemon protocol/client/server scaffolding, but privileged command execution is not yet implemented in the daemon.
- Decision: Keep `PlatformInterfaceFactory.jvm` wired to in-memory control and TUN providers until phase 07 production cutover is ready.
- Consequence: Core orchestration tests remain deterministic and decoupled from daemon lifecycle, while program-level DoD items requiring production providers remain open.

### Decision Entry ADR-10

- Decision ID: ADR-10
- Date: 2026-04-02
- Context: Phase 06 required replacing scaffolded `UNKNOWN_COMMAND` responses with hardened privileged command execution.
- Decision: Implement daemon-side command execution directly in `DaemonProcessApiImpl` through explicit per-method handlers, OS-specific allowlist command catalogs (Linux/macOS/Windows), strict payload validation, and process execution via `ProcessBuilder` without shell interpolation.
- Consequence: The daemon now executes typed control-plane operations with validation and auditability, while production cutover in `PlatformInterfaceFactory.jvm` remains a later phase step.

### Decision Entry ADR-11

- Decision ID: ADR-11
- Date: 2026-04-08
- Context: The earlier daemon/apply-peer path assumed an OS WireGuard backend, but BORINGTUN peer state and packet forwarding now live in-process.
- Decision: Treat `Engine.BORINGTUN` as userspace-only; remove daemon/backend peer control, interface create/delete RPCs, and daemon peer-stats reads; keep peer routing and byte accounting in `UserspaceVpnRuntime`.
- Consequence: Phases 03-06 are reconciled around a userspace runtime plus control-only daemon, and phase 07 narrows to runtime-job orchestration plus production provider cutover.

## Definition of Done (Program Level)

1. `Vpn` orchestrates `SessionManager` and `VpnInterface` only.
2. Packet data-plane loop runs in common runtime and is independent from daemon IPC.
3. JVM privileged actions run only through daemon subprocess protocol.
4. No generic command execution path exists.
5. Core module can be tested with fake interface/session dependencies.
6. End-to-end lifecycle tests pass: `create`, `start`, `stop`, `delete`, peer updates, failure rollback.

Current gap snapshot (2026-04-08): the daemon surface and userspace runtime are aligned, but program-level item 3 still awaits production `PlatformInterfaceFactory.jvm` cutover and item 6 still awaits orchestrator-owned runtime-job integration in phase 07.
