# Native-swap CPU versus off-CPU split

**Date:** 2026-08-28

**Disposition:** retained diagnostic infrastructure; this is not an FPS optimization claim

**Parent:** [Gameplay FPS program #449](https://github.com/teamleaderleo/preflight/issues/449)

**Child:** [Hitch packet v1 #1150](https://github.com/teamleaderleo/preflight/issues/1150)

**Implementation:** `5702a9ae` (`Split native swap wall time from render CPU`)

## Result

The native-swap tail is almost entirely off-CPU wait, not render-thread computation inside
`Display.swapBuffers()`.

The thin Preflight-only confirmation captured a complete wall/CPU split for all 3,409 settled
paused-campaign frames. Native swap had a 17.4 ms p99. Current render-thread CPU inside the same
call had a 0.5 ms p99, while inferred off-CPU time had a 17.1 ms p99.

Twelve settled frames exceeded 33.33 ms. Native swap was the largest phase in ten; pre-swap work
was largest in two. Across those ten swap-dominated frames:

- native swap spent 16.430–17.806 ms off CPU;
- it spent only 0.305–0.372 ms on the render thread;
- off-CPU time was 97.948–98.296% of the native-swap interval, averaging 98.104%.

This establishes a boundary, not the final cause. Off-CPU time below the Java call may be VSync or
compositor waiting, GPU backlog, driver synchronization, or ordinary OS descheduling. It does rule
out Java/render-thread CPU inside the swap call as the explanation for this run's repeated
approximately 17 ms swap tail.

## Actual presentation policy

The exact installed settings file requests VSync and a 60 FPS cap. The transformed LWJGL class
observed five `setVSyncEnabled` requests: three enabled and two disabled. After normal policy
handling, it observed six actual `setSwapInterval` requests: four interval-one, two interval-zero,
none other, and a final interval of one. The opt-in force-VSync-off experiment was disabled and no
request was changed.

The scenario never opens settings or changes presentation policy after entering campaign, so final
interval one is consistent with the settled measurement. The v1 observer does not timestamp each
policy request or copy the interval into every frame, so it does not claim a per-frame policy
history. A future GPU/presentation probe should retain that state alongside its measurement window.

This also repairs an observability defect: ordinary VSync requests are now counted when the
force-off experiment is disabled. Earlier reports that showed zero requests did not prove VSync was
off; their counter returned before observing normal policy.

## Measurement boundary and overhead

The exact reviewed class is `org/lwjgl/opengl/Display` from the installed LWJGL JAR:

- JAR SHA-256 `527d509f60132e5b2653c7fc0f8cf299d6f698f4a8013342bef47705dc57ed3f`;
- class SHA-256 `054d89b13a904edd041df8ceba72c068070d7c9cc2dbfdd20d5acb3cdf109526`;
- class-file major 49.

Its `update(boolean)` calls static `swapBuffers()`, which delegates through `DrawableGL` to native
`ContextGL.swapBuffers()`. The retained adapter reads `System.nanoTime()` and the cached
`ThreadMXBean.getCurrentThreadCpuTime()` immediately around that exact call. The wall bracket
contains the two CPU-clock reads. A valid CPU delta must be nonnegative and no larger than the wall
delta; otherwise that frame reports the split unavailable rather than clamping or guessing.

On the shipped x86-64 Zulu Java 17.0.10 game runtime under Rosetta, the one-time 10,000-read live
calibration averaged 587.613 ns per CPU-clock read. The hot path uses two reads per presented frame,
so the calibrated average clock cost is about 1.175 microseconds per frame. All 12,552 live reads
succeeded. The existing inclusive boundary hook averaged 13.949 microseconds over 6,276 samples;
its scope deliberately excludes presentation, limiter, and CPU-clock hooks.

Calibration runs once only when explicit frame telemetry is requested. It does not enable a clock
the JVM disabled. Unsupported, disabled, invalid, or failed clocks keep the wall-time recorder and
mark the CPU split unavailable. No per-frame object is allocated.

## Hitches remain two fingerprints

Both bounded >50 ms packets completed their post-windows, and neither was a swap-wait hitch:

| Trigger | Total | Pre-swap excluding limiter | Native swap | Swap CPU / off CPU | Messages |
| --- | ---: | ---: | ---: | ---: | ---: |
| sequence 1,123 | 53.726 ms | 53.120 ms | 0.366 ms | 0.283 / 0.083 ms | 0.236 ms |
| sequence 3,682 | 50.774 ms | 39.050 ms | 0.889 ms | 0.742 / 0.147 ms | 0.772 ms |

The broader 60-to-30-FPS quantization and the rarer >50 ms game-work hitch are still separate
families. The former now points below the Java swap boundary; the latter still calls for
packet-triggered CPU escalation around remaining pre-swap work.

## Workload and claim boundary

The existing `campaign-hitch-limiter-current-state` scenario used internal PID/start-bound
Continue, left the loaded pause state untouched, waited 30 seconds, retained another 60 seconds,
passed all five semantic steps, and stopped only its owned process with code 0. It issued no save
action. Broad campaign timers and JFR were off.

The run preserved the immediately preceding diagnostic's 83-mod profile, texture profile, 1440x932
windowed display, sound, prepared-pixel path, Recommended preset, ordinary presentation policy,
and paused semantic route. The source and Preflight JAR necessarily changed to add the probe. The
scenario also does not bind an exact save hash. This record therefore makes a within-run phase
attribution claim; it does not compare FPS between the two diagnostic runs or call them a paired
cohort.

The settled series averaged 56.84 FPS with a 35.71 FPS 1% low. Those values describe this one run
only. They are not an uplift or regression claim.

## Correctness, fallback, and adapter health

The adapter observes policy and time. It does not alter the normal VSync argument, swap interval,
FPS cap, native call, control flow, gameplay state, save/load code, or serialization. Exact class,
JAR, loader, and method-shape gates must all match. A disabled plan, wrong identity, changed shape,
or second transform retains the original class. The independent existing smooth-frame-pacing
experiment remains opt-in and was off here.

Adapter health was `ACTIVE`: 65 exact transforms across 77 registered targets, with zero source
binding rejections, unavailable plans, declines, contained failures, cache rejection signals,
wrapper failures, or runtime-integrity failures. Lifecycle inspection found no gameplay fatal. The
known OpenAL native call after the PID-bound controller stop remained classified as shutdown-only.

Java 17 `./mvnw verify` passed all five modules: 2,242 tests, zero failures/errors, and nine expected
skips. A separate opt-in installed-artifact test transformed the exact installed LWJGL bytes and
proved the observer appears once at each reviewed seam.

## Reconciliation with #449

Current main plus this branch now has the parent program's bounded hitch flight recorder, semantic
paused/unpaused/combat series, scenario corpus, workload fingerprints, exact limiter split,
allocation/JFR analysis tools, and the first CPU-versus-off-CPU presentation boundary. The
CPU/GPU/presentation decomposition is only partial: CPU inside swap is measured, but GPU execution
and presentation wait are not yet separate. OpenGL command/state attribution, adaptive triggered
CPU capture, and a generalized per-mod frame/hitch tax remain research branches rather than shipped
conclusions.

Fresh verification changed one assumption: ordinary VSync request counts in old reports were
incomplete, while this run directly observed actual swap-interval requests and ended at interval
one. Still unverified are the live context's vendor/renderer/version, timer-query/fence capability,
and a per-frame interval history. Those are the next prerequisites; high-risk rendering changes are
not justified yet.

## Mutable conclusions and next experiment

**Observed:** the exact current policy ends at swap interval one, and the ordinary policy was not
modified.

**Observed:** all settled frames produced a valid current-thread CPU split. The ten
swap-dominated slow frames spent about 98.1% of their native-swap interval off CPU.

**Falsified for this fingerprint:** Java/render-thread computation inside `swapBuffers()` is not the
material source of the approximately 17 ms tail.

**Not yet distinguished:** GPU backlog, driver synchronization, compositor/VSync wait, and render
thread descheduling all remain inside the inferred off-CPU category.

**Highest-information next slice:** first record exact OpenGL vendor/renderer/version and timer-query
or fence capability without changing rendering. If the installed context supports a nonblocking,
bounded asynchronous GPU timer ring, measure GPU execution for the frame that precedes each swap
without waiting for query completion on the hot path. Carry the actual interval into the same
window. Only if GPU time grows with the swap tail should command/state attribution move ahead of
compositor/presentation analysis.

The machine-readable record and raw-file hashes are in
[`2026-08-28-native-swap-cpu-offcpu-split.json`](data/2026-08-28-native-swap-cpu-offcpu-split.json).
The ignored raw run directory is disposable after this bounded record is committed.
