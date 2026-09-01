# Installed tactical fleet-AI tax

Date: 2026-08-28

Issues: #1158, #449. Branch: `codex/1158-physical` at `9fda91e3`.

Status: **successful intrusive decomposition; no FPS optimization claim**.

Successor status: the bounded decision census completed and selected lazy fleet inflation rather
than the repository scan or strength loop. Continue at
[installed lazy fleet-inflation hitches](2026-08-28-installed-lazy-fleet-inflation-hitches.md).

The prior exact `ModularFleetAI.advance(float)` pass selected vanilla tactical AI as a concrete
campaign hitch owner. This successor preserves `TacticalModule.advance(float)` and times six
existing semantic regions: every-frame work, avoid-list update, location fleet-list acquisition,
the other-fleet scan, encounter-option selection, and post-scan work. It retains at most 32 spans
over 2 ms with per-run tactical-AI identity. The plan is opt-in, exact-class-hash gated, fails back
to original bytecode, and has the kill switch
`preflight.campaign.tacticalFleetAiTimes.disabled=true`.

## Result

| region | calls | total | average | maximum | >16 ms | >33 ms | >50 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| every-frame | 16,427 | 126.424 ms | 7.696 us | 5.509 ms | 0 | 0 | 0 |
| avoid list | 2,000 | 150.225 ms | 75.113 us | 20.170 ms | 1 | 0 | 0 |
| fleet-list acquisition | 1,901 | 1.124 ms | 0.591 us | 0.186 ms | 0 | 0 | 0 |
| **other-fleet scan** | **1,998** | **494.738 ms** | **247.617 us** | **129.012 ms** | **4** | **2** | **1** |
| encounter option | 1,516 | 87.618 ms | 57.796 us | 9.753 ms | 0 | 0 | 0 |
| post-scan | 16,510 | 19.227 ms | 1.165 us | 0.997 ms | 0 | 0 | 0 |

`encounterOption` is nested inside `otherFleets`; these rows are not additive. Even after removing
that nested timer, the other-fleet region retains about 407.120 ms. The location repository lookup
itself is decisively immaterial, so caching or replacing `ObjectRepository.getList(CampaignFleet)`
is not supported by this run.

## Exact frame joins

The largest 129.012 ms other-fleet span occurred during the deliberately excluded five-second
unpause transition. It is correctness/usefulness evidence for the timer, not measurement-window
performance evidence.

The clean unpaused measurement window retained three material other-fleet joins:

| exact span | containing retained frame | span share |
| --- | ---: | ---: |
| other fleets 36.219 ms | 58.436 ms | 62.0% |
| other fleets 32.413 ms | 50.348 ms | 64.4% |
| other fleets 23.846 ms | 48.576 ms | 49.1% |

A later 9.775 ms other-fleet span contained 9.753 ms of encounter-option selection and occupied
29.2% of a 33.425 ms frame. That one call demonstrates that encounter selection can dominate an
individual scan, but its 9.753 ms maximum does not explain the larger 36/32/24 ms scan spans.

The measurement window retained 1,441 frames / 29.334 active seconds: p50 16.9 ms, p95 39.2 ms,
p99 65.8 ms, 1% low 15.20 FPS, 40 frames over 50 ms, four over 100 ms, and 22 repeated slow-frame
clusters. The same route and 83-mod profile were used, but this campaign evolved into a harsher
workload than the prior module pass. Those FPS values are discovery context, not a baseline/candidate
comparison.

## Correctness and observer health

The first installed attempt safely retained original bytes because the new direct plan was absent
from the transformation-availability registry. Commit `9fda91e3` adds that production gate and a
regression test. No partially transformed class reached the game.

The corrected semantic route passed every step, observed the save paused, kept it paused through
warmup and settled measurement, verified unpause, excluded five transition seconds, then started
the exact window. The harness owned and stopped its exact process. Adapter status remained `ACTIVE`
with 75 reviewed transformations and zero unavailable plans, declines, source-binding rejects,
contained failures, or runtime-integrity failures. The tactical plan reported installed and its kill
switch remained off. GC overlapped none of the retained hitch frames.

The display-boundary hook averaged 29.35 us and reached 11.745 ms once. Tactical and owner timing
overhead is not independently subtracted, and SAMPLE JFR plus the broader owner suite remained
active. This result selects a target; it cannot support an FPS improvement claim.

## Decision

Do not optimize fleet-list acquisition, every-frame, or post-scan work from this evidence. Do not
cache encounter decisions broadly: their semantics depend on hostility, transponder, battle-join,
fleet condition, and interval state.

The other-fleet loop remains worth one narrower discovery pass because it repeatedly occupies about
half to two-thirds of real bad frames. Use the exact existing `Checking visibility level` profiler
boundary plus bounded counts/timers around the loop's semantic decision helpers. At minimum retain
candidate fleets visited, visibility work, pursuit eligibility, battle-join/nearby-fleet decisions,
encounter selection, and declines. If that pass is diffuse, stop this seam and return to the
downstream particle or thin GL-synchronization lanes. Any optimization must preserve target choice,
hostility/transponder behavior, battle participation, avoidance, and cadence.

Compact retained data is in
[`data/2026-08-28-installed-tactical-fleet-ai-tax.json`](data/2026-08-28-installed-tactical-fleet-ai-tax.json).
Raw logs, JFR, and run directories remain disposable local evidence.
