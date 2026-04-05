# Phase 04: VpnInterface and TUN Ownership Boundary

## Objective

Keep `VpnInterface` limited to interface lifecycle and control-plane application,
while making the live TUN handle available to the userspace runtime.

## Implementation Status

Status: Completed (re-scoped)  
As Of: 2026-04-08

## Scope

1. Build interface lifecycle abstraction independent of session code.
2. Add local TUN ownership to the JVM interface layer.
3. Keep privileged commands behind a control-only boundary.
4. Deliver packet I/O adapters and test doubles needed by the userspace runtime.

## Implemented in Code

1. `VpnInterface` now exposes `tunPort()` for the live interface handle.
2. `JvmVpnInterface` now owns:
- local TUN handle lifecycle through `TunProvider`
- up/down state
- MTU/address/routes/DNS application through `InterfaceCommandExecutor`
- configuration snapshots and rollback behavior
3. `InterfaceCommandExecutor` scope was reduced:
- no peer apply
- no interface create/delete
- no peer-stats read command
4. Added JVM-side TUN ownership primitives:
- `OwnedTunPort`
- `TunProvider`
- `InMemoryTunProvider`
5. Added packet I/O adapters and fakes:
- `KtorDatagramUdpPort`
- `InMemoryTunPort`
- `InMemoryUdpPort`
- manual periodic ticker fakes
6. `PlatformInterfaceFactory.jvm` remains intentionally wired to in-memory providers until phase 07 production cutover.

## Deliverables

1. Core `VpnInterface` contract and factory.
2. JVM interface implementation wired to control executor and local TUN provider abstractions.
3. Packet I/O adapter baseline wired to common loop contracts.
4. Tests validating idempotency, rollback, and `TunPort` ownership behavior.

## Exit Criteria

1. `Vpn` lifecycle runs through a single `VpnInterface` contract.
2. Session lifecycle and interface lifecycle stay independent.
3. Packet loop can consume a live `TunPort` without daemon ownership.
4. No interface-level API implies OS WireGuard peer ownership.

## Risks and Controls

1. Risk: platform branching leaks into core.  
Control: keep all OS branching inside JVM interface implementations.
2. Risk: incomplete cleanup on failure.  
Control: rollback contract and tests for every apply step.
3. Risk: production cutover arrives before native TUN providers are ready.  
Control: keep default factory in-memory until phase 07 integration work.
