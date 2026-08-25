# Scripts

Check this index before writing a new script or driving the game by hand.

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

## Exercise campaign, combat, and save lifecycle

| | |
|---|---|
| `run-gameplay-pilot.sh --disposable-save DIRECTORY [--game DIR] [--label NAME]` | One campaign/save/combat pilot with every beta probe on. Needs a human and a named disposable save copy: roam through warm-up and steady state, fight a three-to-five-minute simulation, save, return to the title screen, reload, resume play, and exit normally. Exact-content snapshots require that the selected copy changed and every sibling campaign stayed unchanged. The create-once operator attestation binds that result to the exact snapshots, engine JAR, run and mod-profile reports, adapter/health evidence, clean source state, probe configuration, and completed process outcome. With the adapter enabled, completion requires at least 100 frames in each phase plus 20 seconds of active campaign warm-up, 30 seconds of settled campaign, and three minutes of post-campaign combat. The final typed confirmation covers the whole route and save lifecycle; it cannot replace telemetry. The pilot refuses to run without its process-inspection tools, rejects already-running instances only under the exact selected game directory, and stops only a child process with the PID and start time it recorded. Reports which exact adapters applied and what their paths cost. |
| `save_state_guard.py` | Exact-content before/after guard and evidence binder used by the gameplay pilot. It hashes every `save_*` campaign without following symlinks, excludes global `saves/common` mod state, accepts only a changed selected copy with byte-identical siblings, and does not edit the saves it checks. Evidence files are read through stable regular-file identities and bounded byte ceilings before their exact bytes are hashed into the operator attestation. It rejects mixed engine/run identities and disagreement between the adapter and its health verdict. |

The checked-in `campaign-roam-measurement-only.json` and `campaign-roam.json` scenarios are the
paired developer FPS route. Both launch through Preflight with the same interaction sequence; the
first retains only the state/frame measurement boundary and the second enables the reviewed fixes.
The coordinator now compares only the period after the first 30 seconds and refuses either phase
unless that settled period contains at least 100 frames and 30 seconds of active frame time. Select
a disposable campaign before running it: Continue and movement can still trigger a mod autosave.

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

## Keep local worktrees bounded

| | |
|---|---|
| `prune_local_build_outputs.py` | Preview rebuildable Maven, Rust, frontend, UI-matrix, package, probe-kit, Wrangler-state, generated native-metadata/icon, operator-script bytecode, and duplicate dependency outputs across this repository's registered worktrees. Each output has its own age, so a fresh small cache cannot keep an unrelated old binary tree past the 48-hour limit. Dependencies remain available in the current worktree; sibling dependency trees follow the same 8-hour floor and 48-hour hard limit as other outputs. Older outputs are removed unless `--keep-completed` explicitly reserves their clean worktree. Old generated output may be removed from dirty worktrees without touching source changes. After committing a verified wave, `--retire-current` can include that clean worktree's build outputs immediately. Pass `--apply` only after reviewing the plan. |

Run this after an experiment or review wave finishes. Exact release evidence belongs in its reviewed
artifact/evidence location; an old `target/` or `desktop-dist/` directory is not durable evidence.
`--retire-current` bypasses the age and newest-build-set floors only for the current worktree, and
refuses to act there while Git reports source changes.

## Guards that fail closed

Each of these is a refusal, not a report: they exist to stop something reaching a release.

| | |
|---|---|
| `verify_source_boundary.py` | Audits tracked files *and reachable Git history* for private or game content. Nothing from the installation may enter the repository. |
| `verify_release_boundary.py` | Fails when a core release archive holds an unexpected file or JAR namespace. |
| `verify_complete_release.py` | Fails when the final merged release differs from the reviewed artifact set, when platform capability receipts disagree, or when their product/engine identity differs from the updater feed and standalone JAR. |
| `starsector_core_resource_guard.py` | Discovers and validates the reviewed core mission resource root. |
| `starsector_profile_guard.py` | Guards immutable profile inputs and bounded GraphicsLib runtime state, so a measurement cannot silently change what it measures. |

## Release plumbing

| | |
|---|---|
| `generate-release-sboms.sh OUTPUT_DIR` | Write the release SBOMs. |
| `assemble-core-release.sh OUTPUT_DIR SBOM_DIR` | Assemble the core release from a build and its SBOMs. |
| `download-private-candidate.sh RUN_ID [DEST]` | Fetch a private signed candidate produced by a workflow run. |
| `preflight-desktop/scripts/exercise-package-lifecycle.mjs OLDER NEWER` | Install a package, upgrade it, roll it back, and remove it. Dispatch the `Package lifecycle` workflow rather than running this by hand — it needs two built versions. See [the rehearsal](../docs/package-lifecycle-rehearsal.md). |

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
