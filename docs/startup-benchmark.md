# Repeated startup benchmark

This is the harness for [M10](https://github.com/teamleaderleo/starsector-preflight/issues/80):
the only thing in this repository that can turn Preflight's work into a load-time number.
Until it produces one, the project has no measured acceleration, and
[benchmarking.md](benchmarking.md) records why every earlier attempt failed.

```bash
scripts/run-startup-benchmark.sh --unattended
```

With `--unattended` you do nothing at all: the game starts itself and is stopped once its own
log says the load finished. Without it, you do two things per launch, each announced with a
terminal bell — click **Play Starsector**, then quit from the main menu once it is up.

## The two protocols

`--unattended` is not automation layered on top of the clicked protocol; it is a different
launch, and a results file may hold only one of the two.

| protocol | how the game starts | what it costs |
| --- | --- | --- |
| `clicked` | the launcher is built, shown, and an operator presses Play | your reaction time, and a launcher |
| `direct` | Starsector's own `launchDirect` path starts the game with no launcher at all | nothing |

`direct` is the game's, not ours. `StarfarerLauncher`'s constructor checks `launchDirect`
*before* it decides between the legacy Swing UI and the OpenGL one, and when the property is
present it reads `startRes`, `startFS` and `startSound`, calls the same static start method the
Play button would have called, and returns without constructing a UI. `preflight
launch-settings` reads those three values out of the launcher's own preferences, so an
unattended launch is configured exactly like the one you would have clicked.

It refuses rather than guesses. An unregistered copy makes the game take the direct branch and
return without launching; a resolution that is not `WIDTHxHEIGHT` reaches `split("x")[1]` inside
a try whose handler is a modal native dialog. Both would cost a full timeout, so both are
checked before the first launch and reported with a reason.

The reporter refuses to summarize a file that mixes the two. The launcher's OpenGL context, font
loading and window creation exist in one and not the other, so a median across them is two
quantities read as one.

## The conditions

| condition | how it launches | what it isolates |
| --- | --- | --- |
| `vanilla` | the game's own `starsector_mac.sh`, with `JAVA_TOOL_OPTIONS` cleared | the true baseline |
| `agent` | `preflight run --no-adapter` | what the JFR recorder itself costs |
| `enabled` | `preflight run --adapter --texture-auto` | the prepared texture path, recorded |
| `compatibility` | the same plus `--no-record` | the historical compatibility-texture subset without profiling |
| `fast` | `preflight run --fast` | **the current normal user launch: every live-gated optimization** |
| `full` | the frozen 2026-08-03 explicit prepared-pixel and rule-cache stack | reproduction of the accepted historical campaign |
| `profile` | the same plus `--profile` | **not a timing condition** — see below |

`profile` is opt-in and is not part of the default set, because reading its wall clock as
a result would be a mistake: it records, so it is slower than `fast` by construction. It
exists to answer *where* the time goes rather than *how much* there is. What it changes is
which events the agent enables — periodic execution sampling and threshold-gated blocking
events, without the stack-traced class loads, class definitions and file reads that make up
most of the recorder's ~24%. That distinction matters for one specific reason: the expensive
part of the full recording falls hardest on **class loading**, so a full profile inflates the
very thing it is most often consulted about. Analyse these runs with `preflight analyze`.

The recorder condition is easy to leave out and expensive to lose. Preflight
attaches a recording agent in **both** of its modes, so a bare `enabled` minus `vanilla`
difference mixes "the cache helped" with "the recorder cost us". Only `agent` separates them.

`fast` now passes the CLI's literal `--fast` preset, so its meaning advances only when an
optimization passes its live gate and joins the installed-launcher path. Before 2026-08-05 the
benchmark condition with this name actually ran compatibility textures plus `--no-record`; as the
CLI preset grew, the benchmark silently stopped representing a normal user launch. That historical
subset is retained as `compatibility`, and the reporter uses it for like-for-like recorder and
prepared-pixel component comparisons.

`full` is also retained, but frozen: it is the explicit flag set used by the accepted 2026-08-03
whole-stack campaign. It reproduces that evidence; it is no longer a synonym for "everything that
has landed". Use `fast` for the current product and `full` only for historical comparison.

`enabled` uses `--texture-auto`, which resolves the manifest and index for the current
profile and runs the accepted compatibility texture path. That is deliberate: it is the
mode `benchmark compare-runs` will accept, and the prepared-pixel path remains opt-in.

Preparation is **not** a condition. `preflight prepare` runs offline, builds the caches, and
exits before any launch; the harness times it once (about 50 seconds on the reviewed
installation) and records it as `preparationMillis` setup cost.

## What is measured

`gameLogStartToGraphicsPreloadMs` — from the first line Starsector's game-start method logs,
`Running with the following mods (in order of priority):`, through to GraphicsLib reporting
VRAM after preload. Both boundaries are markers the game logs itself, and the measurement
completes the moment the second one lands.

There is no quiet window any more. It used to be what proved the phase had ended, back when the
measurement ran to "the last line before it" — but the preload marker proves that directly, and
silence does not reliably arrive: the game keeps emitting `Cleaned buffer for texture` from the
main menu in irregular bursts. On 2026-08-01 a launch whose load finished at 94.8s was still
logging at 231.8s. Under the clicked protocol this never showed, because the operator quit the
game and the log stopped; unattended, the harness sat on a completed measurement it already held.
The `trailingLogActivityMs` that ranged 0.0-9.3s across identical runs was measuring when that
trickle happened to pause, not anything about the game.

**That start anchor is load-bearing, and getting it wrong produced every startup number this
project recorded before 2026-08-01.** The measurement used to begin at the first log line that
appeared after the harness took its snapshot. Starsector's launcher writes into the same log
the game does, so whether the launcher's lines had been flushed by then decided whether the
early part of loading landed inside the measured interval. That is the entire "unexplained 18s
bimodality": every run anchored on the launcher's line measured 92-99s, every run anchored on a
later mid-load line measured 74-78s, and nothing else separated them. Reading the same launches
straight out of the game's log says the high mode was correct — startup is ~92s, not ~75s. See
[the evidence](evidence/2026-08-01-the-bimodality-was-the-anchor.md).

`scripts/starsector_log_load_times.py` recovers load times from the game's logs with no harness
involved, and is the independent check on all of this. Run it after a campaign: the harness and
the detector agreed with each other the whole time this was wrong.

That distinction is worth the paragraph. The first version measured to *the last line before
the quiet window*, which meant whatever the game happened to log next landed in the result.
On the 2026-07-31 campaign that trailing chatter ranged from **0.0 to 9.3 seconds** across
otherwise identical runs. The preload phase itself is not the variable part: from the
save-descriptor read to the preload marker was 0.5-0.7s in every one of sixteen runs. Both
boundaries are still recorded, along with `trailingLogActivityMs`, so the excluded noise
stays visible.

It deliberately excludes the launcher phase, because under the clicked protocol that
interval contains your reaction time and under the direct one it does not exist. It also excludes world generation: do not load a save. The startup work Preflight
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

**A fail-open adapter counted as a real measurement.** The texture adapter is fail-open by
design: on a stale artifact or a circuit-breaker trip it quietly stops serving and the game
loads normally. Such a run is indistinguishable from the baseline by timing alone, so
counting it would measure the baseline twice and produce the honest-looking conclusion that
the cache does nothing. Every `enabled` run is now checked against its own telemetry —
ready, hits above zero, no internal errors, no disable reasons — and excluded as
`adapter-served-nothing` if the path did not actually run.

**A crashed game that never exits.** Starsector passes `-XX:+ShowMessageBoxOnError`, so a
fatal JVM error prints its report and then blocks on stdin forever — process alive, CPU
above zero, loading bar at 100%, indistinguishable from a slow load. A watchdog now scans
the wrapper output for HotSpot's fatal banner and kills the process tree, so the run is
excluded as `jvm-crash` within seconds instead of consuming the full timeout. Termination
covers the whole tree because the game is a *grandchild* of this script, via the wrapper
and `starsector_mac.sh`. See
[a JVM crash that looks exactly like a slow load](evidence/2026-07-31-a-jvm-crash-that-looks-exactly-like-a-slow-load.md);
`--no-record` also removes the execution sampling that is the most likely contributor.

GraphicsLib writes generated normal maps into its own cache on a first run and reuses them
afterwards — that one-time write is what invalidated the July comparison. The harness opens
with a discarded settling launch so the installation stops changing before anything counts.
If the profile drifts anyway, the harness adopts the settled fingerprint **and rebuilds the
caches**, because `--texture-auto` refuses to launch against a profile its index was not
built for.

## Reading the result

The report names several comparisons, not one, because **the interesting ones are not
against the baseline**. A comparison only isolates something when its two conditions differ
in exactly one thing:

```text
enabled vs agent     +12.81s ( 13.7%)  p = 0.119   the texture cache, recorder held constant
fast vs vanilla       +7.20s (  9.6%)  p = 0.032   what a user would actually feel
agent vs vanilla     -10.82s ( 13.1%)  p = 0.167   the cost of the recorder
enabled vs vanilla    +1.99s (  2.4%)  p = 0.714   net, confounded by the recorder
```

The first campaign, on 2026-07-31, reported only the last of those and so reported 2.4%.
That single number hid a texture cache worth about 15% behind a recorder costing about 24%.
Reporting one comparison against one baseline is how a real effect goes missing.

Each comparison carries an exact permutation p-value over the difference in medians. With
three runs per condition the smallest reachable value is 0.100, so a three-round session
cannot produce a significant result no matter how large the gap looks — the report says so
rather than letting the number stand alone.

`benchmarkAccepted` is true only when every condition reached five successful runs. Even
then it is one machine on one mod profile: treat it as a measurement, not a general claim.

Two asymmetries to keep in mind when reading a result. The vanilla launcher script ends in
an unconditional `exit 0`, so the exit-code check cannot fail a vanilla run — for that
condition, main-menu detection is the only real gate. And `enabled` writes a JFR recording
per run just as `agent` does, so its overhead is present in both and cancels in the
`enabled` versus `agent` comparison but not in `enabled` versus `vanilla`.

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

## Running the caches without the profile

The recorder is not free. Measured against itself on the 2026-07-31 campaign it cost about
**24% of startup** — stack-traced class loads and file reads plus 10ms execution sampling,
across the tens of thousands of classes Starsector loads. That is more than the texture
cache saves, which is why `enabled` versus `vanilla` came out near zero while `enabled`
versus `agent` came out at 15%.

So there is a launch mode that keeps the caches and skips the profile:

```bash
java -jar preflight.jar run --adapter --texture-auto --no-record
```

The adapter still runs and still writes `adapter.json`; only the JFR recording is skipped.
Recording stays **on** by default, because every analysis command in this repository reads
what it produces — `--no-record` is for launching, not for measuring. A run made this way
has no `startup.jfr` and so cannot feed `preflight benchmark collect` or the probes.
