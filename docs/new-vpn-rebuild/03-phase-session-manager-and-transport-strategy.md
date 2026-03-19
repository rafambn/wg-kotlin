# Phase 03: SessionManager and Common Data-Plane Loop

## Objective

Implement transport/session lifecycle as an isolated domain service with a common packet I/O loop.

## Scope

1. Build `SessionManager` with full peer-session reconciliation.
2. Integrate UniFFI `TunnelSession` through a clean Kotlin wrapper.
3. Implement packet data-plane loop in `commonMain` (`TUN <-> UDP` + timer tasks).
4. Select session factory from `Engine` (`BORINGTUN`, `QUIC` placeholder).

## Work Breakdown

1. Create `VpnSession` abstraction (per peer session handle).
2. Add `ManagedSession` registry keyed by peer public key.
3. Implement core operations:
- `reconcileSessions`
- `closeSession`
- `closeAll`
4. Add packet result model and packet operations on `VpnSession`:
- `encryptRawPacket`
- `decryptToRawPacket`
- `runPeriodicTask`
5. Add common packet loop runtime:
- reads packets from `TunPort`
- writes encrypted datagrams to `UdpPort`
- decrypts incoming datagrams and writes to `TunPort`
- executes timer-driven tasks and flush loops until `Done`
6. Add deterministic session index generation.
7. Add engine factories:
- `BoringTunVpnSessionFactory`
- `QuicVpnSessionFactory` (explicit unsupported)
8. Connect BORINGTUN factory to existing Rust `TunnelSession` binding where available.
9. Keep packet loop platform-agnostic via port interfaces (`TunPort`, `UdpPort`, timer abstraction).

## Deliverables

1. `SessionManager` implementation in `commonMain`.
2. Common data-plane loop implementation in `commonMain`.
3. BORINGTUN session factory implementation backed by UniFFI.
4. Unit tests for:
- duplicate peer key rejection
- stale session removal
- config change reconciliation
- partial-create rollback
- packet loop behavior for encrypt/decrypt/timer branches

## Exit Criteria

1. `Vpn` can delegate session lifecycle to `SessionManager` without platform dependencies.
2. Session behavior is deterministic and idempotent.
3. Failure paths close newly-created sessions on rollback.
4. Packet loop runs in common runtime without daemon dependency.

## Risks and Controls

1. Risk: memory/resource leaks in session replacement.
Control: mandatory close on stale and failed sessions.
2. Risk: mismatched transport semantics for QUIC.
Control: explicit unsupported QUIC factory with clear error path.
3. Risk: packet-loop livelock on non-`Done` outcomes.
Control: bounded flush policy and deterministic loop tests.
