# Campaign call-window and slow-frame correlation

**Status:** exact diagnostic accepted; location/economy/fleet work remains a mixed active-campaign
frontier rather than a proved single root cause

## Controlled run

The deep `campaign-profile-paused-unpaused` scenario passed all ten semantic steps in one
Preflight-owned process. It observed the save untouched for three seconds, confirmed that the game
was already paused, measured a 45-second settled paused window, issued one verified unpause, held a
five-second transition window, and then measured a 45-second settled active route. It dropped zero
inactive and zero invalid frame intervals. The diagnostic source is committed as
`1b9ab58bfc859535482ac5ad7dde850333b5d698`.

The opt-in campaign timers now retain the 32 slowest calls of at least 1 ms for each timed group,
including exact wall-clock start and end bounds. The correlation tool intersects those calls with
the frame recorder's repeated >33.33 ms clusters. It can also intersect both with exact scenario
receipt steps. Timer rows are inclusive and nested: their overlap must not be added together as CPU
time.

## The state bucket initially hid two workloads

The `campaignUnpausedAfter30SecondsActive` state bucket contained all 22 repeated active clusters,
but its name does not mean “five seconds after unpause.” Seven clusters (25 frames, 1,715.929 ms of
original cluster duration) occurred in the explicit `unpaused-transition` step. The other 15
clusters (34 frames, 1,672.968 ms) occurred in `unpaused-settled`.

This distinction materially changes attribution. Among transition clusters, the largest retained
overlaps were locations (346.02 ms across 10 calls), economy (177.22 ms across 13),
`ModularFleetAI` (136.32 ms across eight), and market advance (93.24 ms across 16). This is a broad
post-unpause burst and should not be advertised as steady-state route cost.

The settled 45-second route ranked:

| Inclusive timer | Retained overlap | Overlapping calls | Largest call |
| --- | ---: | ---: | ---: |
| locations | 69.27 ms | 1 | 69.27 ms |
| economy | 57.79 ms | 5 | 15.10 ms |
| `ModularFleetAI` | 50.40 ms | 4 | 28.34 ms |
| economy stepper | 33.88 ms | 7 | 11.02 ms |
| fleet base entity | 27.37 ms | 4 | 21.73 ms |
| `qolp_clock` | 17.30 ms | 10 | 2.53 ms |
| `SupplyDataTracking` | 15.98 ms | 1 | 15.98 ms |
| core campaign script | 12.13 ms | 7 | 3.19 ms |

The paused settled window had 35 frames over 33.33 ms but no repeated multi-frame cluster inside
the exact step. Its low tail was therefore isolated in this run, unlike the active route.

## Interpretation and falsifiers

**Observed:** the immediate post-unpause burst is much more concentrated in broad location,
economy, fleet-AI, and market work than the settled route. The settled route still has recurring
clusters, but no retained child timer accounts for most of their 1.673 seconds of wall time.

**Working interpretation:** there are at least two active-campaign problems: a catch-up burst after
unpause and mixed steady-state spikes/paper cuts. The earlier “one shared cadence boundary” idea is
now too broad to stand as the only explanation.

**Falsifiers and next probes:** repeat the same source and route to see whether the post-unpause
ordering is stable; correlate timer calls with individual frames rather than whole cluster windows;
and add narrower children below location advance only if the 69–90 ms location spikes recur. A
settled run that consistently concentrates in one different leaf would supersede the mixed-work
interpretation. An on/off intervention is still required for an uplift claim.

## Measurement limits and safety

This is a diagnostic run, not a production-FPS comparison. The display-boundary recorder averaged
9.93 microseconds per sample, but the many opt-in call timers add additional unmeasured overhead.
The run's 58.20 paused average / 30.03 FPS 1% low and 51.49 active average / 15.41 FPS 1% low are
useful workload descriptions only. Thermal state was not locked.

The scenario never opened save management or wrote a save. The adapter adds no serialized state.
Java 17 `./mvnw verify` and the focused Python analyzer suite passed before this record was written.
The bounded machine-readable record is
[`data/2026-08-27-campaign-call-cluster-correlation.json`](data/2026-08-27-campaign-call-cluster-correlation.json).
Raw reports and rebuildable binaries are disposable and identified there by SHA-256.
