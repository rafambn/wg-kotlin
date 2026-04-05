# Phase 07: VPN Orchestrator Integration

## Objective

Finish the production cutover that wires the userspace runtime into `Vpn`
lifecycle operations.

## Implementation Status

Status: Deferred  
As Of: 2026-04-08

## Already Implemented

1. `Vpn` constructor supports production defaults plus injectable test dependencies.
2. Core lifecycle orchestration is implemented:
- `create`: ensure interface exists and reconcile sessions
- `start`: validate/configure, reconcile sessions, and bring interface up
- `stop`: close sessions then bring interface down
- `delete`: idempotent stop/close/delete flow with best-effort unwind ordering
3. Health and state APIs are implemented:
- `exists`
- `isRunning`
- `state`
- `configuration`
4. `reconfigure` updates interface configuration and re-runs session reconciliation.
5. Error mapping publishes `VpnEvent.Failure`; non-fatal lifecycle conflicts publish `VpnEvent.Alert`.

## Remaining Scope

1. Start and stop a real `UserspaceVpnRuntime` job from `Vpn.start()`, `stop()`, and `delete()`.
2. Feed live `TunPort` and endpoint-aware `UdpPort` instances into that runtime.
3. Cut `PlatformInterfaceFactory.jvm` over from in-memory providers to production `TunProvider` and production `InterfaceCommandExecutor`.
4. Wire runtime peer byte counters into orchestrator-facing information reads.
5. Add lifecycle integration tests for the production JVM path.

## Deliverables

1. Orchestrator-owned runtime job lifecycle.
2. Production JVM factory cutover.
3. Live runtime stats wiring.
4. Integration tests covering start/stop/reconfigure/delete on the production path.

## Exit Criteria

1. Public lifecycle API works against the real JVM userspace path.
2. Rollback tests pass for failure boundaries including runtime-start and daemon-control failures.
3. `Vpn` maintains strict delegation to `SessionManager`, `UserspaceVpnRuntime`, and `VpnInterface`.

## Risks and Controls

1. Risk: lifecycle race conditions.  
Control: keep explicit idempotent transitions and deterministic error mapping.
2. Risk: production cutover leaks test doubles into runtime wiring.  
Control: keep provider abstractions explicit and add integration coverage for the real path.
