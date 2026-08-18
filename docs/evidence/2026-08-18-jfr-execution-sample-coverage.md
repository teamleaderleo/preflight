# JFR execution-sample coverage on JDK 17

Date: 2026-08-18  
Issue: #254  
Measurement workflow: GitHub Actions run `32121892214`  
Retained measurements: [`data/2026-08-18-jfr-sample-coverage.csv`](data/2026-08-18-jfr-sample-coverage.csv)

## Answer

On the repository's current JDK 17 reference runtime, Temurin 17.0.20+8 on native macOS x86_64,
the dedicated worker's first/last `jdk.ExecutionSample` timestamps span **99.87% to 99.98% of the
observed 65-second monotonic wall interval** across the five recording-policy controls. The two
product-policy analogs span 99.88% (single large chunk) and 99.87% (ordinary periodic dump) of wall
time. Their JFR start/end marker clocks agree with wall time to within 0.03%.

That temporal span is nearly complete. Sample density is a separate result. With a 10 ms configured
execution-sampling period, the worker produced **1,775-1,874 successful `ExecutionSample` events in
about 65 seconds**, equal to **27.3%-28.8% of the configured interval count**. The largest observed
inter-sample gap after clock calibration was 167 ms. Every retained worker sample reported
`STATE_RUNNABLE`, and every run reported zero `jdk.DataLoss` events.

Preflight can therefore describe proportions **inside the observed execution-sample population** and
can use those proportions for controlled comparative attribution. It cannot turn those proportions
into absolute wall-clock seconds. A sample share is a share of successful observed samples. Time
between successful observations remains unknown unless another event or independent clock measures
it directly.

## Runtime and recording identity

The reference lane resolved:

- Eclipse Temurin / Eclipse Adoptium 17.0.20+8;
- OpenJDK 64-Bit Server VM;
- macOS 15.7.7;
- x86_64 JVM on an x86_64 host;
- four available processors on the hosted runner.

The exact historical lane was available and reproduced:

- Azul Systems Zulu17.48+15-CA;
- Java 17.0.10+7-LTS;
- Mach-O x86_64 JVM;
- arm64 macOS 15.7.7 host with Rosetta x86_64 translation active;
- three available processors on the hosted runner.

The workflow also ran current Temurin 17.0.20+8 as an x86_64 JVM on the same class of arm64/Rosetta
host, separating vendor/JDK-patch effects from the translated runtime environment.

## The historical 40% span reproduces as a clock-scale effect

The exact Zulu 17.0.10/Rosetta reproduction records about **65.0 seconds of monotonic wall time** as
about **26.0 seconds on the JFR event timestamp clock**. Its raw sample-span/wall ratios are
39.93%-39.95%, matching the old 48/120 and 26/65 observations.

The dedicated JFR start/end markers compress by the same amount: their marker-span/wall ratios are
39.96%-39.98%. Relative to those markers, the execution samples span **99.89%-99.94% of the recorded
interval**. The sampler did not stop around 40% into the wall interval; the JFR timestamp scale itself
ran at about 0.4x wall time.

Current Temurin 17.0.20+8 reproduces the same translated-runtime clock effect under Rosetta:
marker-span/wall ratios are 39.84%-39.97%, and sample-span/marker ratios remain 99.85%-99.99%.
The same Temurin build on native x86_64 records a ~1.0x marker clock. This evidence correlates the
0.4x timestamp scale with the measured x86_64-on-Rosetta runtime context, across two vendors and two
JDK 17 patch levels.

`-XX:+UseFastUnorderedTimeStamps` is not the boundary in this reproduction. The Rosetta controls show
the ~0.4x clock with that option absent and present. On the native x86_64 runner the current Temurin
runtime remains near 1.0x with the option present, while also printing HotSpot's warning that the
host lacks invariant/synchronized TSC guarantees.

The probe uses its custom start/end markers as the primary clock comparison. `jdk.CPULoad` timestamp
spacing is retained as an auxiliary periodic signal; the marker ratio gives the direct same-process
measurement for this question.

## Recording-policy controls

Five runs separate collection policy from runtime behavior:

| Policy | Chunks | Purpose |
| --- | ---: | --- |
| single/default clock | 1 | recorder-default control |
| single/256 MiB clock | 1 | buffer/chunk-size control |
| periodic/default clock | 2 | one `Recording.dump()` rotation at 60 s |
| single/256 MiB plus game timestamp flag | 1 | single-chunk product-policy analog |
| periodic/default plus game timestamp flag | 2 | survivable periodic product-policy analog |

The product choices remain distinct. Single-chunk recording disables the agent's periodic sidecar
flush and raises JFR memory/max-chunk limits to 256 MiB for one coherent retained recording. Ordinary
recording keeps periodic sidecar dumps so a long session can preserve partial evidence after an
abnormal exit.

### Chunk rotation

The periodic controls rotate to two chunks. Their sample-span/marker ratios remain essentially full
on all three runtime contexts. Sample-count density moves in different directions across the three
lanes. The retained measurements show no consistent association between the one deliberate rotation
and either temporal-span loss or lower sample density.

### Buffer pressure

The 256 MiB single-chunk control does not consistently increase sample density relative to the
recorder-default single-chunk control. Every run reports zero `jdk.DataLoss`. The historical 256 MiB
experiment had already shown the same raw ~40% timestamp span; the new marker measurement explains
why increasing recorder capacity could not change that ratio. The retained evidence gives no buffer-
pressure explanation for the remaining sparse sample count.

### Safepoints and target-thread state

The periodic dump runs add safepoint events, as expected from the additional recording operation, yet
temporal span remains essentially complete after clock calibration. Every retained sample from the
dedicated workload reports `STATE_RUNNABLE`. Across these controls, safepoint counts and sampled
thread state do not track the remaining sample-count variation.

### Runtime implementation

The timestamp-scale difference tracks the runtime execution context in this matrix:

| JVM | Host/execution | JFR marker clock / wall |
| --- | --- | ---: |
| Temurin 17.0.20 x86_64 | native x86_64 | ~1.000x |
| Temurin 17.0.20 x86_64 | arm64 host / Rosetta | ~0.399x |
| Zulu 17.0.10 x86_64 | arm64 host / Rosetta | ~0.400x |

This is a measured correlation, not a release-boundary claim.

## Why a 10 ms period does not promise one event every 10 ms per thread

The JDK 17 JFR sampler implementation schedules its sampling loop from a monotonic clock, then walks
the Java-thread list and records only successful stack samples. It caps a Java sampling task at five
successful samples, skips compiler threads, declines threads that are outside the Java state at the
attempt, and emits an event only after the stack walk succeeds. See the current JDK 17 update source:

- `src/hotspot/share/jfr/periodic/sampling/jfrThreadSampler.cpp` in `openjdk/jdk17u-dev`;
- `MAX_NR_OF_JAVA_SAMPLES = 5`;
- `thread_state_in_java(...)` and the post-suspend state re-check;
- `sample_thread_in_java(...)` returning false after an unsuccessful stack walk;
- `task_stacktrace(...)` rotating through the thread list and committing successful samples.

The probe's 21%-32% worker-event count relative to the configured interval count across all measured
lanes is therefore an **observed event-density measurement**, not a count of proven "lost" samples.
The legacy sampler has no event telling us how many attempted samples failed for this worker.

## Current upstream state

Research was performed against the OpenJDK issue tracker/JEPs on 2026-08-18.

### JDK-8273060 does not match this recording-window symptom

[JDK-8273060](https://bugs.openjdk.org/browse/JDK-8273060) is closed as a duplicate of
[JDK-8244514](https://bugs.openjdk.org/browse/JDK-8244514). Its reproducer exercises `Math.sin` and
related intrinsic/native sampling behavior: the method can be busy while `jdk.ExecutionSample`
contains few or no matching Java samples. JDK-8244514 remains open for intrinsic-method reporting.
That is a method/stack-visibility blind spot, not an explanation for a contiguous 40% timestamp span.

[JDK-8252417](https://bugs.openjdk.org/browse/JDK-8252417) also remains open for execution-sampling
stack failures around megamorphic interface calls/stub frames.

[JDK-8368844](https://bugs.openjdk.org/browse/JDK-8368844), currently targeted to JDK 27, tracks low
legacy sampler hit rates around code blobs, stubs, and intrinsics whose metadata is insufficient for
stack walking.

[JEP 509](https://openjdk.org/jeps/509) explicitly describes legacy `ExecutionSample` limitations:
technical sample attempts can fail without a count of the missed attempts, and the sampler selects a
subset of threads at each interval. JDK 25 delivered its separate experimental CPU-time sampler with
loss accounting. [JEP 518](https://openjdk.org/jeps/518), also delivered in JDK 25, added cooperative
sampling. Neither establishes a JDK 17 correction boundary for this repository.

### Clock-related upstream work is relevant, but no exact Rosetta 0.4x issue was found

[JDK-8355503](https://bugs.openjdk.org/browse/JDK-8355503) remains open around JFR's x86 timestamp
source and the use of RDTSC when invariant-TSC guarantees are unavailable. The issue describes the
risk of relying on a TSC whose steady frequency is unspecified. [JDK-8369467](https://bugs.openjdk.org/browse/JDK-8369467)
changed later JDKs by removing the non-invariant experimental RDTSC path in JDK 26.
[JDK-8273453](https://bugs.openjdk.org/browse/JDK-8273453) also tracks JFR timestamp-source choices.
Those issues make the clock mechanism relevant; they do not establish that a particular JDK 17
update fixes this Rosetta measurement.

[JDK-8294072](https://bugs.openjdk.org/browse/JDK-8294072) separately records that x64 JVMs under
Rosetta form a distinct JFR runtime context, though its reported symptom is a JFR hang/crash rather
than the clock-scale behavior measured here.

No upstream issue located in this research exactly names the ~0.4x JFR timestamp clock under an
x86_64 JDK 17 running through Rosetta. This repository therefore records the measurement without
inventing an upstream fix boundary.

## Downstream interpretation contract

For `jdk.ExecutionSample`, Preflight may say:

- a frame represents X% of the observed relevant execution samples;
- one controlled recording contains a higher/lower observed sample share than another;
- the sample stream spans a measured portion of an independently timed interval after its JFR clock
  has been validated or calibrated.

Preflight must keep these separate:

- **temporal span**: how far the first/last relevant sample timestamps extend across a validated
  interval;
- **sample density**: how many successful observations were actually retained;
- **sample composition**: how those successful observations divide among threads/frames;
- **wall-clock duration**: elapsed time measured directly by a suitable clock or duration event.

A 30% execution-sample share means 30% of observed relevant samples. It does not justify "30% of the
session" or "30% of wall seconds." Any elapsed interval lacking direct timing evidence remains
**unknown/unobserved time**.

## Artifact identity

The successful workflow uploaded the complete probe outputs and JFR files for seven-day CI
retention. Their upload digests were:

- current Temurin x86_64 native: artifact `9319030655`, SHA-256
  `2e2f304fd173ea73f6a8f65539302db9b747e9fcb2bb22ab44b35b63586d7cb8`;
- current Temurin x86_64/Rosetta: artifact `9319041953`, SHA-256
  `f92abdd3b10c0922df661acdd9b92b2256178ae684db8f7c826fda25b8c12bfe`;
- historical Zulu 17.0.10 x86_64/Rosetta: artifact `9319041154`, SHA-256
  `1dbb4073c00a676cd278ac307146acc33a445dbe9aedeef3b54e72d2c7fe97f7`.

The CSV linked at the top retains the required numerical evidence permanently in the repository.
The probe and manual workflow retain the reproduction path.
