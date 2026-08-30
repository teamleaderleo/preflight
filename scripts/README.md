# Scripts

Check this index before writing a new script or driving the game by hand.

## Synchronize repeated project facts

| | |
|---|---|
| `sync_project_facts.py --write` | Read `docs/project-facts.json`, derive the startup speedup/savings, and update the managed public-copy and claim surfaces. Edit the facts file first; do not hunt through the repository replacing the same number by hand. |
| `sync_project_facts.py --check` | Verify that the managed copy still agrees with `docs/project-facts.json`. CI uses this mode. |

Historical measurements and raw evidence stay hand-authored. The sync owns selected current facts and
the places that repeat them; it does not rewrite old experiment records.

For a startup time, run:

```bash
scripts/benchmark-startup.sh
```

It launches once, reads the game's exact main-menu marker, prints the time, and stops the game.
`preflight run` only launches the game. It never stops it.

## Launch the game and find out where the time went

| | |
|---|---|
| `benchmark-startup.sh [--game DIR] [--engine PATH] [--cache PATH]` | One automatic `fast` launch to the exact main-menu marker. Prints one number and stops the game. It follows the current profile into Compact after that profile has learned a launch; a new profile starts with Balanced. **This is the default.** |
| `benchmark-startup.sh --details [--mode NAME]` | One automatic launch plus startup phases and mod callback timings. Use it when the number moved and you want to know where. |
| `benchmark-startup.sh --campaign [OPTIONS]` | The repeated comparison harness: shuffled, resumable, and pinned to a measurement identity. Use it for a release claim or an A/B comparison. `--cache PATH --texture-storage compact` measures an explicitly prepared Compact cache. |

Both diagnostic modes use the same condition names:

| mode / condition | what it launches | when you want it |
|---|---|---|
| `fast` | `--fast`, the shipped preset an installed launcher runs | where a real user's time goes |
| `enabled` | `--adapter --texture-auto` | the prepared texture path |
| `adapter` | `--adapter` alone | the least-optimized launch a **probe** can measure |
| `prepared` | `enabled` plus prepared pixels, padding retained | prepared-pixel comparisons |
| `vanilla` | the game's own launcher, no Preflight | the baseline, campaign only |

The phase probe requires the adapter, so it cannot measure vanilla. Compare vanilla and Preflight
with a campaign:

```bash
scripts/benchmark-startup.sh --campaign --unattended --conditions vanilla,fast
```

Campaigns shuffle conditions and can cool between launches to avoid assigning thermal drift to one
condition.

Set `PREFLIGHT_FULL_EVIDENCE=1` only when every per-seam contract report is needed. Normal runs keep
the reports that carry a finding and omit redundant success receipts.

Launch claims and their exact conditions live under `docs/evidence/`. Preparation time is separate
from launch time. Phase-probe time also uses a different clock and cannot be compared directly.
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
| `java-dev.py test MODULE CLASS[#METHOD]` | One exact JUnit test plus required reactor parents. Modules use the short names `core`, `agent`, `cli`, and `synthetic`. |
| `java-dev.py test MODULE CLASS[#METHOD] --reuse` | Opt in to selector/toolchain/policy-partitioned exact-result reuse. The receipt distinguishes current execution from reuse. |
| `java-dev.py it CLASS[#METHOD]` | One exact packaged child-JVM test in `preflight-cli`, without replaying the ordinary unit-test inventory. |
| `java-dev.py module MODULE` | One module only. This expects its reactor dependencies to be available already. |
| `java-dev.py deps MODULE` | One module plus required reactor parents: the reliable routine-edit default. |
| `java-dev.py full [--threads N] [--forks N]` | The full Java integration oracle. Parallelism is explicit and opt-in; it is not a laptop default. |

Routine Java correctness belongs to `mvn verify`. Focused JUnit tests stay in the normal reactor
unless they require a genuinely different OS, package, operator, or stress environment. Medium
synthetic workloads live in the dispatch-only `Synthetic stress` workflow.

### Opt-in exact-result reuse

`--reuse` is deliberately narrower than an ordinary Surefire selector. It accepts one literal Java
class or `Class#method`; lists, wildcards, parameter patterns, and other expressions are refused.
The helper runs `clean verify`, derives a namespace from the module, selector, command, Maven
wrapper/configuration, JDK, platform, and the complete environment Maven may observe (values are
hashed, never printed). The helper-only cache-location override and its replaced Maven base are not
forwarded. The namespace also binds cache policy, then the extension adds its source/project/plugin
content checksum. A relevant source or environment edit therefore misses and executes inside the
same semantic namespace.

The pinned Apache extension is loaded through a temporary external Maven base only for that command.
The repository does not carry `.mvn/extensions.xml`, and ordinary Maven or `java-dev.py` commands do
not pay extension startup/logging cost. The first opt-in command may resolve the extension into the
normal Maven artifact repository. The result cache is local-only, restores compiled main/test
classes but not a historical Surefire report, and keeps at most two source generations per project
and semantic namespace. Set `PREFLIGHT_JAVA_DEV_CACHE` to an absolute route-private directory for a
disposable experiment.

After Maven exits, the helper accepts only one of two evidence shapes:

- `executed`: the exact requested Surefire XML report exists after mandatory `clean`;
- `reused`: the requested module has exact cache-restore and cached-Surefire-skip markers, with no
  requested report.

A successful Maven exit with neither shape is refused. A reuse receipt is focused feedback, not a
claim that the test ran now and not a substitute for `java-dev.py full` when the integration oracle
is required.

## Guards that fail closed

Each of these is a refusal, not a report: they exist to stop something reaching a release.

| | |
|---|---|
| `verify_current_source_boundary.py` | Cheap development guard over the currently tracked tree. PRs use this so accidental game/mod/private content is rejected without replaying all Git history. |
| `verify_source_boundary.py` | Full tracked-tree and reachable-history audit. Distribution/release uses this stronger mode from a complete checkout. |
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
| `preflight-desktop/scripts/exercise-package-lifecycle.mjs OLDER NEWER` | Install a package, upgrade it, roll it back, and remove it. Dispatch the `Package lifecycle` workflow rather than running this by hand — it needs two built versions. See [the rehearsal](../docs/package-lifecycle-rehearsal.md). |

## Disposable container verification

| | |
|---|---|
| `verify-in-container.sh [SUITE]` | Run a bounded Maven suite in a local rootless Podman container. |

## Prepared-pixel probes

Opt-in, historical, and narrow: they exist to reproduce the prepared-texture campaign rather than to
answer a question about today's build. Start from `docs/prepared-textures.md` before running one.

`run-prepared-pixel-layout-probe.sh`, `run-prepared-pixel-coherent-direct-probe.sh`,
`run-prepared-pixel-coherent-converter-probe.sh`,
`run-prepared-pixel-coherent-direct-gameplay-smoke.sh`,
`run-prepared-pixel-main-menu-comparison-pilot.sh`
