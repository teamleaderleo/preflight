# Repeated startup benchmark

This is the harness for [M10](https://github.com/teamleaderleo/starsector-preflight/issues/80):
the only thing in this repository that can turn Preflight's work into a load-time number.
Until it produces one, the project has no measured acceleration, and
[benchmarking.md](benchmarking.md) records why every earlier attempt failed.

```bash
scripts/run-startup-benchmark.sh
```

You do exactly two things per launch, each announced with a terminal bell: click **Play
Starsector**, then quit from the main menu once it is up. Everything else is automatic.

## The three conditions

| condition | how it launches | what it isolates |
| --- | --- | --- |
| `vanilla` | the game's own `starsector_mac.sh`, with `JAVA_TOOL_OPTIONS` cleared | the true baseline |
| `agent` | `preflight run --no-adapter` | what the JFR recorder itself costs |
| `enabled` | `preflight run --adapter --texture-auto` | the prepared texture path |

The middle condition is the one that is easy to leave out and expensive to lose. Preflight
attaches a recording agent in **both** of its modes, so a bare `enabled` minus `vanilla`
difference mixes "the cache helped" with "the recorder cost us". Only `agent` separates them.

`enabled` uses `--texture-auto`, which resolves the manifest and index for the current
profile and runs the accepted compatibility texture path. That is deliberate: it is the
mode `benchmark compare-runs` will accept, and the prepared-pixel path remains opt-in.

Preparation is **not** a condition. `preflight prepare` runs offline, builds the caches, and
exits before any launch; the harness times it once (about 50 seconds on the reviewed
installation) and records it as `preparationMillis` setup cost.

## What is measured

`gameLogStartToMainMenuMs` — the first game log line after you click Play, through to
GraphicsLib reporting VRAM after preload, confirmed by six seconds of log silence.

It deliberately excludes the launcher wait, because that interval contains your reaction
time. It also excludes world generation: do not load a save. The startup work Preflight
targets — mod init, texture load, audio decode — all completes before the main menu.

Detection comes from [`starsector_log_ready_detector.py`](../scripts/starsector_log_ready_detector.py),
which tails the log by inode so rotation cannot reintroduce old bytes, and which now also
emits absolute instants so the same measurement can feed `preflight benchmark scenario`.

## Why the order is shuffled

The 2026-07-23 pilots failed three times, and the design here is a direct response.

**One sample per mode.** Both attempts recorded `samplesPerMode: 1`. A single pair is not a
measurement. The default is five rounds of every condition, and the report refuses to set
`benchmarkAccepted` below that.

**Blocked order.** Running all of A, then all of B, lets thermal drift, page-cache warming,
and background load line up with condition. The harness shuffles the conditions *inside
every round* from a recorded seed, so drift is spread across conditions instead of
confounded with them.

**One bad run destroying the session.** The old harness aborted the whole comparison on any
drift. Here a launch that crashes, times out, or changes the mod profile is recorded as
`excluded` with its reason and the campaign continues. Ctrl-C is safe; `--resume` picks the
session back up and keeps completed runs.

GraphicsLib writes generated normal maps into its own cache on a first run and reuses them
afterwards — that one-time write is what invalidated the July comparison. The harness opens
with a discarded settling launch so the installation stops changing before anything counts.
If the profile drifts anyway, the harness adopts the settled fingerprint **and rebuilds the
caches**, because `--texture-auto` refuses to launch against a profile its index was not
built for.

## Reading the result

```text
condition                 n    median      min      max
-------------------------------------------------------
vanilla (no preflight)    5    85.06s   84.30s   85.90s
agent only (recorder)     5    86.65s   86.20s   87.10s
preflight enabled         5    79.10s   78.40s   80.20s

preflight enabled vs vanilla: 5.96s faster (7.0%), p = 0.008
```

Each comparison carries an exact permutation p-value over the difference in medians. With
three runs per condition the smallest reachable value is 0.100, so a three-round session
cannot produce a significant result no matter how large the gap looks — the report says so
rather than letting the number stand alone.

`benchmarkAccepted` is true only when every condition reached five successful runs. Even
then it is one machine on one mod profile: treat it as a measurement, not a general claim.

## Options

```bash
scripts/run-startup-benchmark.sh --rounds 5              # default
scripts/run-startup-benchmark.sh --conditions vanilla,enabled
scripts/run-startup-benchmark.sh --resume ~/.starsector-preflight/benchmarks/20260730-...
scripts/run-startup-benchmark.sh --skip-warmup           # only if you just settled one
```

Each session writes to `~/.starsector-preflight/benchmarks/<timestamp>/`:
`identity.json` (repository head, JAR hash, hardware, OS, Java, profile fingerprint, seed),
`results.jsonl` (one line per launch), `benchmark-summary.json`, and a per-run directory
holding the JFR recording, profile census, detector output, and log snapshots.
