# Phase 05: Daemon Protocol and Subprocess IPC

## Objective

Create the communication contract between JVM process and privileged daemon subprocess.

## Scope

1. Build typed request/response protocol in a shared module.
2. Implement stdio message framing and correlation.
3. Establish protocol-level security controls.
4. Keep protocol strictly control-plane (no runtime packet forwarding payloads).

## Protocol Design

1. Transport: daemon launched with `--stdio`, communication via `stdin/stdout`.
2. Framing: newline-delimited JSON messages.
3. Request envelope:
- `id: String`
- `type: CommandType`
- `payload: TypedPayload`
4. Response envelope:
- `id: String`
- `ok: Boolean`
- `code: Int`
- `stdout: String`
- `stderr: String`
- `error: ErrorPayload?`
5. Versioning field required in both directions.

## Work Breakdown

1. Create `:new-vpn-daemon-protocol` with sealed command and payload models.
2. Implement serializer/deserializer and schema tests.
3. Implement JVM client transport with:
- request correlation map
- timeout handling
- cancellation and process death handling
4. Add handshake command (`PING`/`HELLO`) with protocol version check.
5. Define explicit non-goal validation:
- packet data-plane is out of protocol scope
- reject/avoid command types carrying raw packet payload

## Deliverables

1. Stable protocol package and tests.
2. Stdio client that can talk to a mock daemon process.
3. Failure taxonomy for protocol errors.

## Exit Criteria

1. Client can execute typed command round-trips against mock daemon.
2. Unknown command and malformed payload are rejected predictably.
3. Protocol docs include backward compatibility rules.
4. Protocol contains only control-plane operations.

## Risks and Controls

1. Risk: protocol drift between daemon and client.
Control: contract tests run for both modules.
2. Risk: hidden command injection vectors.
Control: no raw command string field in protocol.
