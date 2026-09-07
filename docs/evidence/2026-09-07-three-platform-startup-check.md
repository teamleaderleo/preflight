# Bounded Windows, Linux, and busy-Mac startup observations

The requested follow-up sought another approximately 16-second Windows launch, then checked Linux
and the actively used Mac. This is a finite local observation set, not a randomized campaign.
Every reported startup uses runtime-state v2 `processStartedAt → mainMenuInteractiveAt`.
Neither graphics preload, screenshot capture, nor loading-overlay removal replaces that clock.

The unchanged engine source is `b4536217fbd5ec3592d1d62eff44512c607df7db`; the harness checkout
is its documentation-only successor `13dd9553`. All platforms use the retained normal-build JAR
SHA-256 `9193f8aca5cb44a26a7296bfd96337ca170938d1f2be5ff84b2c1594934ab450`, also used in the
[old/current comparison](2026-09-07-windows-engine-comparison.md). No product changes or rebuilds
were needed. All 83 mods remain enabled; enabled-mods SHA-256 is
`76227ce91333c202271e541774f3e86fd8711c2542d63a81cfd18a4dc0a6997f`.

## Windows

Six runs were declared before observing results, with no excluded game warm-up and no early stop
after finding a fast result. Each reused the existing single-run cohort runner with a 20-second
pause between cohorts: Recommended, prepared cache, sound on, 1024×720 windowed, forced llvmpipe,
14 guest processors, 20 GiB guest RAM, and the existing performance-power policy. No FastRendering
condition was selected. This is not hardware-renderer evidence.

| Cohort (`20260907-…-windows-startup-2x2`) | Interactive menu (s) |
| --- | ---: |
| 131720 | 31.909 |
| 131851 | 21.415 |
| 132012 | 15.432 |
| 132122 | 17.351 |
| 132244 | 27.883 |
| 132404 | 29.852 |

Minimum **15.432 s**, maximum **31.909 s**, median **24.649 s**. The 15.432-second launch is
faster than the historical 16.424-second observation, but this session does not support a claim
of consistently 16-second startup. No new stock comparison or speedup ratio was measured.

All six were accepted, adapter-healthy, and gracefully shut down. Each served 2,049 prepared audio
decodes, retained one game decode, and reported zero audio-cache failures. Every recorded identity
field matched across the six cohorts except `startedAt`. The existing runner's summary also
contains the distinct graphics-preload clock; the table above reads the interactive field.

Two setup failures preceded these six game launches: a name-resolution typo in the temporary
Python orchestration script, then a missing temporary scheduled task that the previous cleanup
had removed. Neither launched a game or produced a startup sample. Both failed receipts are
retained. The corrected setup cloned the existing ready task without changing the original task.

## Linux

Two sound-enabled attempts stalled before publishing an interactive timestamp. Main-thread dumps
at approximately 175 and 126 seconds placed both in native `ALC10.nalcCreateContext`, through
`sound.Object` and `CombatMain`; the game log ended just after refresh-rate selection. The second
attempt followed a restart of the existing PipeWire, pipewire-pulse and WirePlumber user services.
This did not resolve the stall. Both attempts were stopped, retained, and excluded from timing.
Their settings were restored. No subsequent repetitions ran in either failed batch.

This locates the failure in audio-context initialization, but does not establish whether the cause
is the host audio/device state, the game's native library, or Preflight. An optimizations-off
sound-enabled control has not been run in this session. Follow-up is tracked in
[issue #1284](https://github.com/teamleaderleo/preflight/issues/1284).

The subsequent sound-off checks isolate the rest of startup; they do not reproduce or replace the
earlier sound-enabled 18.354-second Linux median. The saved 2048×1280 fullscreen request remains
unchanged; actual fullscreen behavior is a separate existing GUI issue.

Sound-off results: **17.494 / 17.150 / 18.047 s**, median **17.494 s**. All three reached the
interactive marker. Their owned process groups were deliberately stopped after observation;
wrapper exit 143 is recorded, not presented as evidence of ordinary GUI exit behavior. This
cleanup does not invalidate the menu timestamps that were already emitted. Settings readback
confirmed the original resolution, fullscreen request, and sound-off value after completion.
All three adapter reports contain zero contained failures and zero declined transformations.
Audio decodes were zero, as required by the sound-off condition.

## Mac

The user was actively using the Mac, so no quiet-machine performance claim is made. The game
used its existing 1440×932 windowed, sound-on settings and 6 GiB launcher heap. Two sequential
Recommended launches reached the interactive menu in **23.988 and 23.071 s**. This does not show
a gross startup regression in this workload. A recent retained launch
`20260907-023852-851-6af8b76e` recorded 23.555 s on the same clock, but used a different candidate;
it is context, not a controlled baseline.

Both served 2,049 prepared audio decodes, retained one game decode, and reported zero audio-cache
and contained adapter failures. Both retained one declined combat-runtime-integrity transformation
with original code preserved, also present in the earlier retained launch. These startup checks
do not validate combat behavior. The observer stopped each owned process group after the menu;
wrapper exit 143 is retained. Settings readback matched the original values.

## Cleanup and limits

No test game or GUI remains on either machine. The Windows test task was removed, the VM is shut
off, Linux owns the GPU through i915, and GDM is active. All finite benchmark services are inactive.
The original Windows scheduled task was preserved. No reboot or persistent VFIO configuration
change was made. The duplicate Mac engine copy and Python bytecode caches were removed; the
previously retained exact engine remains under Big Red's shared Diagnostics comparison inputs.

These are native game startup observations. They are not browser-layout, native GUI interaction,
packaged release acceptance, combat, or new CI evidence. The outstanding sound-enabled Linux
failure prevents treating this as a clean three-platform timing pass. No product code changed.

## Evidence locations

Big Red retains `benchmark-results/three-platform-startup-20260907/`, including the serial scripts,
setup failures, per-run output, state, and parsed Windows results. Windows raw cohort ZIPs and
host fingerprints remain under `/home/leo/Windows-Share/Diagnostics/` with the table's exact names.
The Mac retains its observation directory and copied compact results under the same repository-
relative evidence root. These private runtime artifacts are ignored rather than shipped.
