# GraphicsLib texture-data keys are reused inside one game session

Date: 2026-08-27

Install: Starsector 0.98a-RC8, GraphicsLib 1.12.1, current heavily modded profile, macOS on
Apple M5, Java 17 under Rosetta, Preflight fast preset

## Finding

GraphicsLib rebuilt the same private texture-data lookup strings throughout combat. Two earlier
Preflight combat recordings sampled 60.0 and 22.1 MiB of weighted allocation directly inside
`TextureData.getTextureDataKey`. JFR allocation weights are statistical estimates, not an exact
allocation census, so those values establish a repeat hot path rather than an allocation rate.

The retained candidate wraps that private pure key builder. A bounded session cache returns the
exact string previously generated for the same base key, object-type enum ordinal, and animation
frame. The original method still generates every miss. Invalid shapes, null values, frames outside
the reviewed 0--1,023 range, a 65,536-base-key ceiling, or a 262,144-value ceiling retain original
behavior. Nothing persists across launches.

The live 1,040-DP combat run made 579,711 calls: 567,477 hits, 12,234 misses/records, and zero
bypasses, for a 97.8896 percent hit rate. It retained 6,235 base keys and 12,234 values. The attached
JFR contained zero allocation samples whose stack included `getTextureDataKey`, compared with 46
and 13 samples in the two earlier captures.

This is an allocation result, not an FPS percentage. The computer was already hot, and the one
retained 30.236-second combat window averaged 22.72 FPS with an 8.26 FPS 1-percent low. Its
249.36ms/s stutter burden and 84.28-percent repeated-cluster exposure are useful evidence that much
larger combat work remains; they do not show that the cache made frame pacing worse or better.

## Startup and gameplay stay separate

The same launch reached the interactive menu in 31.228 seconds and included a 6,027.638ms
pre-interactive presentation gap. That corroborates the separately retained startup-hitch finding.
The clean combat window began only after Continue, fleet construction, deployment, autopilot,
speed, pause-state, camera, and settling steps. Startup frames therefore cannot enter the combat
FPS or low-percentile population.

The measurement lanes remain distinct:

- time to interactive and the worst pre-interactive presentation gap describe startup;
- paused and unpaused campaign windows describe campaign frame pacing;
- clean simulation windows describe combat frame pacing;
- allocation and CPU profiles explain candidate mechanisms but are not FPS claims by themselves.

## Deterministic combat route

The run also closed two automation gaps. macOS activation now foregrounds and then verifies the
same recorded PID through ApplicationServices; the retained run used the standalone harness's
bounded system-Python compatibility call and never selected a Dock item or launched by application
name. Combat zoom uses
12 bounded negative wheel events at Starsector's exact input-generation boundary, then verifies the
public viewport changed. The retained run stayed on PID 5599, reported `frontmost=true` at both
activation checks, and proved `viewMult` 1.250 to 3.989 and visible width 1,800 to 5,744 before the
measurement window.

Discarded learning attempts remain process notes, not evidence: CoreGraphics wheel delivery did
not change the camera; positive game-wheel events zoomed in and were rejected; a non-frontmost run
had its inactive frames rejected; and clicking a stale Dock item launched a second process, which
was terminated and the approach removed. The retained route completed the inner scenario and outer
Preflight launch with exit code zero.

## Exact gates and safety

The cache is the final transform in GraphicsLib's existing exact whole-class pipeline:

- installed `TextureData` class SHA-256: `6a4302bcacd2dd90f6637c815d1443ddfdb3d28ff59095d48c875358de4e8594`;
- GraphicsLib archive SHA-256: `832064013fe853731941e547842884ba121fb8b20eff08d24137f7a2c916903a`;
- reviewed pre-cache transformed class: `f2f4c45d9d19f1dbc51821779ee2efca1817c96ac680d67e442cdb5180ef15ff`;
- final transformed class: `4a461c2f1cb75c82da55d8f99e636a955575f6babb94237032295566198fe02c`.

A changed class, method descriptor, access shape, or final transformed hash rejects the replacement
and retains original bytecode. The cache adds no fields to game or mod objects, changes no files,
and touches no campaign, combat, or save state.

## Verification and claim limit

Focused unit and installed-archive integration checks cover the exact transform, unchanged-input
rejection, nonanimated and animated keys, invalid values, action protocol, exact combat input seam,
and macOS PID activation. A separate Java 17 probe also foregrounded an already-frontmost exact PID
through the packaged JNA path. Full Java 17 `mvn verify` passed 2,135 tests with zero failures or errors
and five environment-gated skips. The source-boundary and claim-provenance checks also passed. The
retained machine-readable measurements and artifact hashes are in
`data/2026-08-27-graphicslib-texture-key-cache.json`.

The supported claim is narrow: on this exact profile, a bounded session cache served 97.8896 percent
of observed GraphicsLib texture-key requests and removed this method from JFR allocation sampling.
One thermally noisy run does not support a universal FPS, percentile, or startup-time percentage.
