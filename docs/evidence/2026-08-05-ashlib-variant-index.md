# AshLib variant lookup is indexed only during repository population

Date: 2026-08-05

Profile: 83 enabled mods, AshLib 2.2.3, Starsector 0.98a-RC8, macOS on Apple M5,
bundled x86-64 Zulu 17 under Rosetta, `--fast`

## Result

AshLib's `AshMisc.getVaraint` asks Starsector for all 8,622 variant ids and scans them from the
beginning for every modular or station hull. The reviewed startup route called it 547 times. An
exact callback-scoped index reduced that measured call site from **160ms to 19ms** while preserving
the mod's first-match and fallback ordering.

The retained final unattended run is
`~/.starsector-preflight/runs/ash-variant-index-final-20260805-145452`. It reached the main menu in 24.88s,
shut down automatically with wrapper exit 0, and reported ACTIVE adapter health: 34 transforms,
zero declines, zero unavailable plans, and zero contained failures.

Live telemetry reported:

- one successful index build over 8,622 ordered live variant ids;
- 547 indexed lookups;
- 362 first eligible exact-hull matches;
- 3 ordered case-insensitive `<hullId>_Hull` fallback matches;
- 182 null results; and
- zero build failures.

AshLib's complete application callback measured 502ms. The immediately preceding no-record probe
without this change measured the exact lookup at about 160ms and the callback at about 680ms. The
141ms exact call-site reduction is the attribution claim; the whole callback difference is
consistent corroboration, not a whole-launch claim.

## Boundary and failure behavior

The index exists in a `ThreadLocal` only between entry and normal return of AshLib's exact
`ShipRenderInfoRepo.populateRenderInfoRepo()` method. Outside that callback,
`AshMisc.getVaraint()` executes its original bytecode. The index is built from the same live ordered
`SettingsAPI.getAllVariantIds()` list and applies the same two decisions as the mod:

1. the first variant with a non-null file path whose hull id exactly matches; then
2. the first ordered id equal ignoring case to `<hullId>_Hull`.

Any null or malformed API object, unexpected collection member, reflective linkage failure, or
getter failure discards the partial index. A synthetic catch-all also clears the scope before
rethrowing any callback failure unchanged. The transformed lookup then sees no active scope and
runs the untouched AshLib scan. The repository class, lookup class, methods, complete class hashes,
owning `ashlib.jar` hash, source kind, and mod classloader are all pinned.

Unit coverage proves duplicate first-match ordering, ignored null file paths, case-insensitive
fallback order, null results, malformed-registry fallback, transform shape, double-transform
rejection, and source binding. The installed AshLib archive transforms both exact classes, and the
full Maven reactor passes.
