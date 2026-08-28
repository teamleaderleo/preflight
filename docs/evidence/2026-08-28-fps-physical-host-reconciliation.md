# Gameplay FPS physical-host reconciliation

Date: 2026-08-28

Canonical parent: #449. Coordination map: #1152. Child lanes: #1153–#1158.

This checkpoint reconciles live GitHub state, `codex/quality-grind`, the installed-machine results,
and the physical-host handoff. It is intentionally a restart map: explored areas remain revisitable
when a new workload or mechanism changes the evidence, but a large counter alone is not permission
to rerun a settled candidate.

## Settled installed-host results

- **#1155 combat scaling:** three shuffled 260/520/780/1,040-DP ladders found increasing but
  predominantly diffuse AI/entity/ordnance cost. The best nonlinear model improved blocked RMSE by
  less than one percent over a simple linear model, so no stable cliff or candidate was promoted.
  Retained on `codex/1155-installed-scaling` at `4861a6ea`.
- **#1157 precision limiter:** rejected at 60 Hz. It installed cleanly but added only mixed roughly
  1–2% historical-context tail movement while average FPS fell 2.6%; telemetry exposed deadline
  extension plus scheduler overshoot. VSync-off itself remains the large accepted experimental win.
  Retained on `codex/1157-installed-pacing` at `c1b8c40c`.
- **#1153 vanilla particle wrapper:** discovery lead, not a candidate. The exact 1,040-DP window
  recorded 44,992 calls and 586.879 ms inclusive, about 0.496 ms/frame and 1.95% of active wall.
  Actual drawing is downstream, so the next render question is a bounded downstream submission
  census. Retained on `codex/1153-physical` at `8146c5dc`.
- **#1153 GraphicsLib tessellation array:** rejected for the current profile/routes. The exact
  transform installed and passed ordinary visual/correctness plus 1,040-DP health gates, but both
  candidate routes reported zero batches, vertices, buffer growths, and calls avoided. Retained on
  `codex/1153-tess-array-physical` at `d528366b`.
- **Earlier carrier candidates:** AI Tweaks weapon-location caching, texture-bind deduplication,
  matrix identity elision, and the current precision waiter are useful rejected experiments. Do not
  revive them unchanged because their work-reduction counters were large.

## Live lane map

The following state was refreshed from GitHub on 2026-08-28. Branch counts are relative to the live
`origin/codex/quality-grind` head `f20a1b66` and may change.

| lane | live implementation | physical-host state | action |
| --- | --- | --- | --- |
| #1153 / draft #1163 | `lantern/1153-render-sync` (`3b868b91`), long-diverged from `main`; frame-sync, tess array/packed, particle probe, guarded GL-state cache | precision/frame-sync successor and base tess array are settled; particle wrapper is material; packed replay inherits the unexercised base path in current routes | pursue downstream particle census before batching; count guarded `glIsEnabled` traffic before any A/B |
| #1154 / PR #1159 | `switchboard/1154-hitch-classifier` (`781b852b`), 23 commits ahead and 7 behind the carrier | report-time classifier and JFR attribution exist; current PR checks include carrier coverage/release-contract failures | reconcile onto the current carrier and verify, but it does not require a new game run merely to classify retained reports |
| #1155 / draft #1162 | `research/1155-combat-scaling` (`6279a5a0`) plus installed continuation `codex/1155-installed-scaling` (`4861a6ea`) | real coefficients complete for the bounded DP ladder | keep broader fighter/effect/wreck dimensions open only when a concrete question needs them |
| #1156 | `codex/prism-1156-gpu` (`d0a3cd02`) is already an ancestor of the carrier | async GPU timing and GL attribution are live; resource/sync questions remain | use a bounded resource/synchronization diagnostic only when bad-frame evidence points there |
| #1157 / draft #1164 | PR branch `metronome/1157-cap-vsync` (`4626e369`); installed rejection branch above | current 60-Hz precision candidate settled negative | no unchanged rerun; high-refresh or new deadline semantics is a different experiment |
| #1158 / draft #1160 | `toolbox/1158-runtime-attribution` (`a3ff83d5`), 37 commits ahead and 10 behind the carrier | implementation and synthetic gates exist; no current installed owner-tax discovery result | highest-information next physical-host slice after reconciliation |

PR #1159 and #1160 currently show coverage/release-contract failures alongside many green focused
checks. Treat those as integration debt requiring fresh verification on the current carrier, not as
evidence that the classifier or attribution model is wrong. PR #1162 and #1163 are green but target
`main` and carry long, partly superseded experiment histories; use patch/behavior reconciliation
instead of merging them wholesale into a moving gameplay carrier.

## Assumptions that need fresh verification

1. **Runtime owner tax on the real profile.** Historical owners are concrete leads, but current
   CodeSource-to-mod attribution, unresolved/dynamic counts, steady tax, and >50/>100 ms hitch tax
   have not been measured together on the installed machine.
2. **JFR correlation after owner-tax integration.** GC/JIT/safepoint/blocking attribution should be
   run only against retained hitch windows. Existing G1 plus deferred heap commit is already a
   startup win; collector changes do not advance without gameplay-tail evidence.
3. **Downstream particle submission.** The wrapper's 1.95% inclusive stress share is an upper bound.
   Draw mode, texture, blend, layer, owner, batch compatibility, and probe overhead remain unknown.
4. **GL synchronization frequency.** The broad queue topology is understood well enough for
   synthetic work, but real getter/readback/sync frequency still needs calibration before any
   render-thread bridge model can claim relevance.
5. **Guarded GL state queries.** A source-safe `glIsEnabled` cache exists on #1163, but it should get
   a causal count/materiality check before a performance cohort. The current tessellation zero-work
   result is a warning against assuming an implemented branch is exercised.

## Highest-information next slice

Reconcile #1160's runtime-owner attribution onto the current physical-host carrier and run one
discovery-only ordinary campaign/combat route with the existing hitch packets and thin frame
recorder. Preserve:

- top core/mod steady frame tax and retained >50/>100 ms hitch tax;
- unresolved, directory-only, and dynamic-Janino ownership separately;
- runtime/static hot-pattern intersections;
- exact game/profile/save/runtime/display/adapter identity;
- workload fingerprint and semantic phases;
- JFR configuration and observer cost;
- adapter health, declines, fallbacks, and kill switch.

If SAMPLE JFR leaves a retained pre-swap family unexplained, one FULL repeat may answer the narrow
JIT/class-load question. Otherwise do not spend another game launch. The output should select one
exact owner for a focused child experiment or show that the tax remains diffuse.

After that, compare its information value with the downstream particle census. The former can rank
the whole live callback surface; the latter has a measured ~2% wrapper upper bound and is the better
render-specific continuation. Neither requires changing normal Preflight behavior: both remain
opt-in unattended probes with exact target gates.

## FAQ / reopen conditions

**Does a rejected seam spend an entire domain?** No. It spends the exact candidate under the named
workloads. New nonzero causal traffic, a materially different workload, or a changed mechanism can
reopen it.

**Why skip a baseline stress run for the tessellation array candidate?** Its own counter was zero in
ordinary combat and the exact stress fixture. A baseline can measure machine variance but cannot
attribute any difference to a candidate that removed no work.

**Are 1% lows the only target?** No. Retain p50/p95/p99/p99.9, maximum, severe-frame rates and
clusters, active time, presentation/GPU/CPU decomposition, and workload identity. Tail metrics decide
tail-focused candidates; average FPS remains context.

**Will the probes make normal use cumbersome?** No. Experiment switches are off by default. The
physical runners validate exact installed archives, drive one owned process unattended, write
bounded reports, and leave ordinary GUI/CLI launches unchanged.
