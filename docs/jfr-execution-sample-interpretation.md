# Interpreting JFR execution samples

`jdk.ExecutionSample` is statistical evidence about the Java execution JFR actually sampled. Treat
its percentages as proportions inside that observed sample set.

That distinction is part of Preflight's measurement contract because the JDK 17 JFR execution
sampler records only successful stack-sampling attempts, selects a bounded subset of Java threads in
a sampling task, and can omit attempts for thread-state or stack-walk reasons without emitting a
missed-sample count. The recording timestamp clock can also differ from monotonic wall time.

The retained measurement for issue #254 is
[`evidence/2026-08-18-jfr-execution-sample-coverage.md`](evidence/2026-08-18-jfr-execution-sample-coverage.md).

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

## Keep temporal span, sample density, and sample composition separate

The current Temurin 17.0.20+8 native-x86_64 probe spans essentially the whole 65-second interval:
the first/last relevant execution-sample timestamps cover 99.87%-99.98% of monotonic wall time
across the controls. At the same time, a 10 ms configured sampling period produced only 1,775-1,874
successful worker `ExecutionSample` events in about 65 seconds, or 27.3%-28.8% of the configured
interval count.

The second number is an observed density comparison, not a count of proven lost samples. In JDK 17,
the sampling period controls the sampler task cadence. It does not promise one successful event for
every target thread on every period. The sampler walks the thread list, caps a Java sampling task at
five successful samples, requires the target to be in Java at the attempt, and commits an
`ExecutionSample` only after a successful stack walk.

Consequently:

- **temporal span** says how far first/last successful observations extend across a validated time
  interval;
- **sample density** says how many successful observations were retained;
- **sample composition** says how those successful observations divide among frames/threads;
- **wall-clock duration** comes from direct elapsed-time evidence.

A recording can have almost full temporal span and still contain a sparse statistical sample set.

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
4. one-second `jdk.CPULoad` events as an auxiliary periodic JFR-clock check.

Read `markerSpanWallRatio` before `coverageSpanWallRatio`. When the marker ratio is far from 1, the
raw event timestamp span is using a different clock scale from monotonic wall time. In that case,
`coverageSpanMarkerRatio`, calibrated sample gaps, and sample-count yield carry the useful coverage
information.

Issue #254 reproduces the historical ~40% raw timestamp span on the exact Zulu 17.0.10 x86_64 JVM
under Rosetta. Its JFR markers compress by the same amount, and its samples cover ~99.9% of the marker
interval. Current Temurin 17.0.20 x86_64 under Rosetta does the same, including runs where
`-XX:+UseFastUnorderedTimeStamps` is absent. The same current Temurin runtime on native x86_64 records
a ~1.0x JFR marker clock. This is a measured runtime-context correlation, not a JDK patch or vendor
fix boundary.

A short raw JFR timestamp span therefore does not by itself prove that sampling stopped. A real
coverage gap requires evidence after clock calibration: low successful-sample density, a large
calibrated inter-sample gap, data loss, a thread-state explanation, or another measured
discontinuity.

## Known limitations of the JDK 17 sampler

OpenJDK's current upstream record gives several reasons to keep the interpretation bounded:

- JDK-8273060 is closed as a duplicate of JDK-8244514. Its reproducer concerns `Math.sin` and other
  intrinsic/native sampling blind spots, not a contiguous recording-window tail.
- JDK-8244514 remains open for intrinsic-method reporting.
- JDK-8252417 remains open for stack traversal failures around megamorphic interface calls and stub
  frames.
- OpenJDK's JEP 509 description states that the legacy execution sampler can fail to obtain samples
  for technical reasons without reporting the number missed, and selects only a subset of threads
  at each interval.
- JDK-8368844, currently targeted to JDK 27, tracks low hit rates around code blobs, stubs, and
  intrinsics whose metadata is insufficient for stack walking.
- JDK 25 delivered JEP 518 cooperative sampling and the experimental JEP 509 CPU-time sampler. Those
  features do not establish a JDK 17 fix boundary.

Clock-related upstream work is relevant too. JDK-8355503 describes risks around JFR's x86 timestamp
source when invariant-TSC guarantees are unavailable; JDK-8369467 removed the non-invariant
experimental RDTSC path in JDK 26. JDK-8294072 separately documents x64 JFR running through Rosetta
as a distinct runtime context. None of those issues currently names the exact ~0.4x JFR clock
reproduced by #254, so Preflight records the correlation without inventing an upstream correction
boundary.

Upstream references:

- https://bugs.openjdk.org/browse/JDK-8273060
- https://bugs.openjdk.org/browse/JDK-8244514
- https://bugs.openjdk.org/browse/JDK-8252417
- https://bugs.openjdk.org/browse/JDK-8368844
- https://bugs.openjdk.org/browse/JDK-8355503
- https://bugs.openjdk.org/browse/JDK-8369467
- https://bugs.openjdk.org/browse/JDK-8294072
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

The #254 controls show essentially complete calibrated temporal span with both policies. They also
show no consistent sample-density improvement from the 256 MiB recorder limits, no `jdk.DataLoss`,
and no consistent density change from the deliberate 60-second chunk rotation. Sample interpretation
follows the same rules under both policies. Tune either policy for its retention purpose, and use the
coverage probe to describe what the runtime actually records.
