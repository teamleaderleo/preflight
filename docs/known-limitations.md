# Known limitations

Preflight is in release-candidate preparation. The isolated macOS package has completed signed
update, signature-failure recovery, rollback, and app-only removal. A hosted three-platform
candidate, its exact-package benchmark, and the final Windows/Linux package lifecycle still need to
be retained before the first beta.

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

The 15.88-second launch is a warm record from the development machine, not an expected result for
every system. Mod count and content, cache warmth, CPU, translation, storage, memory pressure, and
temperature all affect startup and frame time. The built-in benchmark lets each installation record
its own normal and accelerated launch; the final package will retain the same result before release.

Preparation uses additional disk space. Balanced stores exact lossless LZ4 data and keeps raw data
when compression barely helps; Fastest keeps every upload-ready pixel array raw and can use several
gigabytes more for a small warm-launch difference. The desktop estimates the selected profile's
predicted and conservative requirements before writing, and cleanup remains preview-first.

There is no automatic report or crash upload. **Send run report** exists only in a build configured
for the private intake and still requires review and confirmation for each ZIP. Ordinary builds can
save the same bounded diagnostics ZIP locally.

There is no remote runtime kill switch. If a reviewed adapter is implicated, select **Off /
troubleshooting** and follow the [rollback and incident path](rollback.md). An updated package is
required to change the accepted fingerprint or default plan.
