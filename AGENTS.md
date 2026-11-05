# Project decisions

## Intentional daemon policies

These behaviors are intentional project decisions. Preserve that intent when
reviewing or changing the daemon.

- Accept only interface names matching `utun[0-9]+` on every platform, including
  Linux and Windows, to keep cross-platform behavior consistent.
- On Linux, install routes using host route replacement/deletion semantics.
  Do not introduce separate routing tables or restoration of previous routes.
  Callers are expected to understand the routing impact of the routes they request.
- Require `resolvectl` at daemon startup on Linux, including for sessions that
  do not configure DNS.
- Use only Scribe Scrolls for daemon logging, through `new_scroll` and `seal`.
  Do not use `note(...)`. Model all events, including lifecycle events and errors,
  as contextual scrolls.
