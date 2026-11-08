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
- On Linux, macOS and Windows, remove the session's routes during cleanup without
  restoring routes that it replaced, including routes from an earlier session.
  This is intentional. Users must understand and accept the routing impact of
  the routes they request, including that replaced routes will not return when
  a session ends or its setup fails. Do not add route backups or restoration.
- On Linux, retain host route replacement/deletion semantics and do not introduce
  separate routing tables.
- Require `resolvectl` at daemon startup on Linux, including for sessions that
  do not configure DNS.
- Log plain text to the file supplied with `--log-path`, or only to stdout when
  no path is supplied. Do not use Scribe.
