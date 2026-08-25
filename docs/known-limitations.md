# Known limitations

Preflight is in operational release-candidate execution. Source and rendered-UI convergence are
complete, and the private signing rehearsals are done. The remaining beta work is selecting one
release source, producing its immutable tagged package generation, exercising that exact package on
native Windows and x86-64 Linux, and collecting the package-bound benchmark/lifecycle/report evidence
tracked in [#652](https://github.com/teamleaderleo/preflight/issues/652) and mirrored in [Release
readiness](release-readiness.md). Broader engineering stays parked unless a concrete candidate
failure or explicit maintainer decision promotes it.

The first beta's macOS and Windows packages will lack paid platform identities. macOS will require
the user's explicit **Open Anyway** approval, and Windows may show SmartScreen's unrecognized-app
warning or refuse execution under stricter managed policy. Each release will publish SHA-256
manifests and use a separate project-key signature for in-app updates.

The current performance evidence comes from Starsector 0.98a-RC8 with a large mod profile on an
Apple-silicon Mac running the game's x86-64 JVM through Rosetta. Native packages build on macOS,
Windows, and Linux. Real Windows and Linux game installations haven't completed the same startup,
campaign, combat, save, reload, and exit scenarios, so equal activation and speed aren't claimed
there yet. The first public beta GitHub release and downloadable packages do not go live until the
retained candidate completes the required native Windows and native x86-64 Linux real-game exercises.

Runtime optimizations are admitted only for game/mod code Preflight recognizes. A changed or
unknown target declines that optimization and uses the original code instead. That keeps updates
runnable; it can also mean a newly updated installation receives fewer speedups until its new code is
reviewed. Preflight can't guarantee that the original game or a mod is free of its own defects.

The 15.25-second launch is the fastest run from the current development comparison, not an expected
result for every system. Mod count and content, cache warmth, CPU, translation, storage, memory
pressure, and temperature all affect startup and frame time. The built-in benchmark lets each
installation record both conditions through Preflight, with reviewed optimizations off and on. The
accepted package will get its own
retained benchmark result before the first public beta release goes live.

Preparation uses additional disk space. Balanced stores lossless LZ4 data and keeps raw data when
compression barely helps; Fastest keeps every upload-ready pixel array raw and can use several
gigabytes more for a small warm-launch difference. The desktop estimates the selected profile's
temporary build and finished retained sizes before writing, keeps a free-space reserve, and leaves
raw codec ceilings internal. Cleanup remains preview-first.

Preflight builds and passes its full test suite on JDK 17, 21, and 26, and everything ships as Java
17 bytecode, so any runtime from 17 up loads it. Paths and profile names containing characters a
system cannot represent are carried into the engine as ASCII, and a Linux session running the
`C`/`POSIX` locale gets a UTF-8 one for the engine. The agent's own jar path is the one thing the JVM
reads for itself and cannot be encoded, so it is staged at a representable path when the system's
encoding would lose it. `prepare audio` spawns a child JVM the same way, and it now carries the
installation's jars as encoded arguments instead of on a class path the launcher would convert. The
audio verification commands still take the older route; they are maintainer tools rather than
anything a player runs. Code pages cover their own language, so all of this affects mixed scripts
rather than ordinary localized names.

Adapters reproduce the game's own locale sensitivity rather than correcting it. Starsector's
case-insensitive campaign entity fallback folds ids with the player's locale, so under Turkish and
Azeri it already fails to match ids containing an `I`; the index in front of it answers the same way
instead of resolving what the game would decline. The detail for both is in
[Java runtime support](java-runtime-support.md).

There are no accounts or usage telemetry. A configured build can send a support ZIP after the user
reviews and confirms it. The first beta does not send failed-run reports automatically. Ordinary
builds can save that ZIP locally.

There is no remote runtime kill switch. If a reviewed adapter is implicated, select **Off /
troubleshooting** and follow the [rollback and incident path](rollback.md). An updated package is
required to change the accepted fingerprint or default plan.
