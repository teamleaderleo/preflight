# What the profiler was not telling us

**Date:** 2026-08-02
**Reproduced on:** the game's own JVM (Zulu 17.0.10 x86_64) with the real agent, on a synthetic
240-second workload — **no game involved**, clean exit, exit code 0
**Status:** two defects found and fixed, one measured and left open, and one earlier claim corrected.

> **2026-08-18 correction (#254):** the ~40% first/last `ExecutionSample` timestamp span below was
> reproduced on the exact Zulu 17.0.10 x86_64/Rosetta runtime with an independent monotonic wall
> clock plus JFR start/end markers. The JFR marker clock itself advances at about 0.4x wall time in
> that runtime context, while execution samples span about 99.9% of the marker interval. Current
> Temurin 17.0.20 x86_64 under Rosetta shows the same clock scale; the same Temurin build on native
> x86_64 is ~1.0x. The old 48/120 and 26/65 measurements therefore describe **raw JFR timestamp
> scaling**, not sampling stopping after 40% of wall time. A separate JDK 17 limitation remains:
> successful `ExecutionSample` event density is sparse relative to the configured sampler period.
> See [the retained #254 measurement](2026-08-18-jfr-execution-sample-coverage.md). The observations
> below are preserved as the historical trail; this correction supersedes their 40%-coverage causal
> interpretation.

## The claim being corrected

Yesterday's note said *"every `--profile` run this project has taken has been silently losing its
tail."* That is too strong, and the way it is wrong matters.

**No events are lost.** A recording that appears to stop at 96 seconds of a 240-second run contains
all of its events; splitting it chunk by chunk returns exactly the same count (10,193 + 3,531 =
13,724 execution samples, against 13,724 read from the whole file). What breaks is **time**: Flight
Recorder rotates to a new chunk at 12 MB, and when the resulting file is read as one recording,
every chunk after the first comes back stamped inside the first chunk's window. Chunk 1 read alone
spans 07:42:16–07:42:44; read as part of the concatenation, its events fold back into 07:39–07:41.

So chunk folding leaves retained event counts unchanged. Proportions remain proportions inside that
retained event population; execution-sample proportions still require the separate sample-coverage
interpretation documented by #254. Anything derived from **when** also needs a validated recording
clock.

**The `getEntityById` recording is a single chunk**, so
[that finding](2026-08-02-a-failed-lookup-scans-the-sector.md) is unaffected — its sample shares and
its call-path attribution never depended on cross-chunk time, and its chunk count is 1 either way.

## Defect 1: the recording is a zero-byte file for the whole run

A `Recording` with a destination and `dumpOnExit` writes **nothing** until the JVM's own shutdown
hook runs. Watched directly: 240 seconds into a run, the destination was 0 bytes. Force-quit the
game, kill it, crash it, and the entire session is gone.

**Fixed.** `RecordingFlusher` dumps the recording to a sidecar (`startup.partial.jfr`) on a
minimum-priority daemon thread every `flush=<seconds>` (default 60; `flush=0` disables). Verified
live: at 60 seconds into a run the destination was still 0 bytes and the sidecar was 5.0 MB.

The sidecar never touches the destination. Which of the two to keep is decided by `RecordingSidecar`
**after the process is gone**, where the two writers cannot race — the JVM's exit dump is the only
writer that sees the final chunk, so it usually wins, and when it does not, the loser is kept beside
the winner rather than deleted.

## Defect 2: nothing said the timestamps were unusable

`RecordingCoverage` counts chunks by walking the chunk headers — the public JFR API reports events
but not chunking, and scanning the file for the `FLR\0` magic does not work, because that sequence
occurs inside chunk bodies too and reports two chunks for a perfectly good single-chunk recording.
`run` now prints a loud warning naming the split command when a recording holds more than one chunk.

Reader-side, `jfr print` truncates stacks to **5 frames** by default, which is why an earlier pass at
the save-load recording attributed nothing to `Memory.replaceIdsWithEntities`:

```
jfr print --stack-depth 64 --events ExecutionSample out.jfr
```

## Superseded: execution-sample timestamps span about 40% of wall clock

Per-chunk, with flushing every 30 seconds, each chunk holds about **12 seconds of execution-sample
timestamps out of its 30-second wall window**:

```
fixed_0.jfr  1478 samples  07:44:30.493 -> 07:44:42.467
fixed_1.jfr  1516 samples  07:45:00.537 -> 07:45:12.546
fixed_2.jfr  1545 samples  07:45:30.583 -> 07:45:42.558
...
```

The original interpretation said sampling stopped partway through each window and resumed at the
next rotation, with buffer filling as one hypothesis. **Raising the buffers did not change the raw
ratio**: `-XX:FlightRecorderOptions:memorysize=256m,maxchunksize=256m` over a 120-second run gave a
single chunk with sample timestamps spanning 48 seconds of the 120.

The 2026-08-18 marker probe resolves that particular ratio: the JFR timestamp clock on the measured
x86_64/Rosetta runtime advances at about 0.4x monotonic wall time. The large-buffer result therefore
remained at 40% because recorder capacity was never the cause of that raw timestamp ratio.

What still follows from the retained evidence:

- **Sample counts are statistical observations.** Proportions between frames within one recording
  describe the observed sample population; absolute "this took N seconds because it had M samples"
  arithmetic is unsupported.
- Single-chunk and periodic sidecar recording remain separate operational policies. The former
  favors one coherent retained recording; the latter preserves partial evidence during a long
  session. #254 found no consistent sample-density advantage from the 256 MiB limits or the one
  deliberate 60-second rotation.

> **Update, same day:** `preflight run --profile --single-chunk-recording` now wires
> `memorysize=256m,maxchunksize=256m` into the child JVM and sends `flush=0` to the agent as one
> policy. The run receipt records the selection, and postprocessing verifies the actual chunk
> count. It remains opt-in because it spends 256 MiB and gives up the periodic crash/force-quit
> sidecar.

> **Live update:** an unattended Starsector startup then produced exactly one 65-second chunk and
> reached the main menu in 64.4 seconds. Recorded events spanned 26.060 JFR timestamp seconds —
> 40.1% of the wall interval — so the real game reproduced the synthetic raw clock ratio. The #254
> reproduction now identifies that ratio as timestamp scaling in the measured x86_64/Rosetta
> context. See [the live profile](2026-08-02-live-single-chunk-startup-profile.md).

## What remained open after the original 2026-08-02 run

The original run left these questions open. #254 answers the first two at the level described above
and keeps the third as a separate survivability concern:

- The ~40% raw execution-sample timestamp span follows the ~0.4x JFR marker clock on the reproduced
  x86_64/Rosetta environment. It is not evidence that the sampler stopped for the remaining 60% of
  wall time.
- The live game's similar ratio is consistent with the same clock-scale measurement. Native GL can
  still change which Java execution samples appear, so method shares remain observed-sample evidence.
- **The sidecar does not fix final-file truncation**, and was never intended to. It fixes losing the
  whole recording after an abnormal exit.
