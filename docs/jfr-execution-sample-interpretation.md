# Interpreting JFR execution samples

`jdk.ExecutionSample` is statistical evidence about the Java execution JFR actually sampled. Treat
its percentages as proportions inside that observed sample set.

That distinction is part of Preflight's measurement contract because the legacy JFR execution
sampler can miss opportunities, can select only a subset of runnable threads at an interval, and on
the reviewed Starsector launch path the JFR timestamp clock can differ from monotonic wall time.

## What Preflight may infer

For a recording whose sample population and policy are stated, Preflight may report findings such
as:

- 28% of the observed execution samples contained a particular method;
- a method appeared in more observed samples in one controlled recording than another;
- the observed sampled execution was concentrated in one subsystem or thread;
- a sample stream spans a measured fraction of the recording after its JFR clock has been checked
  against an independent monotonic interval.

Those are sample-population statements. They remain useful for hotspot discovery and comparative
attribution when the recording, workload, runtime, and collection policy are disclosed.

## Absolute seconds need an independent clock

Do not multiply an `ExecutionSample` percentage by process wall duration and present the result as
"seconds spent". A 30% sample share says 30% of the observed relevant samples belonged to that
frame. It does not establish that the frame consumed 30% of the session's elapsed wall time.

Use an independent monotonic timer, direct phase markers, event durations with a validated JFR clock,
or another measurement that directly observes elapsed time when a claim needs seconds.

If sample coverage leaves part of an interval unobserved, report that interval as
**unknown/unobserved time**. Do not distribute that time across sampled frames by extrapolation.

## Check the recording clock before reading sample timestamps as wall time

The sample-coverage probe in `scripts/jfr-sample-coverage/` records the same fixed interval with:

1. `System.nanoTime()` for monotonic wall duration;
2. JFR start/end marker events for the recording timestamp clock;
3. the first and last relevant `jdk.ExecutionSample` events;
4. one-second `jdk.CPULoad` events as an independent periodic JFR-clock check.

Read `markerSpanWallRatio` before `coverageSpanWallRatio`. When the marker ratio is far from 1, the
raw event timestamp span is using a different clock scale from monotonic wall time. In that case,
`coverageSpanMarkerRatio`, calibrated sample gaps, and sample-count yield carry the useful coverage
information.

A short raw JFR timestamp span therefore does not by itself prove that sampling stopped. A real
coverage gap requires evidence after clock calibration: missing sample count, a large calibrated
inter-sample gap, data loss, a thread-state explanation, or another measured discontinuity.

## Known limitations of the legacy sampler

OpenJDK's current upstream record gives several reasons to keep the interpretation bounded:

- JDK-8273060 is closed as a duplicate of JDK-8244514. Its reproducer concerns `Math.sin` and other
  intrinsic/native sampling blind spots, not a contiguous recording-window tail.
- JDK-8244514 remains open for intrinsic-method reporting.
- JDK-8252417 remains open for stack traversal failures around megamorphic interface calls and stub
  frames.
- OpenJDK's JEP 509 description states that the legacy execution sampler can fail to obtain samples
  for technical reasons without reporting the number missed, and selects only a subset of threads
  at each interval.
- JDK-8368844, currently targeted to JDK 27, tracks improving low hit rates by adding metadata for
  stubs, blobs, and intrinsics.
- JDK 25 delivered JEP 518 cooperative sampling and the Linux-only experimental JEP 509 CPU-time
  sampler. Those features do not establish a JDK 17 fix boundary.

Upstream references:

- https://bugs.openjdk.org/browse/JDK-8273060
- https://bugs.openjdk.org/browse/JDK-8244514
- https://bugs.openjdk.org/browse/JDK-8252417
- https://bugs.openjdk.org/browse/JDK-8368844
- https://openjdk.org/jeps/509
- https://openjdk.org/jeps/518

## Recording policy is a separate choice

Preflight keeps two JFR collection policies because they protect different evidence:

**Single chunk.** `--single-chunk-recording` disables periodic sidecar dumping and gives the recorder
large memory/chunk limits. Its purpose is timestamp and analysis coherence where one retained chunk
is practical.

**Ordinary periodic recording.** The default profiling path keeps periodic sidecar dumps. Its purpose
is survivability: a long session can retain partial evidence after a crash, force-quit, or abnormal
termination.

Sample interpretation follows the same rules under both policies. Do not tune either policy merely
to increase an attractive-looking sample ratio; use the coverage probe to describe what the runtime
actually records.
