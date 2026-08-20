# Known limitations

Preflight is in release-candidate execution. The live beta gate is owned by
[#652](https://github.com/teamleaderleo/preflight/issues/652) and mirrored in
[Release readiness](release-readiness.md): real-game Windows/Linux exercise, the complete hosted
three-platform candidate, the startup benchmark on exact packaged candidate bytes, and the final
packaged report-intake cancel/retry/delete canary. Broader compatibility and release-administration
work stays in its own owner lanes unless a concrete candidate failure changes that list.

The first beta's macOS and Windows packages will lack paid platform identities. macOS will require
the user's explicit **Open Anyway** approval, and Windows may show SmartScreen's unrecognized-app
warning or refuse execution under stricter managed policy. Each release will publish SHA-256
manifests and use a separate project-key signature for in-app updates.

The current performance evidence comes from Starsector 0.98a-RC8 with a large mod profile on an
Apple-silicon Mac running the game's x86-64 JVM through Rosetta. Native packages build on macOS,
Windows, and Linux. Real Windows and Linux game installations haven't completed the same
startup, campaign, combat, save, reload, and exit scenarios, so equal activation and speed aren't
claimed there yet.

Runtime optimizations are admitted by exact reviewed game, mod, class, archive, and source
identities. A changed or unknown target declines that optimization and retains the original code.
That keeps updates runnable; it can also mean a newly updated installation receives fewer speedups
until its new identity is reviewed. Preflight can't guarantee that the original game or a mod is
free of its own defects.

The 15.25-second launch is the fastest run from the current development comparison, not an expected
result for every system. Mod count and content, cache warmth, CPU, translation, storage, memory
pressure, and temperature all affect startup and frame time. The built-in benchmark lets each
installation record its own normal and accelerated launch; the final package will retain the same
result before release.

Preparation uses additional disk space. Balanced stores exact lossless LZ4 data and keeps raw data
when compression barely helps; Fastest keeps every upload-ready pixel array raw and can use several
gigabytes more for a small warm-launch difference. The desktop estimates the selected profile's
predicted and conservative requirements before writing, and cleanup remains preview-first.

Preflight builds and passes its full test suite on JDK 17, 21, and 26, and everything ships as Java
17 bytecode, so any runtime from 17 up loads it. Paths and profile names containing characters a
system cannot represent are carried into the engine as ASCII, and a Linux session running the
`C`/`POSIX` locale gets a UTF-8 one for the engine. The agent's own jar path is the one thing the
JVM reads for itself and cannot be encoded, so it is staged at a representable path when the system's
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

There is no ambient telemetry. A configured build can send a support ZIP after the user reviews and
confirms it. A separate failed-run setting starts off; when enabled, an exact failed wrapper sends
the same bounded ZIP automatically. Ordinary builds can save that ZIP locally.

There is no remote runtime kill switch. If a reviewed adapter is implicated, select **Off /
troubleshooting** and follow the [rollback and incident path](rollback.md). An updated package is
required to change the accepted fingerprint or default plan.
