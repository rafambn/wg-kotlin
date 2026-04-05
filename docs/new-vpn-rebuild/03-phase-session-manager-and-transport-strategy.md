# Phase 03: SessionManager and Userspace Runtime

## Objective

Implement BORINGTUN as a userspace runtime: session lifecycle, packet routing,
UDP endpoint handling, and runtime peer accounting all stay in `:new-vpn`.

## Implementation Status

Status: Completed (reconciled)  
As Of: 2026-04-08

## Scope

1. Build `SessionManager` with full peer-session reconciliation.
2. Integrate UniFFI `TunnelSession` through a clean Kotlin wrapper.
3. Implement userspace packet forwarding in `commonMain`.
4. Select session factory from `Engine` (`BORINGTUN`, `QUIC` placeholder).

## Implemented in Code

1. `ManagedSession` registry keyed by peer public key.
2. Deterministic session index generation and rollback-safe reconciliation.
3. `VpnSession` packet operations:
- `encryptRawPacket`
- `decryptToRawPacket`
- `runPeriodicTask`
4. Endpoint-aware UDP contracts:
- `UdpEndpoint`
- `UdpDatagram`
- `UdpPort.receiveDatagram`
- `UdpPort.sendDatagram`
5. `UserspaceVpnRuntime` for multi-peer forwarding:
- longest-prefix route selection for outgoing TUN packets
- exact endpoint demux for incoming UDP datagrams
- timer sweeps across all sessions
- per-peer byte counters
6. Validation hardening for BORINGTUN:
- peer endpoints are mandatory
- allowed-IP ownership must be unambiguous
7. `BoringTunVpnSessionFactory` backed by the existing Rust `TunnelSession`.

## Deliverables

1. `SessionManager` implementation in `commonMain`.
2. `VpnPacketLoop` low-level loop primitive in `commonMain`.
3. `UserspaceVpnRuntime` multi-peer runtime in `commonMain`.
4. Tests for:
- duplicate peer key rejection
- stale session removal
- partial-create rollback
- longest-prefix routing
- endpoint demux
- periodic task execution
- runtime peer stats

## Exit Criteria

1. `Vpn` can delegate peer-session ownership to `SessionManager`.
2. Userspace runtime behavior is deterministic and platform-agnostic.
3. Packet forwarding has no daemon dependency.
4. Peer state is no longer modeled as an OS-level apply step.

## Risks and Controls

1. Risk: memory/resource leaks in session replacement.  
Control: mandatory close on stale and failed sessions.
2. Risk: ambiguous routing across peers.  
Control: validation rejects overlapping allowed-IP ownership.
3. Risk: packet-loop livelock on non-`Done` outcomes.  
Control: bounded flush policy and deterministic loop tests.
