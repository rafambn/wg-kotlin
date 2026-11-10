# Project decisions

## Backward compatibility

Backward compatibility is not required for APIs, protocols, or persisted data.
Do not add compatibility layers, legacy paths, migrations, deprecated aliases,
or reserved protocol fields solely to preserve older versions.
This does not override explicit instructions to preserve an API or limit the
scope of a change.

## Intentional daemon policies

These behaviors are intentional project decisions. Preserve that intent when
reviewing or changing the daemon.

- Accept only interface names matching `utun[0-9]+` on every platform, including
  Linux and Windows, to keep cross-platform behavior consistent.
- Use unmodified `talpid-routing` from the pinned upstream Git revision on Linux,
  macOS and Windows. Use its public `RouteManagerHandle`; do not vendor, patch,
  expose private APIs or maintain native route adapters.
- Accept the upstream routing policies and platform differences. In particular,
  macOS manages and restores non-tunnel default routes. This is not a backup of
  every replaced route. Linux uses main-table routes through the public API;
  upstream still performs its native routing-rule cleanup. Users must understand
  and accept these routing effects. The pinned Linux endpoint lookup rejects
  multipath-only FIB entries; preserve the upstream error before TUN creation
  rather than adding native lookup fallbacks.
- Track session route requests in the daemon. Share identical requests until the
  final session releases them and reject an exact prefix requested through a
  different node. Keep one shared upstream manager active while routes are in use.
  Restart it after TUN creation so its interface snapshot includes the new TUN.
  Because upstream Linux `remove_routes` is a no-op, cleanup stops the
  manager and starts a fresh one with the remaining route set, or only stops
  it after the final release. Adding or removing a routed session may briefly interrupt routes
  belonging to other sessions.
- Preserve errors returned by upstream. Cleanup uses upstream's error handling:
  some native failures are logged without being returned or retained for retry.
  Forward dependency logs to the daemon's selected output. Do not promise a
  native deletion acknowledgement or automatic retry that the API does not give.
- Require `resolvectl` at daemon startup on Linux, including for sessions that
  do not configure DNS.
- Log plain text to the file supplied with `--log-path`, or only to stdout when
  no path is supplied. Do not use Scribe.
