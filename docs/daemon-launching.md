# Launching an owned daemon

The application supplies the privileged launcher. `DaemonProcess` controls the
resulting daemon and connects through local IPC. There is no TCP listener,
HTTP server, bearer credential, or stdin/stdout bootstrap.

One daemon has one endpoint and serves several VPNs. Each TUN owns a separate
connection to that endpoint. Closing one session leaves other sessions running.

```kotlin
val daemon = DaemonProcess.launch(logPath = applicationLogPath) { arguments ->
    applicationPrivilegedLauncher(arguments)
}
val vpn = Vpn(interfaceName = "utun0")
try {
    vpn.open(configuration)
} finally {
    vpn.stop()
    daemon.close()
}
```

## Launcher contract

The callback receives public arguments: `--endpoint`, `--owner-pid`,
`--owner-start-time`, and optional `--log-path`. The endpoint identifies a launch;
its name is not an authentication credential. The daemon derives the owner's
Unix UID or Windows SID from the operating system.

The application must:

- Start the intended executable with those arguments and root or Administrator
  privileges. Return a `java.lang.Process` identifying the actual daemon, rather
  than a permission dialog, shell, or helper process.
- Supply working `pid`, `isAlive`, timed `waitFor`, and `destroyForcibly` methods.
  Forced termination must already be authorized, without another prompt or a
  response from the daemon. A native launcher may adapt `Process` to its OS
  process handle. Release native handles after exit and retain exit status for
  subsequent queries.
- Redirect or drain standard output/error so process output cannot block it.
  These streams do not carry IPC data. Runtime logs are plain text.
- Clean up partial launches if permission is denied or the callback throws before
  returning a process. After the callback returns, the library owns cleanup.

Permission UI, executable packaging, signing, and installation belong to the
application. No standard-stream handle transfer through elevation is needed.
Calls to launch, open, stop, and kill are blocking and belong off the UI thread.
Serialize lifecycle calls, except that `kill()` can interrupt a graceful stop.
The startup timeout begins after the callback returns; the app owns cancellation
and timeout of its permission dialog. Failed startup forces termination.

## Process authorization

Before binding, the daemon verifies the owner's PID and OS start time supplied
by the library. On Linux, `--owner-start-time` is the raw start time in clock
ticks since boot from field 22 of `/proc/<pid>/stat`, independent of wall-clock
adjustments. On macOS/Windows it is epoch milliseconds from
`ProcessHandle.current().info().startInstant()`. The daemon then tracks that
instance's lifetime:

- Linux uses a pidfd. Linux 5.3 or newer is required. An exited process cannot be
  replaced by another process with the same PID in this authorization check.
- macOS verifies PID, process birth time in microseconds, and live process status.
- Windows retains a process handle with query and synchronize rights.

Each accepted IPC connection must report the authorized owner's PID through the
kernel. Linux uses `SO_PEERCRED`, macOS `LOCAL_PEERPID`, and Windows
`GetNamedPipeClientProcessId`. Authentication happens before reading any config
or allocating a TUN. Connections from other processes, including other processes
of the same user, are closed without a readiness response. Failed peer queries
reject only that connection.

The JVM client also verifies the actual server PID from Unix socket credentials
or `GetNamedPipeServerProcessId` before sending data. It requires the launched
process to remain alive and checks the protocol's readiness identity as well.
The application and its trusted launcher must preserve the supplied owner binding;
a PID declared by an arbitrary connecting client is never accepted as proof.

The daemon uses unmodified `talpid-routing` from the Mullvad repository at
`95bc5f5cdeca6f7624b4a76499a5cc86f9480f80`, pinned in Cargo.toml and Cargo.lock.
There are no bundled sources, local patches or native route adapters. The
upstream dependency requires Rust 1.97.1 or newer. Its public
`RouteManagerHandle` and network monitors run in a dedicated Tokio runtime that
stays alive between requests. After creating a new TUN, the daemon restarts the
shared manager before registering routes so its initial interface snapshot
includes that TUN. Existing sessions' requests are reapplied through upstream.

Endpoint exceptions are installed before VPN routes. Linux resolves each peer's
selected output node through the upstream API before creating the TUN. macOS
and Windows use upstream `DefaultNode`, which follows the monitored non-tunnel
default route rather than preserving a per-destination route snapshot. VPN
prefixes use the tunnel's actual interface name. The daemon does not copy complete
native entries, route metrics, source selectors or all multipath next hops.
The pinned upstream Linux lookup requests a FIB entry and cannot interpret a
route containing only multipath next hops. Such endpoint lookups fail before
TUN creation; single-path IPv4 and IPv6 endpoints are supported.

The daemon tracks ownership of requested routes. Identical requests share one
registration until the final session releases them. An exact prefix requested
through a different node rejects the new session. Different prefixes can coexist.
Resolution, TUN creation and route registration are serialized with cleanup;
DNS commands run outside that lock.

Upstream Linux `remove_routes` does not remove individual routes. Therefore one
shared manager handles the daemon's routes: releasing the final reference to any
route stops the current manager and creates a fresh manager for the remaining
sessions' requests. Adding or removing a routed session can briefly interrupt the remaining
sessions' routes.
The daemon awaits upstream shutdown before creating the next manager, avoiding
old route notifications affecting the new manager's bookkeeping. After the
final route release, it only stops the manager and its runtime before dropping
the TUN.

Routing follows upstream's platform-specific policies. macOS manages scoped
non-tunnel defaults and restores non-tunnel default routes during cleanup.
This does not restore every arbitrary replaced route. Linux uses the main table
and does not request policy-table routing or socket marking, but the upstream
manager still runs its routing-rule cleanup when starting and stopping.
Windows uses the upstream route records and LUID handling. Users must accept
these effects and the differences between systems before requesting routes.
The daemon does not coordinate ownership with external route managers.

Errors returned by the library propagate through the protocol. Requests have a
five-second response deadline; upstream initialization and shutdown follow its
own completion behavior. Some upstream native cleanup failures are only logged,
and the API does not guarantee a deletion acknowledgement or retain every failed
removal for retry. Upstream logs at INFO, WARN and ERROR go to the same file or
stdout as the daemon's logs. Setup rollback uses the same upstream cleanup and
rebuilds the other sessions' requested routes.

## Linux desktop

A graphical polkit agent can provide the elevation dialog:

```kotlin
val daemon = DaemonProcess.launch { arguments ->
    ProcessBuilder(listOf("pkexec", daemonExecutable.toAbsolutePath().toString()) + arguments)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
}
```

`pkexec` executes the daemon in the returned process. The app packages the binary
and any polkit policy. Headless applications supply their own authorization
integration. The daemon still requires `resolvectl` at startup.

Linux and macOS use `/tmp/wg-kotlin-<instance>/ipc.sock`. The daemon creates a new
protected directory and never overwrites an existing endpoint. The socket permits
its owner account to connect; kernel PID checks restrict authorization to the
actual app. The privileged directory prevents other user processes replacing the
socket during setup. JVM Unix IPC uses junixsocket for native peer credentials.

Reference: [pkexec](https://www.freedesktop.org/software/polkit/docs/latest/pkexec.1.html),
[Unix socket credentials](https://man7.org/linux/man-pages/man7/unix.7.html).

## macOS

A distributed app can register its signed privileged helper using `SMAppService`.
The helper must authenticate its requesting app and launch the daemon with the
supplied owner and endpoint arguments. Return a process adapter for the daemon's
actual PID and process controls; no pipe descriptor transfer is required.

Before starting runtime threads, the daemon calls `setreuid(ownerUid, 0)` and
checks the result. Its effective UID remains root, and its real UID allows the
owning user's app to send SIGKILL when the daemon is stuck. Linux uses the same
signal ownership arrangement. Other processes of that UID can also send signals;
this grants them no permission to create TUN sessions. Child DNS tools receive
matching root UIDs and do not inherit the launcher's standard input.

DNS configuration uses SystemConfiguration directly. It checks native error
codes, writes both resolver entries with SCDynamicStoreSetMultiple, and reads
them back to verify the saved values. Cleanup accepts absent keys, verifies
removal, and attempts both entries even if one fails. DNS failures propagate
through startup rollback and session cleanup. The scutil executable is no
longer required.

Reference: [SMAppService](https://developer.apple.com/documentation/servicemanagement/smappservice),
[LOCAL_PEERPID](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/sys/un.h),
[SCDynamicStore](https://developer.apple.com/documentation/systemconfiguration/scdynamicstore-gb2).

## Windows

The daemon extracts its embedded Wintun DLL into a fresh private directory under
the Program Files known folder returned by Windows. Both directory and DLL are
owned by Administrators, with a protected DACL granting access only to
Administrators and SYSTEM. Existing paths are rejected, and filesystems without
persistent ACLs are unsupported. The unelevated account that launched the daemon
does not receive owner rights to change these permissions.

Open handles prevent renaming the directory or writing, deleting, or replacing
the DLL until the daemon and all sessions release it. DLL preparation must
succeed before the listener is bound. Normal cleanup removes the extracted files;
forced process termination can leave protected files behind, which are never
reused by subsequent launches. The elevated Windows tests in
`daemon/tests/unit/wintun_tests.rs` exercise
file replacement, actual DLL loading, cleanup, and access through a linked
unelevated UAC token without creating a TUN or installing a driver.

The native launcher can use `ShellExecuteEx` with `runas` and
`SEE_MASK_NOCLOSEPROCESS`. Retain the actual daemon handle with
`PROCESS_TERMINATE` plus query/wait rights. Adapt it to `Process`; a PowerShell
wrapper's PID is insufficient. Forced termination calls `TerminateProcess` on
that retained daemon handle, then waits for exit.

The endpoint is `\\.\pipe\wg-kotlin-<instance>`. Named pipes reject remote
clients and use an explicit DACL for the app's account, SYSTEM, and administrators.
The object has medium integrity so the unelevated app can write to it. The first
server instance refuses name collisions; a listening instance is kept alive between
connections. PID checks enforce app exclusivity beyond account-level permissions.
The JVM client uses overlapped I/O, with native buffers retained until completion
and pending operations canceled when the connection closes.

NRPT rule comments identify the daemon PID, its creation time from GetProcessTimes,
and the interface. Session cleanup matches the complete comment. Initial cleanup
removes recognized rules only when that process instance has exited. A live
process with a reused PID has a different creation time, so it cannot keep old
rules alive. Unknown comments and owners that cannot be inspected are preserved.

Reference: [named pipe access controls](https://learn.microsoft.com/en-us/windows/win32/ipc/named-pipe-security-and-access-rights),
[client PID](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-getnamedpipeclientprocessid),
[server PID](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-getnamedpipeserverprocessid).

## Logs

The daemon writes plain text to stdout by default. When `--log-path` is supplied,
it appends logs only to that file and creates missing parent directories. Failure
to open the requested file fails startup, with a diagnostic on stdout.

Each line contains the Unix timestamp in milliseconds, daemon PID and message.
Session messages include the interface name; the stop message includes its
elapsed duration. Embedded newlines are escaped so one event occupies one line.

There are no log targets, severity filters, component filters or default log file.
For example:

```text
--log-path /path/to/daemon.log
```

A dedicated thread writes logs through a queue of up to 256 entries. Logging is
best effort: a full queue drops new messages, and an output error stops the log
worker. Shutdown waits at most 500 ms for a flush when the queue has room for the
flush request. Unread stdout or a blocked file does not hold up session cleanup
or process exit. Records still pending at exit can be lost.

## Framed protobuf protocol

Each message is a four-byte unsigned big-endian length followed by protobuf bytes.
Empty frames, frames above 4 MiB, truncated frames, and malformed protobuf are
rejected. Packet payloads remain limited to 65,535 bytes.

After OS peer authorization, the daemon sends `ServerMessage.ready`, field 4,
with protocol version 2 and its PID. Readiness probes can then close without
creating a session. A TUN connection sends `ClientMessage.config`, field 1,
followed by `outgoing_packet`, field 2. It receives `Started`, `IncomingPacket`,
or `Error`. Config must arrive within five seconds, and each server write has a
five-second deadline. `open()` waits for `Started` and reports initialization
failures synchronously. Peer authorization uses OS process identity.

DNS configuration accepts at most 64 servers and 64 search domains. Each domain
entry is limited to 253 bytes before trimming or deduplication, and duplicates
count toward the input limit. Normalization preserves the first occurrence's
order. Windows creates the per-domain NRPT rules in one PowerShell invocation.
Session initialization runs on a blocking worker so OS and DNS operations do not
occupy async executor threads. Its owning task completes cleanup even when the
client disconnects before receiving Started.

OS commands run with closed stdin and share a 20-second deadline for draining
stdout and stderr and waiting for process exit. On timeout or IO failure, the daemon kills
and reaps the subprocess. It retains at most 1 MiB from each output while
continuing to drain the pipes, and includes captured output in error diagnostics.

An independent, authorized control connection can send `ClientMessage.shutdown`,
field 4, after readiness. Shutdown is not accepted inside an existing TUN session.

## Shutdown and migration

Close VPNs before their daemon to allow route/DNS cleanup. `daemon.stop()` or
`close()` sends an authorized IPC shutdown request and waits up to five seconds
before forcing exit. `stop(timeout)` changes the waiting period. `kill()` forces
OS termination without daemon cooperation and waits up to five seconds for exit.
Failure to terminate is reported. Forced termination cannot run network cleanup
and can leave its temporary Unix socket directory behind.

The daemon monitors its owner and begins shutdown when that app exits. Shutdown
stops accepting connections and sends the same stop signal to the IPC handlers
and session loops. Each connection task owns its session from initialization
through teardown. A failed or cancelled socket writer closes the response queue;
the session still waits for its blocking initialization and cleanup workers.
Route/DNS cleanup also runs outside the async executor, and the daemon gives
the connection tasks four seconds to drain, including their session cleanup.
If that deadline expires, it records sessions_drained=false and exits with code
1. The client retains independent force-kill control for a stuck daemon.

Shutdown was exercised on Linux in an isolated container with two real TUNs,
routes and systemd-resolved. The scenarios cover IPC shutdown, SIGTERM, owner
exit, clients that stop reading, route/DNS cleanup failures, and an unresponsive
resolver with a single async worker. Privileged macOS and Windows shutdown have
not been exercised in this validation.

Use the existing `Vpn(interfaceName, engine)` constructor and `open(configuration)`.
`DaemonProcess.launch(...)` registers the first running daemon for JVM VPN sessions.
Further launches do not replace that active registration. Closing or killing the
registered daemon removes it. Constructing a VPN does not require a daemon yet;
opening it requires a running registered daemon. Upgrade client and daemon together: TCP/gRPC, `--host`, `--port`, the
old host/port properties, and the secret-based bootstrap are no longer supported.
The build's IPC integration tests launch an unprivileged Rust fixture and never
configure real TUN, route, or DNS state.
After changing Rust code, add `--rerun-tasks` to the Gradle test command. The test
task records the fixture's executable path and can reuse earlier JUnit results
even when the fixture has been rebuilt.
