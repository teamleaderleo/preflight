# AshLib repository inputs are reused only during repository population

Date: 2026-08-05

Profile: 83 enabled mods, AshLib 2.2.3, Starsector 0.98a-RC8, macOS on Apple M5,
bundled x86-64 Zulu 17 under Rosetta, `--fast`

## Result

AshLib's `AshMisc.getVaraint` asks Starsector for all 8,622 variant ids and scans them from the
beginning for every modular or station hull. The reviewed startup route called it 547 times. An
exact callback-scoped index reduced that measured call site from **160ms to 19ms** while preserving
the mod's first-match and fallback ordering.

The retained final unattended run is
`~/.starsector-preflight/runs/ash-ship-json-scope-20260805-145915`. It reached the main menu in 24.66s,
shut down automatically with wrapper exit 0, and reported ACTIVE adapter health: 34 transforms,
zero declines, zero unavailable plans, and zero contained failures.

Live telemetry reported:

- one successful index build over 8,622 ordered live variant ids;
- 547 indexed lookups;
- 362 first eligible exact-hull matches;
- 3 ordered case-insensitive `<hullId>_Hull` fallback matches;
- 182 null results; and
- zero build failures.

The first indexed run measured AshLib's complete application callback at 502ms. The immediately
preceding no-record probe without the index measured the exact lookup at about 160ms and the
callback at about 680ms. The 140ms exact call-site reduction is the attribution claim; the whole callback difference is
consistent corroboration, not a whole-launch claim.

## Private read-only hull JSON

The same pinned `ShipRenderInfo` class calls its private `getShipJson(String)` four times from
different read-only construction paths. Across the callback, those paths repeatedly ask for the
same hull. Exact bytecode contains no `JSONObject` mutation call anywhere in the class, the private
helper's result never escapes the class's construction routines, and the surrounding callback
already defines a narrow lifetime.

The callback state therefore also retains each successful non-null helper result by hull id. The
final live gate reported 17,051 hits, 6,041 misses, and 6,039 captures. Actual `loadJSON` calls in
the class fell **27,294 -> 9,861** and their exact measured time fell **220ms -> 155ms**. The
complete AshLib callback fell **502ms -> 430ms** in the adjacent runs, consistent with the 65ms
call-site reduction. The final adapter reported all three AshLib owners installed with zero build
failure, decline, unavailable plan, or contained failure.

## Boundary and failure behavior

The index and private JSON map exist in a `ThreadLocal` only during AshLib's exact
`ShipRenderInfoRepo.populateRenderInfoRepo()` method. Outside that callback,
`AshMisc.getVaraint()` and `ShipRenderInfo.getShipJson()` execute their original bytecode. The index is built from the same live ordered
`SettingsAPI.getAllVariantIds()` list and applies the same two decisions as the mod:

1. the first variant with a non-null file path whose hull id exactly matches; then
2. the first ordered id equal ignoring case to `<hullId>_Hull`.

Any null or malformed API object, unexpected collection member, reflective linkage failure, or
getter failure discards the partial index. A synthetic catch-all also clears the scope before
rethrowing any callback failure unchanged. The transformed lookup then sees no active scope and
runs the untouched AshLib scan. The repository class, lookup class, methods, complete class hashes,
owning `ashlib.jar` hash, source kind, and mod classloader are all pinned.

Unit coverage proves duplicate first-match ordering, ignored null file paths, case-insensitive
fallback order, null results, malformed-registry fallback, private JSON hit/miss isolation,
transform shape, double-transform rejection, and source binding. The installed AshLib archive transforms all three exact classes, and the
full Maven reactor passes.
