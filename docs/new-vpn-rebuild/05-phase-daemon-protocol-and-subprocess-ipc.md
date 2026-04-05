# Phase 05: Daemon Protocol and Local RPC IPC

## Objective

Create the control-plane contract between the JVM process and the privileged
daemon using a local RPC transport, without carrying userspace peer/runtime
state.

## Implementation Status

Status: Completed (reconciled)  
As Of: 2026-04-08

## Scope

1. Build typed control-plane protocol in a shared module.
2. Implement shared kRPC contract and local Ktor WebSocket IPC transport.
3. Establish protocol-level failure taxonomy and timeout behavior.
4. Keep protocol strictly control-plane.

## Implemented in Code

1. Transport: kRPC over Ktor WebSocket route `/services`, default client endpoint `ws://127.0.0.1:<port>/services`.
2. Service contract: `DaemonProcessApi` (`@Rpc`) with one method per control-plane command:
- `ping`
- `interfaceExists`
- `setInterfaceState`
- `applyMtu`
- `applyAddresses`
- `applyRoutes`
- `applyDns`
- `readInterfaceInformation`
3. Response model:
- `CommandResult.Success(data)`
- `CommandResult.Failure(kind, message, detail?)`
4. Error taxonomy:
- daemon emits `VALIDATION_ERROR`, `PROCESS_START_FAILURE`, `PROCESS_TIMEOUT`, `COMMAND_FAILED`, and `INTERNAL_ERROR`
- legacy enum values remain available for compatibility, but removed RPCs are no longer part of the surface
5. Handshake: zero-argument `ping()` returning singleton `PingResponse`.
6. Protocol smoke tests assert control-plane-only naming and serialization.

## Deliverables

1. Stable protocol package and smoke tests.
2. JVM kRPC client that performs handshake and typed round-trips against a mock endpoint.
3. kRPC daemon server scaffold wired to protocol handler.
4. Shared failure taxonomy for daemon responses.

## Exit Criteria

1. Client executes typed command round-trips against mock daemon over local kRPC transport.
2. Malformed payload deserialization fails predictably.
3. Protocol contains only control-plane operations.
4. Handshake behavior is deterministic.

## Risks and Controls

1. Risk: protocol drift between daemon and client.  
Control: contract/smoke tests in protocol, client, and daemon modules.
2. Risk: local endpoint exposure beyond intended scope.  
Control: bind to `127.0.0.1` by default; remote exposure requires explicit opt-in and separate auth controls.
3. Risk: hidden command injection vectors.  
Control: no raw command string field in protocol.

## Implementation Notes

1. `DaemonProcessClient` includes timeout guards and throws `DaemonClientException.Timeout` or `ProtocolViolation` for handshake failures.
2. Both daemon and client currently use JSON serialization, with TODO markers to migrate to Protobuf later.
3. The protocol deliberately excludes peer apply, interface create/delete, and peer-stats RPCs because BORINGTUN peer/runtime state is owned in-process.
