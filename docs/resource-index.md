# Resource provider index

Preflight Index records where every logical Starsector resource can be found across the core game and the ordered enabled-mod profile.

## Build

```bash
java -jar preflight.jar index build --game "/path/to/Starsector"
```

The default output is:

```text
~/.starsector-preflight/indexes/PROFILE_FINGERPRINT.spfi
```

Choose an explicit path with `--output`.

## Inspect, query, and validate

```bash
java -jar preflight.jar index inspect resources.spfi
java -jar preflight.jar index query resources.spfi graphics/ships/example.png
java -jar preflight.jar index query resources.spfi graphics/ships/example.png --all
java -jar preflight.jar index validate resources.spfi
```

The default query returns the winning provider. `--all` returns core and mod providers in resolution order.

A missing path is a complete negative lookup result, so a runtime adapter can avoid probing every resource root.

Validation checks every provider against the filesystem and reports:

- Missing resource roots
- Missing or unreadable files
- Non-regular files
- Size changes
- Modification-time changes
- Invalid provider paths

The command exits successfully for a current index and returns a distinct nonzero status for stale provider metadata. Reports are capped while the total invalid-provider count remains exact.

## Format

Version 1 uses a compact binary container:

```text
magic:      SPFI
version:    32-bit integer
length:     32-bit payload length
payload:    roots and sorted resource entries
checksum:   SHA-256 of the payload
```

Each provider contains:

- Resource-root index
- Actual relative path, preserving case
- File size
- Modification timestamp

Each logical lookup key is slash-normalized and case-folded with `Locale.ROOT`. Relative provider paths reject parent traversal. Physical resolution verifies that the result remains inside its declared root. An exact-case provider is answered from memory. For the rare folded-only hit, the index asks the provider's filesystem whether the requested spelling names that same file, preserving case-sensitive Linux behavior without imposing filesystem probes on ordinary exact lookups.

The writer creates a sibling temporary file, flushes it, then performs an atomic replacement when the filesystem supports it. Readers validate magic, version, bounds, checksum, path rules, ordered root indexes, duplicate keys, and trailing data. Collection allocations are capped during parsing so extreme count fields fail through bounded growth instead of immediate giant allocations.

## Current resolution semantics

Version 1 orders providers as:

1. Core resource directory
2. Enabled mods in `enabled_mods.json` order

The last provider wins for ordinary override lookup. Every provider remains available for future merge-aware consumers.

The builder reports case-colliding files within one root because they can behave differently across Windows, default macOS filesystems, and case-sensitive Linux filesystems.

## Runtime integration

The builder, validator, and binary reader live independently from Starsector and Fast Rendering. A future runtime adapter will:

- Validate the profile fingerprint and provider metadata
- Load the index once during startup
- Answer winning-provider and all-provider requests
- Treat absent entries as negative cache hits
- Fall back to the original loader after any validation or compatibility failure

The v1 CLI makes the format and invalidation behavior testable before that integration is introduced.
