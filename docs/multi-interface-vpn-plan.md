# Multi-Interface VPN Plan

## Goal

Allow one `Vpn` object to manage multiple live WireGuard interfaces.

Target behavior:

- `Vpn` constructor no longer requires `VpnConfiguration`.
- `open(config)` starts one independent VPN session.
- Each session has stable ID.
- `close(id)` stops only one session.
- `close()` stops all sessions.
- `reconfigure(config)` is removed from public API.
- Runtime information is exposed as continuously updated state per session.

## Current State

`wg-kotlin/src/commonMain/kotlin/com/rafambn/wgkotlin/Vpn.kt` is single-session:

- One TUN pipe pair.
- One network pipe pair.
- One `CryptoSessionManagerImpl`.
- One `SocketManagerImpl`.
- One `InterfaceManager`.
- One mutable `vpnConfiguration`.

`open()` restarts that single stack. `reconfigure()` only permits same interface name. `close()` stops whole stack.

The daemon is closer to multi-session already:

- `DaemonImpl` tracks `activeSessions` by `interfaceName`.
- `startSession(config, outgoingPackets)` rejects duplicate interface names only.
- Session shutdown is implicit: cancelling/completing packet Flow closes TUN handle.

Daemon does not currently expose explicit `close(id)` RPC.

## Proposed Public API

Prefer session object plus ID over returning raw `Flow` from `open`.

```kotlin
class Vpn(
    engine: Engine = Engine.BORINGTUN,
) : AutoCloseable {
    fun open(config: VpnConfiguration): VpnSession

    fun information(id: VpnSessionId): StateFlow<VpnInformation>

    fun close(id: VpnSessionId)

    override fun close()
}

@JvmInline
value class VpnSessionId(val value: String)

data class VpnSession(
    val id: VpnSessionId,
    val information: StateFlow<VpnInformation>,
) : AutoCloseable
```

Rationale:

- Session ID gives clear target for `close(id)`.
- `StateFlow` makes status observable without making Flow cancellation semantics ambiguous.
- `VpnSession.close()` can delegate to owner for ergonomic one-session cleanup.

Avoid making `open(config): Flow<VpnInformation>` the primary API unless cancellation is explicitly defined as closing the VPN session. Raw Flow return makes it easy to leak live sessions if caller stops collecting without closing.

## Information Model

Add a common model that is broader than current `VpnInterfaceInformation?`.

```kotlin
data class VpnInformation(
    val id: VpnSessionId,
    val interfaceName: String,
    val status: VpnStatus,
    val interfaceInformation: VpnInterfaceInformation?,
    val failure: Throwable? = null,
)

sealed class VpnStatus {
    data object Starting : VpnStatus()
    data object Running : VpnStatus()
    data object Stopping : VpnStatus()
    data object Stopped : VpnStatus()
    data class Failed(val message: String) : VpnStatus()
}
```

Keep `VpnInterfaceInformation` for low-level interface snapshot. `VpnInformation` becomes session status envelope.

## Internal Design

Create one internal runtime stack per opened config:

```text
Vpn
  sessions: MutableMap<VpnSessionId, VpnRuntimeSession>

VpnRuntimeSession
  config: VpnConfiguration
  tunPipePair: DuplexChannelPipe<ByteArray>
  networkPipePair: DuplexChannelPipe<UdpDatagram>
  cryptoSessionManager: CryptoSessionManagerImpl
  socketManager: SocketManagerImpl
  interfaceManager: InterfaceManager
  information: MutableStateFlow<VpnInformation>
```

`Vpn.open(config)` flow:

1. Validate config.
2. Reject duplicate `interfaceName` already open in this `Vpn`.
3. Reject duplicate non-zero `listenPort` already open in this `Vpn`.
4. Create `VpnSessionId`.
5. Create new runtime stack.
6. Start crypto, socket, interface in same order as current `Vpn.open()`.
7. Register session only after successful startup, or register as `Starting` and guarantee cleanup on failure.
8. Return `VpnSession`.

`Vpn.close(id)` flow:

1. Remove session from map.
2. Mark `Stopping`.
3. Stop interface, socket, crypto.
4. Mark `Stopped`.
5. Preserve current cleanup behavior: attempt all stops and throw first error.

`Vpn.close()` flow:

1. Snapshot all IDs.
2. Close each session.
3. Attempt all cleanup.
4. Throw first failure after all sessions attempted.

## Daemon Compatibility

No daemon protocol change is required for first implementation.

Current daemon semantics support many sessions if:

- Each has unique `interfaceName`.
- Client maintains one packet Flow per session.
- Closing local session cancels that Flow.

Keep daemon `startSession(config, outgoingPackets): Flow<ByteArray>` as-is.

Add tests proving:

- Two simultaneous `startSession` calls with different names can run.
- Second `startSession` with same name fails.
- Cancelling one session does not close another.

Future daemon-owned IDs are optional. Use them only if there is a requirement to stop orphaned sessions from another client/process.

## Socket Constraints

Current `SocketManagerImpl` binds UDP sockets per runtime session.

Initial rule:

- Allow same `listenPort` only when it is `null` and resolves to default? No.
- Treat `null` as `DEFAULT_PORT`.
- Reject duplicate effective listen ports inside one `Vpn`.
- Allow `listenPort = 0` for OS-assigned ephemeral ports.

Reason: two sessions cannot bind same UDP port with current implementation.

Future improvement:

- Shared UDP socket per listen port.
- Demultiplex incoming packets by peer endpoint.
- Route outgoing packets by session.

Do this later. First version should use one socket manager per session and reject conflicts.

## Routing And DNS Constraints

Multiple live interfaces can conflict at OS level.

First version should not attempt global route conflict solving. It should:

- Allow OS/platform adapter to fail on conflicting routes.
- Surface failure in `VpnInformation`.
- Document that callers must avoid overlapping default routes unless platform behavior is understood.

Optional later validation:

- Reject duplicate exact routes inside one `Vpn`.
- Warn or reject overlapping routes across sessions.
- Detect multiple `0.0.0.0/0` or `::/0` routes.

## Interface Name Validation

Current common validation requires `utun[0-9]+`, which is macOS-specific.

Change validation before multi-interface public release:

- Common validation: non-blank and bounded length.
- Platform/daemon validation: existing daemon regex or platform-specific rules.
- Tests must cover Linux-style names like `wg0` and macOS names like `utun7`.

This matters because multi-interface support should work across daemon platforms, not only macOS-style names.

## Reconfigure Removal

Remove public `reconfigure(config)` after session API lands.

Migration path:

```kotlin
val vpn = Vpn()
val session = vpn.open(config)

session.close()
val replacement = vpn.open(newConfig)
```

If low-disruption config updates are needed later, add:

```kotlin
fun replace(id: VpnSessionId, config: VpnConfiguration): VpnSession
```

That API can explicitly close old session and open new session atomically from caller perspective.

## Implementation Phases

### Phase 1: Internal Session Extraction

- Extract current `Vpn` startup/stop logic into `VpnRuntimeSession`.
- Preserve existing public API temporarily.
- Current `Vpn(configuration)` owns exactly one `VpnRuntimeSession`.
- Tests should remain behavior-compatible.

Validation:

- `./gradlew :wg-kotlin:check`

### Phase 2: Add Session ID And Multi-Session Map

- Add `VpnSessionId`.
- Add `VpnSession`.
- Add `VpnInformation` and `VpnStatus`.
- Add new constructor `Vpn(engine)`.
- Add `open(config): VpnSession`.
- Add `close(id)`.
- Keep old constructor/API as deprecated wrappers if binary/source migration matters.

Validation:

- Unit test two sessions with distinct interface names and ports.
- Unit test duplicate interface rejection.
- Unit test duplicate listen port rejection.
- Unit test closing one session leaves other running.

### Phase 3: Daemon Concurrent Session Tests

- Add daemon test for concurrent different-interface sessions.
- Add daemon test for duplicate interface rejection.
- Add daemon test for cancellation cleanup isolation.

No protocol change expected.

Validation:

- `./gradlew :wg-kotlin-daemon-jvm:test`
- `./gradlew :wg-kotlin-daemon-protocol:test`

### Phase 4: Validation Cleanup

- Replace macOS-only common interface regex.
- Keep daemon payload validation strict and platform-safe.
- Add tests for `wg0`, `wg1`, `utun7`.

Validation:

- `./gradlew :wg-kotlin:check`
- `./gradlew :wg-kotlin-daemon-jvm:test`

### Phase 5: Remove Or Deprecate Reconfigure

- Deprecate `reconfigure(config)` first if compatibility matters.
- Remove after next breaking version.
- Update tests and docs.

Validation:

- `./gradlew build`
- `./gradlew ciPhase01`

## Test Matrix

Core module:

- `Vpn()` starts two independent sessions.
- `close(first.id)` does not stop second.
- `close()` stops all.
- Duplicate interface name fails before partial startup leaks resources.
- Duplicate effective listen port fails before partial startup leaks resources.
- Failed interface startup cleans crypto/socket/interface.
- `information(id)` emits `Starting`, `Running`, `Stopped` or `Failed`.

JVM interface module:

- Two `JvmInterfaceManager` instances can open different `InterfaceCommandExecutor` sessions.
- One daemon-backed bridge failure only clears one manager.

Daemon:

- Concurrent different interface names work.
- Duplicate interface name fails.
- One Flow cancellation closes one handle only.
- Oversized packets still fail per session.

Platform adapters:

- Linux `wg0`/`wg1` accepted.
- macOS `utun7` accepted.
- Windows actual opened name still used for cleanup.

## Open Decisions

- ID format: random UUID string vs deterministic interface name.
- Old API compatibility: hard break now or deprecate first.
- Flow update cadence: event-driven only vs periodic polling for peer stats.
- Whether `listenPort = 0` should expose actual bound port in `VpnInformation`.
- Whether route overlap should fail fast in library or be delegated to OS.

## Recommended First Cut

Do pragmatic first version:

- One runtime stack per session.
- Local session IDs only.
- Existing daemon protocol unchanged.
- Reject duplicate interface names and effective listen ports inside one `Vpn`.
- Use `StateFlow<VpnInformation>` for status.
- Deprecate old constructor/open/reconfigure first, remove later.

