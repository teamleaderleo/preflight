# JFR execution-sample coverage probe

This probe measures the recording before using `jdk.ExecutionSample` for attribution. It runs one
pure-Java integer worker for a fixed wall duration, records the same interval with monotonic time and
JFR marker events, and then reports how the worker's execution samples cover that interval.

The retained #254 result is
[`docs/evidence/2026-08-18-jfr-execution-sample-coverage.md`](../../docs/evidence/2026-08-18-jfr-execution-sample-coverage.md).

The primary output is JSON. Each run records:

- observed monotonic wall duration;
- first and last `jdk.ExecutionSample` timestamp for the dedicated worker;
- worker sample count and count relative to the configured sampling interval;
- raw sample timestamp span / wall-time ratio;
- sample span / JFR marker span, so JFR clock scaling is visible separately from sample coverage;
- leading, trailing, and maximum inter-sample gaps, both in JFR time and after marker-clock
  calibration;
- chunk count, recording bytes, periodic dump count, and JVM Flight Recorder options;
- `jdk.DataLoss` count/bytes, safepoint event counts, sampled thread states, and native-method
  sample count;
- runtime vendor, version, VM, OS, architecture, processor count, and JVM input flags.

`jdk.CPULoad` is also recorded at a one-second period as an auxiliary periodic clock signal. The
custom start/end markers are the primary same-process JFR-clock comparison. The worker itself avoids
native calls and math intrinsics, so the primary measurement does not exercise the intrinsic blind
spot tracked by OpenJDK JDK-8244514/JDK-8273060.

## Run it

From the repository root:

```bash
bash scripts/jfr-sample-coverage/run-probe.sh target/jfr-sample-coverage
```

The default duration is 65 seconds. Override it only for probe development:

```bash
JFR_COVERAGE_DURATION_SECONDS=10 bash scripts/jfr-sample-coverage/run-probe.sh /tmp/jfr-coverage
```

The script runs five policies against the same workload:

| policy | purpose |
| --- | --- |
| `single-default-clock-normal` | diagnostic control using the runtime's recorder defaults |
| `single-large-clock-normal` | diagnostic control with 256 MiB recorder/chunk limits |
| `periodic-default-clock-normal` | diagnostic control adding a 60-second dump/rotation |
| `single-large-fast-unordered` | Preflight single-chunk policy plus Starsector's `UseFastUnorderedTimeStamps` flag |
| `periodic-default-fast-unordered` | survivable 60-second periodic policy plus the same game clock flag |

The last two are separate product policies. `single-large-fast-unordered` favors coherent timestamps
inside one retained recording. `periodic-default-fast-unordered` retains partial evidence during a
long session by dumping periodically. The probe keeps both because they answer different operational
needs.

## Reading a result

Start with `markerSpanWallRatio`. A value far from 1 means the JFR timestamp clock differs from the
monotonic wall clock. In that case `coverageSpanWallRatio` is a raw timestamp comparison, while
`coverageSpanMarkerRatio` asks the narrower question: did execution samples span the interval that
JFR's own start/end markers span?

Then inspect `sampleCountExpectedRatio`, the calibrated gap fields, `dataLossEventCount`, chunk count,
and sampled thread states. Keep temporal span and sample density separate. The retained #254 run, for
example, has almost complete calibrated temporal span while the successful worker-event count is
only about 21%-32% of the configured interval count depending on runtime/control. A configured period
is sampler cadence, not a promise of one successful event per target thread per period.

Execution-sample percentages describe proportions within the samples that were actually observed.
They do not establish absolute wall-clock seconds. Any interval left uncovered after clock
calibration remains unknown/unobserved time and should be reported that way.

## CI runtime controls

`.github/workflows/jfr-sample-coverage.yml` is a manually repeatable evidence workflow. The retained
2026-08-18 run measured the repository's current Temurin 17 runtime on a native x86_64 macOS runner,
plus two x86_64/Rosetta controls on an Apple-silicon runner: current Temurin 17 and exact Zulu
17.0.10. The historical lane counts as a reproduction only when the run itself records an arm64 host
and the expected x86_64 Zulu 17.0.10 JVM. Setup failure remains an availability result rather than a
version claim.
