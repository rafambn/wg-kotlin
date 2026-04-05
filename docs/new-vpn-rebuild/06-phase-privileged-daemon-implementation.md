# Phase 06: Privileged Daemon Implementation

## Objective

Implement a hardened JVM daemon subprocess that executes only an allowlisted
control-plane command set.

## Implementation Status

Status: Completed (reconciled)  
As Of: 2026-04-08

## Implemented in Code

1. `:new-vpn-daemon-jvm` starts a kRPC server and exposes `DaemonProcessApi` on `/services`.
2. `DaemonProcessApiImpl` implements one explicit handler per typed protocol method and translates validated requests into internal `DaemonOperation`s.
3. `CommonsExecProcessLauncher` executes only `binary + argv` (no shell), capturing stdout/stderr/exit code.
4. `DaemonPayloadValidator` enforces interface names, CIDR/IP values, DNS payloads, and MTU ranges.
5. OS-specific `PlatformOperationPlanner`s are implemented for Linux, macOS, and Windows, selected at runtime from `os.name`.
6. `PlanExecutor` enforces accepted exit codes and maps launcher/start/timeout/command-exit failures into typed daemon failures.
7. Windows DNS remains the only constrained PowerShell command-string exception, isolated inside the Windows planner.
8. Tests cover planner mapping, validation short-circuit behavior, start/timeout/exit failures, and client/daemon round-trip integration.

## Scope

1. Build daemon executable module with real privileged command handlers.
2. Implement typed protocol command handling in daemon service methods.
3. Enforce strict command validation and execution model.
4. Keep daemon responsibilities limited to privileged OS control-plane commands.

## Work Breakdown

1. Build explicit method-to-handler mapping in `DaemonProcessApiImpl`.
2. Build low-level process launcher:
- executes binary + argv list only
- never uses shell interpolation
- captures stdout/stderr/exit code for structured internal diagnostics
3. Introduce typed `DaemonOperation` -> `ExecutionPlan` planning:
- one internal operation per privileged control-plane action
- one execution step model with explicit accepted exit codes
- one OS-specific planner per platform
4. Implement per-command validators:
- interface names
- CIDR/IP values
- DNS payloads
- MTU ranges
5. Implement allowlisted platform planners for:
- interface state changes
- address and route operations
- DNS apply/unset
- interface information reads
6. Enforce non-goals in code and tests:
- daemon must not implement TUN/UDP packet relay loop
- daemon must not perform runtime encryption/decryption loop
- daemon must not own WireGuard peer state

## Deliverables

1. `:new-vpn-daemon-jvm` runnable artifact with real command handlers.
2. Platform planners and validators with tests.
3. Integration tests against client from phase 05.

## Exit Criteria

1. Daemon accepts only known typed commands.
2. Invalid payloads fail before process execution.
3. No code path allows arbitrary command execution.
4. No daemon code path owns runtime packet forwarding or peer state.

## Risks and Controls

1. Risk: privilege escalation bug in handler mapping.  
Control: explicit one-command-one-handler mapping with tests.
2. Risk: command behavior differences across OS.  
Control: isolate OS-specific command packs and test per platform target.
