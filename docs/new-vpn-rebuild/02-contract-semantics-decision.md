# ADR-02: Core Contract Semantics Freeze (Phases 03-04)

Date: 2026-03-19  
Status: Accepted, reconciled on 2026-04-08

## Context

Phase 02 defines the contract boundary that phases 03-06 implement. The main
risk is mixing userspace WireGuard runtime concerns with privileged OS
configuration concerns.

## Decision

1. `VpnState` is observational with states: `NotCreated`, `Created`, `Running`.
2. `Vpn` owns lifecycle transitions and publishes `VpnEvent` telemetry without internal buffering.
3. `SessionManager` owns peer-session reconciliation and live session registry.
4. `UserspaceVpnRuntime` owns packet forwarding, endpoint demux, timer sweeps, and runtime peer byte counters.
5. `VpnInterface` owns interface lifecycle, configuration application, and access to the live `TunPort`.
6. Privileged daemon scope is control-plane only and must not own peer state, packet forwarding, or encryption/decryption.
7. Invariants are mandatory for orchestration:
- interface name must be non-blank
- peer public keys must be unique
- BORINGTUN peers must provide stable endpoints
- allowed-IP ownership must be unambiguous across peers
- `stop` and `delete` are idempotent

## Consequences

1. Phase 03 can evolve the userspace runtime without changing the `Vpn` API.
2. Phase 04 can evolve local TUN ownership and privileged control application without reintroducing WireGuard-backend semantics.
3. Phases 05-06 stay limited to daemon protocol and OS command execution.
4. Phase 07 is reduced to runtime-job orchestration and production factory cutover.
