# MagicLib probes 874 absent optional JSON files at startup

MagicLib 1.5.6's `MagicPaintjobManager.loadPaintjobs()` loads each paintjob row from CSV, then
tries both `data/config/<id>.paintjob` and
`data/config/paintjobs/<id>.paintjob`. Missing files are expected and handled with Kotlin
`runCatching`, but the expected path still runs the restricted resource resolver and constructs a
throwable. On the current 83-mod profile, the manager builds 437 paintjobs while only one loose
`.paintjob` file exists anywhere in the installation—and that file is not referenced by a loaded
row.

An exact startup-breakdown probe separated MagicLib's CSV reads, optional JSON reads, hull
validation, weapon-row loader, and info logging. The first live probe recorded 437 hull validations
and zero returning optional JSON calls: the call timer ends only after a normal return, while every
optional load threw into `runCatching`. The optimized live runs then supplied the direct count:

- 874 optional JSON probes;
- 874 paths proven absent from the current mod's own directory;
- zero present paths, delegations, or runtime failures;
- the same 437 ship paintjobs and 775 weapon paintjobs;
- ACTIVE adapter health, 34 transforms, zero contained failures, normal main-menu exit.

`MagicLibPaintjobLoadPlan` is pinned to the exact MagicLib manager class and archive already used by
the notification-set adapter. It changes only the one reviewed restricted `loadJSON` call site. A
synthetic helper asks `MagicLibPaintjobLoadRuntime` whether the path is known absent; a proven miss
returns `null`, which is the value MagicLib already obtains from its caught failure. Existing or
uncertain paths execute the original `SettingsAPI.loadJSON(path, modId)` invokeinterface unchanged.

The proof is deliberately narrow. The runtime accepts only a relative forward-slash path that
normalizes below `ModSpecAPI.getPath()`, requires that mod root to be a directory, and reuses
Preflight's conservative case-aware directory listing. A malformed path, changed API shape,
inaccessible root, listing ambiguity, or runtime failure delegates. Class or archive drift prevents
the rewrite entirely.

## Timing

The callback is noisy because it runs beside other large callbacks and compilation activity. Recent
unoptimized observations ranged from 711 to 952ms. The immediate baseline/optimized experiments
included 830/670ms and 830/870ms pairs; the optimized run median was about 770ms, versus roughly
820ms across the four nearby baselines. This supports a modest tens-of-milliseconds win, not a
precise or half-second claim. Whole-launch results were 24.43–24.53s for the clean optimized runs.

One attempted run segfaulted in the bundled x86 JVM at 0.9s, before spec loading or mod callbacks,
then waited at HotSpot's interactive crash prompt. The self-terminating harness killed it and a
clean retry completed. That run is excluded from compatibility and timing evidence.

Full `mvn verify` passed with the installed MagicLib archive property, including synthetic
fail-closed/runtime tests and the exact installed-class transform.

Relevant runs:

- `~/.starsector-preflight/runs/magic-paintjob-breakdown-v2-20260805-150809`
- `~/.starsector-preflight/runs/magic-optional-json-v1-retry-20260805-151800`
- `~/.starsector-preflight/runs/magic-optional-json-ab-baseline-20260805-151926`
- `~/.starsector-preflight/runs/magic-optional-json-ab-optimized-20260805-152019`
