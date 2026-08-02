# The single-chunk startup profile works live — and confirms the sampling hole

**Date:** 2026-08-02
**Install:** Starsector 0.98a-RC8, Zulu 17.0.10 x86_64 under Rosetta, current 77-mod profile
**Session:** `~/.starsector-preflight/benchmarks/20260802-090059`
**Status:** main-menu run accepted; one-chunk policy and finalized wrapper receipt verified live

## Run

The unattended benchmark condition invoked:

```text
preflight run --adapter --texture-auto --profile --single-chunk-recording
```

after a 60-second cooldown. The automatic game-log detector anchored startup on Starsector's game
start line and stopped it after GraphicsLib's post-preload marker. The harness reported:

```text
Main menu ready in 64.4s
prepared textures served: 21653 hits, 2530022691 bytes, 3 fallbacks
```

This is one diagnostic sample, not a performance comparison. There is no baseline pair and the
profile condition intentionally carries sampling overhead.

## The recording policy held

The child JVM received both halves of the policy:

```text
record=sample,flush=0
-XX:FlightRecorderOptions=memorysize=256m,maxchunksize=256m
```

The JDK 21 `jfr summary` reader reported one chunk covering 65 seconds. Preflight independently
printed `Preflight recording is one chunk; timestamps are comparable across startup.` The wrapper
was allowed to finish after the harness signalled the game, so `run.json` was finalized and both
`summary.json` and `adapter-analysis.json` were written. This exposed and fixed a harness defect:
the earlier unattended stop killed the wrapper together with the game, leaving `run.json` stuck at
`RUNNING` and skipping the exact chunk check this run existed to exercise.

The prepared-texture runtime remained healthy: 21,653 hits, three ordinary fallbacks, zero
corruptions, zero quarantines, zero internal errors, and no disable reason. Runtime blob checksum
verification was off, confirming that the trusted prepared-blob read path was live.

## The actual game reproduces the sampling-coverage hole

The physical JFR chunk covers 65 seconds, but recorded events span only 26.060 seconds and contain
4,047 execution samples — 40.1% of the chunk window. This closely reproduces the roughly 40%
coverage measured by the synthetic workload. One chunk repairs timestamp comparability; it does not
make execution sampling cover the rest of the window.

The samples that do exist attribute 39.6% to audio decode, 14.3% to texture image work, 12.4% to
Janino, 7.2% to other Starsector loading, and 6.3% to JSON. These are proportions of the available
samples, not wall-clock durations. Until the coverage hole is explained, they are useful for
prioritization and call-path discovery but cannot say what consumed the unsampled 38.3 seconds.
