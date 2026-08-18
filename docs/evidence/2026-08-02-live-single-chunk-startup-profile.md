# The single-chunk startup profile works live — and reproduces the raw JFR clock ratio

**Date:** 2026-08-02
**Install:** Starsector 0.98a-RC8, Zulu 17.0.10 x86_64 under Rosetta, current 77-mod profile
**Session:** `~/.starsector-preflight/benchmarks/20260802-090059`
**Status:** main-menu run accepted; one-chunk policy and finalized wrapper receipt verified live

> **2026-08-18 correction (#254):** the 26.060/65-second ratio below was reproduced on the exact
> Zulu 17.0.10 x86_64/Rosetta runtime with an independent monotonic wall clock and JFR start/end
> markers. The JFR timestamp clock itself advances at about 0.4x wall time in that runtime context,
> while execution samples span about 99.9% of the JFR marker interval. The original "sampling hole"
> interpretation is superseded. The remaining JDK 17 limitation is sparse successful-sample density,
> and execution-sample percentages remain proportions of observed samples. See
> [the #254 measurement](2026-08-18-jfr-execution-sample-coverage.md).

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

## The actual game reproduces the raw timestamp-scale observation

The physical JFR chunk covers 65 wall-clock seconds, while the first/last recorded event timestamps
span 26.060 JFR timestamp seconds and contain 4,047 execution samples — a raw 40.1% timestamp-span /
wall-time ratio. The #254 marker probe later reproduced that ratio on the exact runtime and showed
that the JFR marker clock itself advances at about 0.4x monotonic wall time. The raw 40.1% ratio is
therefore a clock-scale observation, not evidence that execution sampling stopped for the remaining
wall interval.

The samples attribute 39.6% to audio decode, 14.3% to texture image work, 12.4% to Janino, 7.2% to
other Starsector loading, and 6.3% to JSON. These are proportions of the observed samples, not
wall-clock durations. They remain useful for prioritization and call-path discovery; elapsed seconds
require independent timing evidence, and time between successful sample observations remains
unknown/unobserved.
