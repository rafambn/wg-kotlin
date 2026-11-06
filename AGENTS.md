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
- On Linux, install routes using host route replacement/deletion semantics.
  Do not introduce separate routing tables or restoration of previous routes.
  Callers are expected to understand the routing impact of the routes they request.
- Require `resolvectl` at daemon startup on Linux, including for sessions that
  do not configure DNS.
- Log plain text to the file supplied with `--log-path`, or only to stdout when
  no path is supplied. Do not use Scribe.
