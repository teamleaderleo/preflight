# Prepared-pixel main-menu comparison pilot failure

Date: 2026-07-23

## Result

The first automatic two-run main-menu comparison did not produce a valid A/B result.

Retained archive:

```text
prepared-pixel-main-menu-comparison-pilot-20260723-100906.tar.gz
sha256: 2530de69d2251319422b3224a0d8430e5537f77a667fd69a9a726996785fdd08
```

Exact identity:

```text
repositoryHead: ff0e7081aac9df8dc18d2d2d5c72770ce233a5d8
jarSha256: 11d901d180ec02f07d3c586d8a5d9aae323ecb50ceae7ac2d00cbb4d021c5186
archiveSha256: 10d89e113f6d1627cc7bc90b692e8a7f450fdd820c5a4ac5edaecd6710afe708
classSha256: d8fcb4cb90d457fc3075e711b6293940774dcf990ea66a7584c231bd96898b50
order: prepared,compatibility
samplesPerMode: 1
benchmarkAccepted: false
```

Do not use this archive for a performance claim, log-equivalence conclusion, or default-enablement decision.

## Prepared half

The prepared half ran first and reached the main menu normally:

```text
outcome: COMPLETED
exitCode: 0
launcherExitCode: 0
fatalDetected: false
launcherReadyMs: 9788.513
gameLogStartToMainMenuMs: 85060.434
operatorAccepted: true
automatedAccepted: true
```

Prepared-pixel telemetry remained clean:

```text
prepared hits: 4956
coherent-direct NPOT hits: 4413
fallbacks: 0
internal errors: 0
active direct bytes at shutdown: 0
active/pending buffers at shutdown: 0
```

The full log classification for this half was:

```text
normalMapBufferFailures: 25
graphicsLibErrors: 25
graphicsLibWarnings: 45
musicSourceWarnings: 1
shaderCreationErrors: 0
```

Those counts have no compatibility counterpart because the second half failed before the main menu.

## Compatibility half

The compatibility half ran second. Its launcher reached the automatic marker, but Starsector terminated while loading the core mission `afistfulofcredits`:

```text
outcome: FATAL_LOG_EVIDENCE
exitCode: 6
launcherExitCode: 0
textureAdapterMode: COMPATIBILITY
transformationsApplied: 0
```

Fatal evidence:

```text
Error loading [data/missions/afistfulofcredits/descriptor.json] resource, not found in [...]
```

The corresponding mission-list line was:

```text
Loading mission [afistfulofcredits] ... (source: [null/data/missions/mission_list.csv])
```

The same `source: [null/data/missions/mission_list.csv]` representation appeared during the successful prepared half, where the descriptor loaded. It is therefore not sufficient to explain the failure by itself.

The dialog's long bracketed list is Starsector's resource-search path. The first listed mod directories, including AI Tweaks and Arma Armatura, are not evidence that those mods caused the missing core descriptor.

The archive showed no meaningful working-directory or classpath difference between the two halves beyond the intended texture mode and coherent-direct property. Exact causality for the resource-resolution failure remains unproven.

## Invalid profile comparison

The comparison profile changed between halves.

Initial preparation and the prepared run used:

```text
profileFingerprint: ccbc7f1aebf89c7ed8f21c886ac6a869b496fa3edd36b8943d1269cacd1a8ebe
```

The compatibility run started with:

```text
profileFingerprint: 3c1fc13ee4b47a93d36122ee2804070dbacf43523a3d38df5cc531e35e4513fe
```

The only census delta was `shaderLib` / `zz GraphicsLib-1.12.1`:

```text
files: +2
imageFiles: +2
bytes: +136
imageBytes: +136
```

The prepared log recorded two runtime-generated normal maps matching that delta:

```text
cache/sotf_wisp_lesser___SHIP_normal.png
cache/tpc_weaver___TURRET0_normal.png
```

Therefore the two halves did not run against an identical enabled-profile fingerprint. The pair is invalid independently of the compatibility fatal.

## Launcher-settling observation

The prepared launcher emitted its final reviewed texture marker and then remained open for roughly fourteen seconds before game log activity began.

The compatibility half began game loading roughly three to four seconds after its final launcher marker. The merged detector used only a 1.5-second launcher quiet confirmation. This does not prove that early clicking caused the resource-resolution failure, but it is an order-sensitive difference that the replacement harness must remove.

## Later vanilla fast-relaunch reproduction

On 2026-07-24 the same class of `Error loading [...] resource, not found in [...]` fatal was reproduced manually with the plain Starsector launcher, the full mod set, and no Preflight, after quitting during loading and relaunching within a couple of seconds. The printed resource-search path was complete and the reported missing file was still present and byte-identical on disk; a later reproduction named a mod mission rather than the core mission above.

That reproduction materially weakens an attribution of this failure class to Preflight or the texture adapter, but it does not by itself establish a single universal cause. It also means a disk-state guard based only on file presence and hashing cannot detect this particular failure when the runtime cannot resolve a file that remains present and unchanged.

## Required repair

Before one replacement comparison is authorized, the runner must:

1. use a fixed six-second launcher quiet/safety confirmation while retaining the marker timestamp as the measured endpoint;
2. capture the preparation profile fingerprint;
3. rerun deep preparation before and after each half;
4. abort and retain exact mod deltas if the enabled profile changes;
5. verify the core mission list and `afistfulofcredits` descriptor remain present and byte-identical;
6. retain compatibility as the rollback and preserve the single-pair/preliminary boundary.

A replacement run may proceed only after that repair is reviewed, validated, and merged. Do not disable or blame individual mods based on this archive, and do not rerun the old harness.
