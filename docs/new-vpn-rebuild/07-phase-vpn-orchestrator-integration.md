# Phase 07: VPN Orchestrator Integration

## Objective

Wire `Vpn` to the new architecture end-to-end: `SessionManager` + `VpnInterface` + daemon client.

## Scope

1. Integrate orchestrator behavior against merged `VpnInterface` ownership.
2. Implement deterministic orchestration flow with observational state derivation.
3. Ensure rollback safety across partial failures.
4. Integrate common packet loop lifecycle with orchestrator lifecycle.

## Work Breakdown

1. Refactor `Vpn` constructor wiring:
- default factory path for production
- injectable dependencies for testing
2. Implement orchestration flow:
- `create`: ensure interface exists
- `start`: bring interface up, apply config, reconcile sessions, start packet loop
- `stop`: stop packet loop, close sessions, then down interface
- `delete`: stop packet loop, close sessions, delete interface, clear state
3. Implement health checks:
- `exists`
- `isRunning`
- `information`
4. Implement transaction-style rollback:
- if session or packet-loop start fails after interface up, perform controlled unwind
5. Keep alert callback path and map internal errors to user-facing alerts.

## Deliverables

1. New orchestrator-based `Vpn` implementation in `:new-vpn`.
2. Merged interface-configuration policy validated and documented.
3. Lifecycle integration tests with fake and JVM daemon-backed paths.
4. Integration tests for start/stop packet-loop ownership and rollback boundaries.

## Exit Criteria

1. Public lifecycle API works against real daemon path.
2. Rollback tests pass for every failure boundary.
3. `Vpn` no longer owns transport internals directly.
4. Packet loop starts/stops deterministically under orchestrator control.

## Risks and Controls

1. Risk: lifecycle race conditions.
Control: explicit synchronized state transitions and idempotent operations.
2. Risk: hidden coupling returns through convenience methods.
Control: enforce single-direction dependency and contract tests.
