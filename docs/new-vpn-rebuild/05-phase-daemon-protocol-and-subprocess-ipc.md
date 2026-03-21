# Phase 05: Daemon Protocol and Local RPC IPC

## Objective

Create the communication contract between JVM process and privileged daemon using a local RPC transport.

## Scope

1. Build typed request/response protocol in a shared module.
2. Implement kRPC contract and Ktor transport for local IPC.
3. Establish protocol-level security controls.
4. Keep protocol strictly control-plane (no runtime packet forwarding payloads).

## Protocol Design

1. Transport: kRPC over Ktor WebSocket route, default endpoint `ws://127.0.0.1:<port>/services`.
2. Service contract: `DaemonControlPlaneService` with one RPC method per command.
3. Request model per command:
- `id: String`
- `version: Int`
- typed command fields (for example `interfaceName`, `dnsServers`, `routes`)
4. Response envelope:
- `id: String`
- `ok: Boolean`
- `code: Int`
- `stdout: String`
- `stderr: String`
- `error: ErrorPayload?`
5. Versioning field required in both directions.
6. Transport abstraction remains open for later migration to UDS or remote-access variants.

## Work Breakdown

1. Create `:new-vpn-daemon-protocol` with typed command request models and response taxonomy.
2. Implement serializer/deserializer tests and command-catalog constraints.
3. Add `@Rpc` protocol interface shared by daemon and client with one method per command.
4. Implement JVM kRPC client transport with:
- timeout handling
- protocol mismatch validation
- remote failure mapping
5. Add handshake command (`PING`/`HELLO`) with protocol version check.
6. Define explicit non-goal validation:
- packet data-plane is out of protocol scope
- reject/avoid command types carrying raw packet payload
7. Add daemon Ktor+kRPC server scaffold exposing the control-plane service.

## Deliverables

1. Stable protocol package and tests.
2. kRPC JVM client that can talk to a mock daemon endpoint.
3. kRPC daemon server scaffold wired to protocol handler.
4. Failure taxonomy for protocol errors.

## Exit Criteria

1. Client can execute typed command round-trips against mock daemon over local kRPC transport.
2. Unknown command and malformed payload are rejected predictably.
3. Protocol docs include backward compatibility rules.
4. Protocol contains only control-plane operations.

## Risks and Controls

1. Risk: protocol drift between daemon and client.
Control: contract tests run for both modules.
2. Risk: local endpoint exposure beyond intended scope.
Control: bind to `127.0.0.1` by default; remote exposure requires explicit opt-in and separate auth controls.
3. Risk: hidden command injection vectors.
Control: no raw command string field in protocol.
