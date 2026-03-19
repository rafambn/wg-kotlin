# Phase 06: Privileged Daemon Implementation

## Objective

Implement a hardened JVM daemon subprocess that executes only an allowlisted command set.

## Scope

1. Build daemon executable module.
2. Implement command dispatcher from typed protocol commands.
3. Enforce strict command validation and execution model.
4. Keep daemon responsibilities limited to privileged OS control-plane commands.

## Work Breakdown

1. Implement daemon `main` loop reading protocol messages from `stdin`.
2. Build `CommandDispatcher` mapping command types to handlers.
3. Build `PrivilegedCommandRunner`:
- executes binary + argv list only
- never uses shell interpolation
- captures stdout/stderr/exit code
4. Implement per-command validators:
- interface names
- CIDR/IP values
- ports/MTU ranges
5. Implement allowlist command catalog:
- interface create/delete/up/down
- address/route operations
- DNS apply/unset
- WireGuard apply/sync/remove peer
6. Add audit logging for every executed command.
7. Enforce non-goal in code and tests:
- daemon must not implement TUN/UDP packet relay loop
- daemon must not perform runtime encryption/decryption loop

## Deliverables

1. `:new-vpn-daemon-jvm` runnable artifact.
2. Command allowlist and validators with tests.
3. Integration tests against client from phase 05.

## Exit Criteria

1. Daemon accepts only known typed commands.
2. Invalid payloads fail before process execution.
3. No code path allows arbitrary command execution.
4. Logs contain traceable request ID and command outcome.
5. No code path in daemon owns runtime packet forwarding.

## Risks and Controls

1. Risk: privilege escalation bug in handler mapping.
Control: explicit one-command-one-handler mapping with tests.
2. Risk: command behavior differences across OS.
Control: isolate OS-specific command packs and test per platform target.
