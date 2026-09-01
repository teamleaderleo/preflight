# Installed lazy fleet-inflation hitches

Date: 2026-08-28

Issues: #1158, #449. Branch: `codex/1158-physical` at `05000599`.

Status: **successful intrusive decomposition; selected optimization lead; no FPS claim**.

The preceding exact `TacticalModule.advance(float)` pass selected the interval-gated other-fleet
scan. This successor counted its decisions, then timed the exact installed
`hasEnoughStuffAround(...)` dependencies: fleet inflation, location-list acquisition, and concrete
fleet-strength computation. All wrappers preserve the original call and return value. The plan is
opt-in, requires the reviewed Java 17 class hash, returns the original bytes on any shape mismatch,
and retains the kill switch `preflight.campaign.tacticalFleetAiTimes.disabled=true`.

## Workload discipline

Three Preflight-only runs used the same installed 0.98a-RC8 game, 83-mod profile fingerprint
`2995668308ac3d31d645ccac30fb1a7e644e64fce5609050a1488df4cadc5af6`, 1440x932 window,
recommended plan, Apple M5 OpenGL 2.1-over-Metal adapter, final swap interval one, and internal
PID-bound Continue/pause controls.

- `issue-1158-nearby-helper-r1-20260828-123116` passed, but the current-state route loaded paused
  and never exercised the selected helper. Its zero calls are a workload refusal, not a negative
  result.
- `issue-1158-nearby-helper-r2-20260828-123406` used the semantic paused-to-unpaused route. It
  passed all ten steps, retained the paused and active windows, and stopped its exact process.
- `issue-1158-inflation-sample-r1-20260828-124007` repeated that route with SAMPLE JFR and the deep
  campaign timers disabled. It is supporting discovery evidence only.

No comparison below crosses those runs. Campaign evolution and the timing of lazy inflation were
not lockstep, so their FPS values are not an A/B result.

## Decision census and selected operation

The exact decision run visited 15,692 candidate fleets and declined 13,891 before encounter-option
selection. Across all `hasEnoughStuffAround(...)` callers it saw 4,564 fleet-point-mode calls but
only 27 real-strength-mode calls. The latter are rare and important: the reviewed installed method
calls `CampaignFleet.inflateIfNeeded()` before computing strength when the candidate is the player.

| operation | calls | total | average | maximum | >16 ms | >33 ms | >50 ms | >100 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| other-fleet scan | 2,239 | 526.720 ms | 235.248 us | 121.772 ms | 3 | 3 | 2 | 1 |
| nearby-fleet decision | 531 | 264.944 ms | 498.952 us | 111.906 ms | 3 | 3 | 2 | 1 |
| **fleet inflation** | **27** | **232.934 ms** | **8.627 ms** | **107.371 ms** | **3** | **3** | **2** | **1** |
| nearby location-list access | 3,638 | 1.040 ms | 0.286 us | 0.198 ms | 0 | 0 | 0 | 0 |
| fleet-strength computation | 73 | 5.391 ms | 73.843 us | 1.016 ms | 0 | 0 | 0 | 0 |

Inflation accounted for 87.9% of the inclusive nearby-decision time. Location-list access and the
strength loop together consumed only 6.431 ms, so caching either one cannot explain or remove these
hitches.

## Exact frame joins

The three severe inflation spans aligned with retained active-campaign frames:

| inflation | enclosing nearby decision | containing frame | inflation share of frame |
| ---: | ---: | ---: | ---: |
| 107.371 ms | 111.906 ms | 200.130 ms | 53.7% |
| 65.226 ms | 66.080 ms | 89.725 ms | 72.7% |
| 45.798 ms | 46.549 ms | 65.971 ms | 69.4% |

This is the causal explanation the parent program asks for: rare lazy fleet materialization, reached
from tactical nearby-strength evaluation, occupied most of three bad campaign frames.

The intrusive active window retained 1,868 frames / 36.994 active seconds: p50 17.3 ms, p95
34.7 ms, p99 69.1 ms, 1% low 14.47 FPS, maximum 200.130 ms, 53 frames over 50 ms, four over
100 ms, and 61 slow-frame clusters. The paused control retained 3,341 frames / 57.668 active
seconds: p50 17.1 ms, p95 20.0 ms, p99 28.0 ms, 1% low 35.71 FPS, maximum 48.201 ms, and no
frame over 50 ms. Those numbers describe this discovery run and are not an optimization claim.

## Installed implementation and sampled support

The reviewed installed classes were:

- `TacticalModule`: SHA-256
  `53d6b876055d44a1dd97c9bf66561d974e102116c818aac654baf5ba1d70531c`;
- `CampaignFleet`: SHA-256
  `e407bde405304b4915a709ca15ec82b9d9098a6c0ad5a6acbed1f358d656b8b2`;
- API `DefaultFleetInflater`: SHA-256
  `80a07787e75edbdd5ae0b80da023aeaa59f43d08263de10b77af4595452b08ae`.

`CampaignFleet.inflateIfNeeded()` is not a bookkeeping no-op when an inflater is pending. It calls
the inflater, publishes the fleet-inflated listener event, applies removal policy, and marks the
fleet inflated. The shipped `DefaultFleetInflater` builds faction weapon/fighter availability,
autofits each member, applies S-mod/D-mod policy, assigns new variants, and synchronizes fleet data.
Skipping or memoizing the outer boolean decision would therefore change observable fleet
composition and listener behavior.

The separate SAMPLE run captured three campaign execution samples below
`DefaultFleetInflater.inflate`: two in `CoreAutofitPlugin.doFit` (one specifically adding randomized
hullmods) and one in `DModManager.addDMods/removeUnsuitedMods`. None fell inside that run's exact
`unpaused-settled` receipt because its lazy inflation occurred earlier. Three samples are not a cost
split, but they support per-member materialization as the next boundary rather than the already
disproved repository/strength paths.

## Correctness, fallback, and overhead

The exact decision route reported 73 observed, parsed, and exact-matched transformations, zero
contained failures, an inactive global kill switch, and an installed tactical plan. All semantic
steps passed; no save command was issued; the harness stopped only its recorded PID. The
display-boundary measurement hook averaged 21.54 us and reached 9.730 ms once. Deep tactical timers
add further unquantified discovery overhead, which is why their FPS is not used as a claim.

## Decision and next experiment

Reject the apparent `hasEnoughStuffAround` cache/scan candidate for this hitch family. The scan's
location-list and strength work are cheap, while the dominant work is one-time, stateful fleet
materialization. Do not suppress, defer, or pre-inflate fleets from this evidence: each option can
change campaign state, listener timing, fleet variants, and random/autofit outcomes.

The selected next seam is a bounded exact phase timer inside the reviewed
`DefaultFleetInflater.inflate` implementation: availability-pool construction, per-member
autofit/variant work, D-mod work, final synchronization, and the outer listener notification. If
per-member autofit dominates reproducibly, inspect an immutable-input reuse boundary there. If the
work is irreducibly stateful or moves between runs, retain the hitch explanation and reject the
optimization instead of moving cost earlier under a different name. Any eventual candidate needs
a thin repeated/interleaved route plus exact fleet/workload correctness guards.

Compact retained data is in
[`data/2026-08-28-installed-lazy-fleet-inflation-hitches.json`](data/2026-08-28-installed-lazy-fleet-inflation-hitches.json).
Raw logs, JFR, and run directories remain disposable local evidence.
