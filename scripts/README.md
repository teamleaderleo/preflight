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
`campaign-continue-proof.json` is the shorter prerequisite: it uses only the PID-bound internal
Continue action and semantic state, reaches `campaign-ready`, and stops the exact process without
probing host desktop permissions or sending native input. It holds that same PID for ten seconds
after `campaign-ready`, so the controller cannot erase the visible transition before a campaign
frame is rendered.

`campaign-profile-current-state.json` uses the same internal Continue boundary, waits through the
30-second campaign warm-up, then retains another 60 seconds with the exact-gated campaign call-time
probes enabled. It never changes pause state: classify the run as paused or unpaused from the
retained maintenance counters and the state the selected save actually loaded into.

`campaign-owner-tax-paused-unpaused.json` is the #1158 discovery route. It combines the reviewed
deep campaign timers, owner/tax reporting, thin hitch packets, and SAMPLE JFR in one explicitly
intrusive paused/unpaused pass. `run-1158-owner-tax-discovery.sh` also builds the enabled-mod static
hot-pattern census, runtime/static triage join, and JFR hitch correlation into one run-owned bundle.
Pass `--focus nex-economy` after the broad owner funnel selects Nexerelin's exact economy-info
rebuild. That mode disables the superseded deep campaign timers, omits JFR/static triage, retains
only the exact phase/cardinality probe plus thin frame telemetry, and emits the same compact summary.
It foregrounds the exact recorded game PID before its timed windows because inactive-focus
intervals are deliberately excluded. On macOS the wrapper scopes `caffeinate` to the smoke command
so the display does not idle-sleep during a capture; it neither moves the pointer nor bypasses the
locked-console guard, and the assertion ends with the command. Its frame numbers describe discovery
overhead and are never an FPS claim. Normal launches are unchanged.

`campaign-hitch-limiter-current-state.json` keeps the same route and timing but disables the broad
campaign call-time probes. It retains only the thin frame/presentation recorder and exact campaign
FPS-limiter bracket, so a hitch packet can split known limiter sleep from the remaining pre-swap
wall time without treating an intrusive discovery profile as an FPS measurement. The presentation
recorder also reports the actual LWJGL swap-interval requests and, when the runtime already exposes
an enabled current-thread CPU clock, splits native-swap wall time into render-thread CPU and inferred
off-CPU wait. It also performs one read-only live OpenGL vendor/renderer/version and timer/fence
capability inventory before the first swap bracket; it creates no rendering state and reports
unavailable rather than inferring support. It never enables a disabled CPU clock and reports an
unavailable split instead of guessing.

For discovery-only whole-frame GPU attribution, explicitly set `PREFLIGHT_FRAME_GPU_TIMER=1` on a
frame-telemetry launch. The exact Display adapter uses a fixed 16-query `GL_EXT_timer_query` ring,
polls at most two already-available old slots per frame, declines another elapsed-query owner, and
deletes its query objects before context teardown. The live probe is materially intrusive and its
FPS values are never a performance claim. Use the paired GPU/frame/swap tracks to choose the next
boundary, then return to thin instrumentation for any optimization comparison.

For discovery-only OpenGL command-family attribution, set `PREFLIGHT_FRAME_GL_COUNTS=1` on the
paused/unpaused campaign route. Five exact LWJGL 2 classes are SHA- and method-count-gated; changed
or absent classes retain their original bytecode. The injected wrappers count bounded families such
as draw submission, texture bind/upload, fixed-function and matrix state, readback/explicit flush,
buffers, shaders/uniforms, and framebuffer binding. Counting begins only at the internal
`campaign.begin-frame-window` or `combat.begin-frame-window` action, drops the partial action frame,
and retains aggregate categories plus the 64 slowest complete frames. This is intrusive discovery
instrumentation: do not enable the GPU timer in the same run, and never use its FPS as an
optimization claim. Use the result to choose a narrower probe or candidate, then compare that
candidate with thin frame telemetry. The totals cover selected wrapper families, not every OpenGL
call; immediate-mode rendering is deliberately counted per `glBegin` batch rather than per vertex
to keep the discovery probe bounded.

If the family census exposes substantial state traffic, use the narrower
`PREFLIGHT_FRAME_GL_STATE_REISSUES=1` pass next. It composes exact argument hooks into the same
reviewed GL11/GL13 boundary and reports per-method calls plus same-state reissues for texture binds,
capability enable/disable, blend/alpha/depth/cull state, matrix mode, viewport/scissor, and texture
unit selection. Display-list calls and attribute-stack pops invalidate the model; table overflow,
unknown calls, and unexpected threads are explicit. A same-state reissue is a candidate ceiling,
not proof that suppression is safe. This is also intrusive discovery instrumentation and cannot be
combined with the GPU timer or used for an FPS claim.

`campaign-profile-paused-unpaused.json` keeps the first three campaign seconds untouched, ensures a
paused state through Starsector's mapped pause control, retains a paused warm-up and settled window,
then unpauses for a transition buffer, starts an exact campaign-owned frame window, and retains the
settled window. Continue and every
pause transition are internal PID/start-bound actions, so the route does not activate another app,
move the cursor, or depend on host window focus. It stops the exact owned process from the final
state; a late campaign interaction can legitimately make another pause action unavailable, and the
profiling run is not retained or saved.

`campaign-paused-unpaused-measurement-only.json` and
`campaign-paused-unpaused-optimized.json` are the paired FPS form of that pause cycle. The first
still launches through Preflight but admits only the measurement boundary; the second uses the
shipped fast plan scope. Their steps are otherwise identical. They foreground the exact recorded
game PID immediately before measurement because inactive-focus intervals are deliberately excluded,
then require at least 100 frames and 30 active seconds independently in both the settled paused and
settled unpaused series. They capture and stop from the unpaused state because a legitimate late
campaign interaction can make restoring pause unavailable after the measurements are already
complete. The benchmark result reports recurring-stutter and FPS deltas separately for each state;
it never folds a missing state into the aggregate campaign result.

`campaign-sample-paused-unpaused.json` runs the same internal pause cycle with a single-chunk JFR
sampling recording and without the deep campaign call-time probes. Use it to rank residual campaign
stacks after the timer probes have identified a broad category. It stops the exact owned process
after the unpaused window instead of trying to restore pause, because a late campaign interaction
may legitimately make the pause control unavailable and the process is not retained or saved.

`campaign-simulation-combat.json` is the deterministic 1× combat profile. It keeps the selected save
paused, prepares and verifies an in-memory 24-ship/crew fixture, opens Fleet → Refit → Simulation,
deploys both sides through exact stock-dialog actions, enables autopilot, closes the command map,
ensures combat is unpaused, adds bounded wheel events at the exact game input boundary, verifies a
wider public-viewport state, lets the camera settle, starts a
clean frame window, and retains a 60-second sample. `campaign-simulation-combat-speedup.json` uses the
same route but explicitly toggles the installed SpeedUp mod's Caps Lock 2× mode and labels its
30-second window separately. Neither scenario saves the in-memory fixture. Frame reports rank
repeated >33.33 ms clusters and excess slow-frame time separately from isolated hitches, so a single
menu or transition spike does not masquerade as sustained roughness.

The combat scenarios wait for the internal `main-menu-interactive` receipt before asking macOS to
foreground the exact recorded PID. LWJGL can exist as a Unix process before it has registered a
foregroundable application; attempting activation in that gap is neither a useful readiness probe
nor a reason to hold the launch for another two minutes.

`campaign-simulation-combat-1000dp.json` keeps the same proven entry route without foregrounding the
game: named actions use the closed in-game channel and its remaining macOS keystrokes are posted to
the exact recorded PID. It then replaces the deployed ships with mirrored 24-ship fast high-tech
fleets through the closed in-game runtime action. The profiled and thin variants share the same
pause-held exact camera setup, sealed frame window, and workload fingerprint; only JFR recording
differs.
Each side is 520 DP, so its retained 30-second 2× window is a controlled 1,040-DP combat stress
workload rather than the stock dialog's fleet-order-biased selection. The thin comparison route
re-pauses after autopilot, command-map, and speed setup, holds that state through verified camera
setup, and unpauses only immediately before the measurement window. It never saves the fixture.
The `-thin` variant keeps those semantic phases and disables JFR recording. It centers the LWJGL
cursor once, then uses the public viewport's external-control mode to hold the combat camera at the
battlefield origin and an exact 4.000 multiplier instead of relying on timing-sensitive wheel easing
or the physical cursor position. The smoke-only combat-frame exit hook reasserts the full externally
controlled viewport rectangle after Starsector updates the camera; both comparison arms pay the
same hook cost. Its
verification step rejects a run if external control, center, or settled zoom differs, because
Starsector's cursor-following combat camera otherwise changes the rendered workload. Use it for
controlled FPS comparisons after the profiled scenario has identified a candidate. Profiler FPS
belongs to discovery evidence, not to an optimization claim.

The thin route also brackets the timer with `combat.begin-frame-window` and
`combat.end-frame-window`. Immediately outside that window it records a semantic workload
fingerprint: elapsed combat time, live non-fighters/fighters and hulks by side, aggregate live hull
and flux, plus projectile and missile counts. A candidate can therefore be rejected when the same
recipe and camera evolved into materially different combat work. The two snapshots are comparison
guards, not hot-path probes, and their reflection/allocation cost is excluded from the FPS window.

## Read what a launch produced

| | |
|---|---|
| `summarize_startup_probe.py` | Summarise one `--startup-phase-probe` run into where the launch spent its time. |
| `summarize_combat_cohort.py RUN...` | Compact thin-combat A/B table with tail metrics, causal counters, and identity/workload/adapter gates. Prefers the final shutdown report over the mid-run artifact copy. |
| `startup_scorecard.py` | The Preflight startup record and the measured per-component scorecard. |
| `starsector_log_load_times.py` | Recover every launch's load time from Starsector's own logs, with no harness involved. Works on runs nobody instrumented. |
| `starsector_log_ready_detector.py` | Snapshot, delta, classify and detect readiness in the game's log. This is what makes `--unattended` possible; run it directly when you are debugging why a launch was not detected as finished. |
| `starsector_benchmark_report.py` | Live progress and final statistics for a benchmark campaign. |
| `starsector_critical_path.py` | Where the load's wall clock goes, as opposed to where its CPU goes. Reach for it when the profile says "blocked" and you need to know on what. |
| `starsector_gameplay_hotspots.py` | Rank sampled gameplay stacks, campaign and combat scored separately. Add `--allocations` to rank JFR's weighted allocation estimates by object class, leaf, and first non-JDK owner; `--contains` also attributes filtered CPU or allocation weight to immediate callers and the calling methods above the filter. In either mode, repeat `--step paused-settled --step unpaused-settled` to use exact scenario receipt windows and exclude setup churn. For startup stalls, pass `--frame-report runtime-frame-report.json --include-other`; it defaults to the exact worst `allActive` frame, or repeat `--frame-series NAME` to select other report series. Add `--repeated-clusters N` to aggregate the N longest exact repeated-slow-frame windows for each selected series. For a workload whose ordinary frame time already exceeds the slow-cluster threshold, use `--hitch-frame-millis 100` instead: it reads the recorder's complete retained severe-frame population, deduplicates overlapping packets, and groups only consecutive qualifying frames. Combine either mode with `--step` to intersect rather than union the state bucket and exact scenario receipt; an empty intersection fails closed. For execution samples, add `--cluster-enrichment` to compare those exact windows with the non-window remainder of the same step and rank methods by excess presence rather than unstable rare-event lift. Each row also reports distinct-window breadth, keeping a single long hitch visibly separate from work recurring across many hitches. |
| `starsector_slow_span_frames.py` | Join bounded runtime `slowSpans` to the exact retained worst-frame intervals in a selected frame series. It reports only real interval overlap in the shared epoch clock, keeps nested spans visibly non-additive, and treats spans outside the bounded worst-frame population as unclassified rather than as a negative result. |
| `starsector_campaign_cluster_calls.py` | Correlate the deep opt-in campaign timers' bounded >=1ms call windows with exact repeated slow-frame clusters. Add `--scenario-evidence smoke-evidence.json --step unpaused-settled` to intersect the state bucket with exact scenario time instead of mixing the post-unpause transition into the settled route. Rows are inclusive and nested, so their overlap milliseconds rank likely owners but must never be summed as additive CPU time. |
| `starsector_balance_analysis.py --output benchmark-results/balance/NAME` | Build a local paper-balance database from core plus the exact enabled-mod order. Materializes hull skins, separates burst/sustained/PD weapon proxies, and emits hull, weapon, fitted-variant, and ship-system evidence tables alongside multiple weight profiles, rank stability, comparable-peer Pareto dominance, provenance, and data-quality coverage. Full derived rows stay in ignored operator evidence; the script never edits or copies installed content. See the [model and limits](../docs/balance-analysis.md) and [2026-08-27 audit](../docs/reports/balance-audit-2026-08-27.md). |

## Verify before you push

| | |
|---|---|
| `verify-all.sh` | Everything the repository owns: Java reactor, desktop dependencies, packaged-engine contract, frontend, native host, and the report-intake worker and its bindings. |
| `verify-in-container.sh [full\|focused\|analysis\|coverage\|package]` | The same work inside a memory-, CPU- and PID-limited Linux container. **This is how to reproduce a Linux-only failure from a Mac.** |

## Keep local worktrees bounded

| | |
|---|---|
| `prune_local_build_outputs.py` | Preview rebuildable Maven, Rust, frontend, UI-matrix, package, probe-kit, Wrangler-state, repository-local prepared-cache, JFR, generated native-metadata/icon, operator-script bytecode, and duplicate dependency outputs across this repository's registered worktrees. Each output has its own age, so a fresh small cache cannot keep an unrelated old binary tree past the 48-hour limit. Dependencies remain available in the current worktree; sibling dependency trees follow the same 8-hour floor and 48-hour hard limit as other outputs. Older outputs are removed unless `--keep-completed` explicitly reserves their clean worktree. Old generated output may be removed from dirty worktrees without touching source changes. After committing a verified wave, `--retire-current` can include that clean worktree's build outputs immediately. Pass `--apply` only after reviewing the plan. |

Run this after an experiment or review wave finishes. Exact release evidence belongs in its reviewed
artifact/evidence location; an old `target/` or `desktop-dist/` directory is not durable evidence.
The ignored `benchmark-results/` subtree remains operator-owned evidence and is excluded even when
it contains a JFR file that would otherwise match the generated-recording rule.
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
