# Frame telemetry now reports FPS throughput and lows directly

**Date:** 2026-08-05
**Status:** implementation, unit verification, and corrected live readout complete

## Why add FPS fields

The existing passive probe already counted frames, total active nanoseconds, percentile frame
times, budget overruns, state boundaries, focus loss, and worst-frame timestamps. That is the
authoritative information, but requiring every report consumer to invert microseconds made the most
recognizable gameplay result unnecessarily obscure.

The report now derives these values from the same accumulators, with no additional per-frame clock,
allocation, or sampling work:

- `averageFps`: frame count divided by total active duration, equivalently the inverse mean frame
  time;
- `medianFps`: inverse p50 frame time;
- `onePercentLowFps`: inverse p99 frame time;
- `pointOnePercentLowFps`: inverse p999 frame time;
- `framesMeeting60FpsPercent`: percentage at or below the 16.67ms budget;
- `framesMeeting30FpsPercent`: percentage at or below the 33.33ms budget.

Values are rounded to two decimal places. Empty distributions report `null`, not an invented zero.
Campaign and combat remain separate distributions, and focus-lost time remains excluded.

## First live readout

Run `~/.starsector-preflight/runs/campaign-radar-fps-v2-20260805-055942` used passive frame telemetry
without JFR and completed normally.

The mixed campaign sample contained 6,039 active frames:

| metric | result |
| --- | ---: |
| average FPS | 53.40 |
| median FPS | 59.17 |
| 1% low FPS | 15.04 |
| 0.1% low FPS | 6.78 |
| frames meeting 60 FPS | 45.64% |
| frames meeting 30 FPS | 96.32% |
| p95 / p99 frame time | 29.7ms / 66.5ms |
| worst frame | 388.181ms |

This is useful diagnostic evidence, not a controlled optimization A/B. It says the common campaign
frame is close to 60 FPS while the remaining problem is tail latency: 96.32% of frames meet 30 FPS,
but only 45.64% meet the 60-FPS budget.

The run also recorded 683 nominal combat frames, but that short distribution includes a 4.52-second
load/transition frame. Review found that campaign/combat observations persisted indefinitely after
their game-loop call site stopped running, so later loading and menu display intervals inherited the
last gameplay label. State observations are now one-display-boundary pulses. Stable gameplay still
segments normally, while the first interval into and out of a state is dropped as a transition and
unobserved loading/menu frames remain unclassified.

The same review found that `postStartupActive` stayed empty in non-JFR pilots: startup completion
was previously signalled only by the full startup-phase probe, which the safe Rosetta path does not
enable. Frame-only runs now exact-gate a minimal `ResourceLoaderState.init(Map)` transform that calls
only `markStartupComplete()` before its unique return. Full startup-attribution runs keep their
existing transform instead, so two plans never compete for the same class. Synthetic tests and an
exact installed-core transform pass. The first live attempt exposed an access-control defect before
the main menu: the injected game class could not call package-private `markStartupComplete()`.
The runtime entry point is now public, and the regression test defines the transformed game class in
its own classloader and actually invokes `init(Map)` across the package boundary.

## Corrected live readout and campaign warm-up

Run `~/.starsector-preflight/runs/aitweaks-boxing-fps-v3-20260805-062901` completed normally without
JFR. The lightweight startup marker reported `startupComplete=true`; all 33 reviewed transforms
applied with zero declines or contained failures. The distributions no longer contain inherited
multi-second loading/menu intervals.

| distribution | frames | average FPS | median FPS | 1% low | 0.1% low | p95 / p99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| all active | 8,635 | 55.25 | 62.89 | 14.33 | 2.69 | 29.6 / 69.8ms |
| post-startup | 6,016 | 44.85 | 59.52 | 12.90 | 3.53 | 33.4 / 77.5ms |
| campaign | 2,599 | 50.09 | 59.17 | 12.30 | 4.92 | 36.4 / 81.3ms |
| raw combat call site | 2,947 | 52.90 | 59.17 | 16.81 | 4.06 | 30.3 / 59.5ms |

The raw combat row is not yet a pure battle number. Starsector also advances a `CombatEngine` for
the animated battle behind the title screen. Fifteen retained tail frames at 30--40 seconds are
from that pre-campaign engine and contaminate the aggregate even though the call-site label itself
is exact. The report now retains the raw distribution for compatibility and adds
`combatAfterCampaignActive`, which begins only after this process has observed campaign play. It
also splits campaign play into `campaignFirst30SecondsActive` and
`campaignAfter30SecondsActive`. A menu-only mission can still use the raw distribution; a
load-campaign-then-simulate pilot gets the uncontaminated session row.

The operator independently described campaign play as jittery immediately after loading and smooth
later. The retained worst campaign frames agree: they cluster from roughly 50 to 100 seconds after
the first display boundary, then nearly disappear. The game reported the save load complete at
46.219 seconds and immediately performed deferred Combat Chatter CSV/JSON reads around 48.5
seconds. Later clusters are adjacent to Nexerelin fleet/economy/event work; 116 `RepTrackerEvent`
lines occur in the 70-second region. These are leads, not causal attribution: neighboring log lines
cannot prove what occupied a silent frame interval. The new fixed warm-up/steady distributions make
the next optimization an actual A/B instead of another log-gap guess.

Targeted tests pin the 30-second boundary, campaign-start timestamp, title-demo exclusion, and the
existing pulse/transition behavior. Full `mvn verify` passes.

## First warm-up optimization A/B

The post-startup single-file JSON cache supplied the first use of the split distribution. Its
learning and warm runs served 0 and 746 eligible paths from the prepared artifact respectively,
with 99.73% warm coverage and no failures. Despite removing those repeated parses, the first-30-
second campaign average moved from 47.34 to 46.72 FPS and p95 moved from 45.0 to 50.3ms. The
operator-driven workloads were not identical, so those small differences are noise; critically,
there is no improvement large enough to support the JSON hypothesis.

The later-campaign distribution remained better in both runs. The warm run measured 52.90 average
FPS, 59.52 median FPS, 14.18 FPS 1% low, and 30.1/70.5ms p95/p99 after the first 30 seconds. The next
probe should attribute exact campaign simulation/event call sites rather than infer ownership from
nearby log messages.

That attribution probe now accompanies the opt-in frame pilot. It records inclusive call count,
total/average/maximum time, threshold counts, and the 32 slowest end timestamps for these exact
methods:

- Nexerelin 0.12.2b `FleetPoolHelperListener.advance`,
  `NexRouteManager.spawnAndDespawn`, `ResourcePoolManager.updatePoints`, and
  `DiplomacyManager.advance`;
- Starsector 0.98a-RC8 `RepTrackerEvent.advance` and `EconomyFleetRouteManager.advance`.

Every class and owning archive is hash-pinned. Normal returns and exceptions both close the timer;
the original exception is rethrown. The hot path uses fixed primitive arrays, allocates nothing,
and is disabled unless frame telemetry was explicitly requested. Results are inclusive, so a nested
route operation must not be added to its caller's total. Synthetic invocation proves normal and
exceptional behavior, and all six installed classes pass exact-archive transform verification.

## Rosetta profiling boundary

The immediately preceding JFR-enabled attempt crashed in Zulu 17.0.10's
`SharedRuntime::get_poll_stub` under Rosetta with HotSpot's own safepoint-polling guarantee. The game
had not reached the radar renderer and no adapter report was published. Keeping the frame probe
available under `run-gameplay-pilot.sh --without-profile` provides FPS, percentile, and worst-frame
telemetry without enabling JFR's sampler on this unstable translated runtime.

Unit tests pin the arithmetic, budget percentages, empty-distribution null behavior, expiring state
observations, and the minimal completion transform. Full build verification is recorded with the
implementing commits.
