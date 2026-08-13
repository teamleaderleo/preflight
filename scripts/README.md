# What is in here, and when you would reach for it

Every entry says what it does and why you would run it. Read this before writing a new script or
driving the game by hand — most of what you want already exists, and the two traps below have cost
real time.

**Trap 1: `preflight run` is the launcher, not a measurement.** It starts Starsector and stays
attached until the game exits. It has no auto-stop, so running it to "get a number" leaves a ~4 GB
JVM and a GPU context alive until someone kills it, and every measurement after that is poisoned.
Use `probe-launch.sh` for one number or `run-startup-benchmark.sh` for a claim; both stop the game
themselves.

**Trap 2: the game is a grandchild of the wrapper.** Killing the wrapper's direct children never
reaches it. The scripts here stop it from an `EXIT` trap so it dies on success, failure, timeout and
Ctrl-C alike. If you start the game yourself, you are responsible for stopping it yourself.

Each `foo.py` here has a `test_foo.py` beside it; they run under the repository's Python test suite.

## Launch the game and find out where the time went

| | |
|---|---|
| `probe-launch.sh [--label NAME] [--game DIR] [--timeout-seconds N] [-- FLAGS]` | One direct launch with `--startup-phase-probe`, run to the main-menu marker, stopped, and printed as a phase table plus a per-plugin callback table. **The default choice when you want to know where startup time goes right now.** |
| `run-startup-benchmark.sh --unattended` | The repeated campaign: conditions shuffled inside each round, resumable, and the report refuses a result below five runs per condition. Slow and unattended. **Use this to prove a change moved something**, not to look around. |
| `run-gameplay-pilot.sh [--game DIR] [--label NAME]` | One combat pilot with every beta probe on. Needs a human: load a campaign, open a simulation, raise the DP cap, deploy capitals, fight three to five minutes, exit normally. Reports which exact adapters applied and what their paths cost. |

## Read what a launch produced

| | |
|---|---|
| `summarize_startup_probe.py` | Summarise one `--startup-phase-probe` run into where the launch spent its time. |
| `startup_scorecard.py` | The Preflight startup record and the measured per-component scorecard. |
| `starsector_log_load_times.py` | Recover every launch's load time from Starsector's own logs, with no harness involved. Works on runs nobody instrumented. |
| `starsector_log_ready_detector.py` | Snapshot, delta, classify and detect readiness in the game's log. This is what makes `--unattended` possible; run it directly when you are debugging why a launch was not detected as finished. |
| `starsector_benchmark_report.py` | Live progress and final statistics for a benchmark campaign. |
| `starsector_critical_path.py` | Where the load's wall clock goes, as opposed to where its CPU goes. Reach for it when the profile says "blocked" and you need to know on what. |
| `starsector_gameplay_hotspots.py` | Rank sampled gameplay stacks, campaign and combat scored separately. |

## Verify before you push

| | |
|---|---|
| `verify-all.sh` | Everything the repository owns: Java reactor, desktop dependencies, packaged-engine contract, frontend, native host, and the report-intake worker and its bindings. |
| `verify-in-container.sh [full\|focused\|analysis\|coverage\|package]` | The same work inside a memory-, CPU- and PID-limited Linux container. **This is how to reproduce a Linux-only failure from a Mac.** |

## Guards that fail closed

Each of these is a refusal, not a report: they exist to stop something reaching a release.

| | |
|---|---|
| `verify_source_boundary.py` | Audits tracked files *and reachable Git history* for private or game content. Nothing from the installation may enter the repository. |
| `verify_release_boundary.py` | Fails when a core release archive holds an unexpected file or JAR namespace. |
| `verify_complete_release.py` | Fails when the final merged release differs from the reviewed artifact set. |
| `starsector_core_resource_guard.py` | Discovers and validates the reviewed core mission resource root. |
| `starsector_profile_guard.py` | Guards immutable profile inputs and bounded GraphicsLib runtime state, so a measurement cannot silently change what it measures. |

## Release plumbing

| | |
|---|---|
| `generate-release-sboms.sh OUTPUT_DIR` | Write the release SBOMs. |
| `assemble-core-release.sh OUTPUT_DIR SBOM_DIR` | Assemble the core release from a build and its SBOMs. |
| `download-private-candidate.sh RUN_ID [DEST]` | Fetch a private signed candidate produced by a workflow run. |

## Self-hosted runner

| | |
|---|---|
| `bootstrap-vps-runner.sh` | Provision the VPS verification runner. |
| `configure-vps-runner-service.sh` | Install the runner's service delegation. |

## Prepared-pixel probes

Opt-in, historical, and narrow: they exist to reproduce the prepared-texture campaign rather than to
answer a question about today's build. Start from `docs/prepared-textures.md` before running one.

`run-prepared-pixel-layout-probe.sh`, `run-prepared-pixel-coherent-direct-probe.sh`,
`run-prepared-pixel-coherent-converter-probe.sh`,
`run-prepared-pixel-coherent-direct-gameplay-smoke.sh`,
`run-prepared-pixel-main-menu-comparison-pilot.sh`
