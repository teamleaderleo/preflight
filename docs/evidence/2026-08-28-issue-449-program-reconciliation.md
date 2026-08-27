# Gameplay FPS program reconciliation against issue #449

Date: 2026-08-28

Canonical parent: [#449 — Gameplay FPS program: explain every hitch, then optimize it](https://github.com/teamleaderleo/preflight/issues/449)

Repository identities at review time:

- public `origin/main`: `afb00803002156be0c54f509f82d8bba34982eea`;
- gameplay working branch before this record: `1c7fc7e3ebc6a3958369c96d7de99f5543f95adb`;
- merge base: `9ef75b37fcf10c76676b702089787bc2f555e542`.

Status: **the working branch has materially advanced #449, but the parent is still foundationally
open**. Public `main` does not contain the branch's recent gameplay instrumentation and experiments,
so no public-main capability claim should silently inherit them.

## Sources read

The review covered #449's complete body and both existing comments, plus every issue and retained
source it links directly:

- [#251](https://github.com/teamleaderleo/preflight/issues/251), the still-open representative
  campaign entity-index comparison;
- [#882](https://github.com/teamleaderleo/preflight/issues/882), generation-bound reuse and the
  rule that derived work remains disposable;
- [#1016](https://github.com/teamleaderleo/preflight/issues/1016), the durable-input/disposable-
  acceleration north star;
- [#1072](https://github.com/teamleaderleo/preflight/issues/1072), safe true-size texture work that
  remains separate from speculative gameplay rendering changes;
- `FrameTimeRuntime`, the campaign-engine call-time evidence, and the complete experiment ledger.

No existing focused hitch-recorder, GPU-gameplay, or workload-fingerprint child issue was found.

## Current experiment folded into the program

The AI Tweaks `WeaponHandle.getLocation` successor is a useful #449 rejection, not a missing win. It
passed exact-byte correctness, ordinary 8-v-25 combat, and the symmetric 1,040-DP route. It served
8.30 million cached reads in the final stress run and reduced the reviewed
`SelectTarget -> computePosition` sampled allocation from 194.1 MiB to 2.0 MiB. The global getter
wrapper also appeared in 20/624 final combat execution samples, and the intrusively profiled frame
co-observations did not improve consistently. The only thin run was B-only. The candidate therefore
remains rejected at `574cfd3b`, with its implementation preserved at `5b6035aa` and its bounded
record at [the rejection report](2026-08-28-aitweaks-weapon-location-selection-rejected.md).

This strengthens two parent rules:

1. enormous call/allocation volume is a legitimate discovery lead;
2. removing that work is not a player-visible performance claim until a thin controlled cohort
   moves frame work or pacing under a comparable workload.

The experiment also exposed a current comparison weakness: the 1,040-DP route preserves ship count,
DP, speed, zoom, profile, runtime, display, and semantic actions, but battle evolution is not lockstep
and it has no runtime projectile/effect/workload fingerprint. That is sufficient to reject a no-win
candidate, not to advertise a positive delta.

## #449 workstream status on the working branch

| Workstream | Status | What exists | Material gap |
| --- | --- | --- | --- |
| A. Hitch flight recorder | **Partial** | Bounded worst frames, 32 longest repeated slow-frame clusters, exact epoch/offset windows, exact scenario-step correlation, and offline JFR-window attribution. Existing campaign timers retain bounded slow-call windows. | No primitive pre-trigger/post-trigger event ring, unified hitch packet, exact frame identity shared by producers, or automatically joined Java/game attribution track. |
| B. CPU/GPU/presentation | **Partial** | Exact pre-swap, native-swap, message, and other-after-swap timings; vsync request counters; phase histograms and worst frames. | Pre-swap still combines game work with the limiter, no asynchronous GPU timer exists, CPU/GPU overlap is not represented, and headroom is incomplete. |
| C. GL command/state attribution | **Not implemented** | Prior texture capability probes are separate startup/asset evidence. | No per-frame GL command counters or redundant-state observation in gameplay. |
| D. First 30 campaign seconds | **Partial** | Exact first-30/after-30 buckets and older deep campaign decomposition prove the cold period is rougher. | Current profile assumptions need a fresh cold route; no per-hitch JIT/class-load/GC/resource/GL decomposition exists. Recent exact-step work concentrated on settled paused/unpaused play. |
| E. Mod frame tax / hitch tax | **Partial** | Concrete runtime-class campaign timers, sampled method rankings, and manual mod/JAR ownership have identified real owners. Cluster overrepresentation is ranked separately from whole-step samples. | No generalized classloader/code-source/JAR-to-mod resolver and no durable separate frame-tax/hitch-tax report. |
| F. Allocation hunter | **Active but manual** | State/window-filtered JFR analysis and many exact allocation experiments, including accepted and rejected high-volume paths. | No generalized ranked output tying allocation sites to GC/hitch packets and exact owners. Discovery recordings remain intrusive. |
| G. Bytecode hot-pattern hunter | **Manual precursor only** | Exact bytecode reports and repeated manual pattern audits. | No frequency-weighted candidate scanner; no automated candidate queue. |
| H. Scenario corpus/fingerprints | **Partial** | Semantic paused/unpaused campaign, ordinary combat, and symmetric 1,040-DP simulation routes; process, save, step, DP, speed, zoom, profile, and adapter receipts. | Dense traversal, market/UI, projectile-heavy, soak, and return phases are incomplete; runtime workload fingerprints are absent. |
| I. Adaptive escalation | **Not implemented** | The investigation has followed the funnel manually. | No retained probe plan that chooses a bounded next diagnostic run from a bad thin run. |
| J/K. High-risk rendering | **Correctly deferred** | Vsync opt-in and asset-format research remain separately gated. | Do not advance cosmetic budgeting or GPU-ready gameplay claims until CPU/GPU evidence points there. |
| L. Causal loop | **Partial** | Exact identities, semantic automation, adapter health, kill switches, evidence sealing, and serious rejection records exist. | Cohort orchestration, workload comparability, and automatic explanation/next-experiment output are not yet one system. |

## Near-term acceptance against #449

- Reproducible campaign and combat routes: **partial**. Exact semantic routes and directional pairs
  exist, but repeated shuffled/interleaved cohorts are not yet a generalized gameplay command.
- Thin disabled/enabled result satisfying #251/#449: **missing**. #251 remains open; recent campaign
  and combat experiments do not substitute for its entity-index cohort.
- Bounded hitch recorder joined to an exact Java/game track: **partial, not complete**. Current
  clusters and offline correlations are strong precursors, not the requested packet primitive.
- CPU/GPU/presentation experiment: **presentation-only partial**. There is no GPU timer result or
  explicit capability-based refusal yet.
- Ranked steady and hitch contributors: **partial and diagnostic**. Current JFR/engine rankings are
  useful but not generalized ownership reports.
- Discovery versus measurement separation: **implemented as a rule and followed by the current
  rejection**. Intrusive FPS observations were explicitly not used as the claim.
- Exact-owner candidate through accept/reject: **present repeatedly**, with the current AI Tweaks
  result adding a fully retained allocation-success/gameplay-no-win rejection.
- Experiment ledger: **current through 2026-08-28**.

## Assumptions requiring fresh verification

1. The old first-30-second, Stellar Networks, MagicLib, location/economy, and fleet rankings remain
   leads, not permanent truth. Re-profile only where current hitch packets or relevant code/content
   changes justify it.
2. The current profile still has a cold-campaign penalty under the new exact semantic route. Recent
   settled-state evidence does not prove the old cold decomposition remains proportionally current.
3. The paused presentation result remains valid for the current display/vsync/cap state and does not
   establish GPU limitation. Native swap time and GPU elapsed time are different tracks.
4. A scripted 1,040-DP input sequence does not guarantee comparable simulated work. A positive combat
   claim needs at least a bounded runtime fingerprint or another proof that divergent battle work did
   not explain the delta.
5. Rosetta/JFR remains a discovery reliability risk after the reproduced HotSpot safepoint assertion.
   Thin cohorts must not depend on JFR completing successfully.
6. The working branch's gameplay capability is not yet public-main capability. Reconcile or merge it
   deliberately; do not cite `origin/main` as containing these changes.

## Highest-information next slice

Create one focused child for **bounded hitch packet v1: frame/presentation ring plus one exact
campaign-engine track**. This follows #449's intended order and turns existing pieces into the first
automatic explanation primitive.

Proposed boundary:

- a measurement-safe fixed primitive ring retains frame sequence, monotonic start/end, semantic
  state/pause eligibility, total interval, pre-swap, native swap, messages, and residual-after-swap;
- a 50 ms trigger with a distinct 100 ms severity retains about two seconds before and one second
  after, coalesces overlapping triggers, and caps packets/session;
- reporting and joins happen at telemetry/shutdown, not on the frame hot path;
- the existing exact `CampaignEngine` major-phase timer may feed a separate primitive call-span ring
  only while that **discovery-only** plan is enabled; the first slice excludes dynamic script-class
  ownership and broader mod attribution;
- every producer documents the same monotonic clock domain, while epoch time is derived only for
  presentation/report interoperability;
- focus/state transition exclusions remain identical to current frame evidence;
- report identity names threshold/window/capacity, producer configuration, adapter health, and
  measured recorder overhead;
- tests prove ring wrap, pre/post capture, overlap coalescing, bounded retention, clock joins,
  focus/transition exclusion, absent-producer behavior, and normal/exceptional shutdown reporting.

This slice deliberately does not add GPU queries, GL counters, a generalized ownership resolver, or
an optimization. Once a real bad campaign run emits a packet with presentation tracks and an exact
campaign phase overlap, the evidence can decide whether the next child should deepen Java ownership
or move to asynchronous GPU timing.

Before any positive 1,040-DP optimization claim, add the smallest exact combat workload fingerprint
needed to reject materially divergent simulated work. That is a comparison guard, not a reason to
skip the hitch recorder.
