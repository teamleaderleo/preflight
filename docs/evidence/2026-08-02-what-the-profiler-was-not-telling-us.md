# What the profiler was not telling us

**Date:** 2026-08-02
**Reproduced on:** the game's own JVM (Zulu 17.0.10 x86_64) with the real agent, on a synthetic
240-second workload — **no game involved**, clean exit, exit code 0
**Status:** two defects found and fixed, one measured and left open, and one earlier claim corrected.

## The claim being corrected

Yesterday's note said *"every `--profile` run this project has taken has been silently losing its
tail."* That is too strong, and the way it is wrong matters.

**No events are lost.** A recording that appears to stop at 96 seconds of a 240-second run contains
all of its events; splitting it chunk by chunk returns exactly the same count (10,193 + 3,531 =
13,724 execution samples, against 13,724 read from the whole file). What breaks is **time**: Flight
Recorder rotates to a new chunk at 12 MB, and when the resulting file is read as one recording,
every chunk after the first comes back stamped inside the first chunk's window. Chunk 1 read alone
spans 07:42:16–07:42:44; read as part of the concatenation, its events fold back into 07:39–07:41.

So: anything derived from **counts or proportions** on a multi-chunk recording is fine. Anything
derived from **when** is not.

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

## Open: execution samples cover about 40% of wall clock

Per-chunk, with flushing every 30 seconds, each chunk holds about **12 seconds of execution samples
out of its 30-second window**:

```
fixed_0.jfr  1478 samples  07:44:30.493 -> 07:44:42.467
fixed_1.jfr  1516 samples  07:45:00.537 -> 07:45:12.546
fixed_2.jfr  1545 samples  07:45:30.583 -> 07:45:42.558
...
```

Sampling stops partway through each window and resumes at the next rotation, which looks like a
buffer filling and being dropped until a rotation flushes it. **Raising the buffers does not fix
it**: `-XX:FlightRecorderOptions:memorysize=256m,maxchunksize=256m` over a 120-second run gave a
single chunk — good for timestamps — with samples spanning 48 seconds of the 120.

The root cause is not established. What follows from it, and holds regardless of the cause:

- **Sample counts are a sample of a sample.** Proportions between frames within one recording remain
  meaningful; absolute "this took N seconds because it had M samples" arithmetic does not.
- There is a real tension between the two fixes. More frequent flushing means more chunks, which
  means more sampling windows and *less* trustworthy cross-chunk time. A single-chunk recording with
  a large `maxchunksize` is the timestamp-accurate configuration and the worse-covered one.
  Wiring `maxchunksize` into the launch flags, and choosing per question rather than globally, was
  the next step.

> **Update, same day:** `preflight run --profile --single-chunk-recording` now wires
> `memorysize=256m,maxchunksize=256m` into the child JVM and sends `flush=0` to the agent as one
> policy. The run receipt records the selection, and postprocessing verifies the actual chunk
> count. It remains opt-in because it spends 256 MiB and gives up the periodic crash/force-quit
> sidecar.

> **Live update:** an unattended Starsector startup then produced exactly one 65-second chunk and
> reached the main menu in 64.4 seconds. Recorded events spanned 26.060 seconds — 40.1% of the
> physical chunk — so the real game reproduces the synthetic sampling-coverage hole. The harness
> also now leaves the Preflight wrapper alive after signalling the game, allowing the wrapper to
> finalize `run.json` and verify the one-chunk postcondition. See
> [the live profile](2026-08-02-live-single-chunk-startup-profile.md).

## What is not established

- **Why sampling stops within a window.** Buffer exhaustion is a hypothesis, contradicted at least
  in part by the large-buffer run.
- **Why the game's live recording has the same roughly 40% event span.** One accepted startup now
  reproduces the ratio, but Starsector spends substantial time in native GL, which can legitimately
  suppress Java execution samples and may not share the synthetic workload's root cause.
- **The sidecar does not fix truncation**, and was never going to — it truncates the same way,
  because it is the same recording. It fixes losing everything.
