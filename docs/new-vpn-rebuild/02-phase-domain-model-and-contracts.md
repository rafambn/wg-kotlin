# Phase 02: Domain Model and Contracts

## Objective

Define the final core contracts before platform implementation starts.

## Scope

1. Design `Vpn` orchestration surface.
2. Design `SessionManager` contracts.
3. Design data-plane and `VpnInterface` contracts and state model.
4. Define error and event model shared by core and JVM.

## Contract Set

1. `VpnState` (sealed class): observational states `NotCreated`, `Created`, `Running`.
2. `SessionManager`:
- `reconcileSessions(config)`
- `sessions()`
- `session(peerKey)`
- `closeAll()`
3. `VpnSession` and packet loop contracts:
- `encryptRawPacket` / `decryptToRawPacket`
- `runPeriodicTask`
- `TunPort` / `UdpPort` / timer abstraction contracts for platform I/O boundaries
4. `VpnInterface`:
- `exists(interfaceName)`
- `create(config)`
- `up()` / `down()`
- `delete()`
- `isUp()`
- `configuration()`
- `reconfigure(config)`
- `readInformation()`
5. `VpnEvent` stream model for lifecycle telemetry (`Alert(message)`, `Failure(message)`).
   Failure and delete semantics are emitted as events/errors instead of persisted synthetic states.

## Work Breakdown

1. Merge `VpnAdapter` responsibilities into `VpnInterface` and keep lifecycle state in explicit models.
2. Freeze ownership rule: packet data-plane stays in common runtime; daemon is control-plane only.
3. Create new interfaces in `commonMain` only.
4. Add KDoc for all public contracts.
5. Define invariants:
- interface name non-empty
- unique peer public keys
- idempotent `stop`/`delete`

## Deliverables

1. Compiling contract-first API.
2. Unit tests for invariants and model transitions.
3. Architecture decision note freezing contract semantics for phases 03-04.

## Exit Criteria

1. Contracts are stable and reviewed.
2. No platform command or daemon concerns leak into core contracts.
3. Test coverage exists for all transition and invariant rules.

## Risks and Controls

1. Risk: overfitting to current placeholder behavior.
Control: design for eventual real interface/session backends.
2. Risk: contract ambiguity.
Control: write tests that encode expected lifecycle semantics.
