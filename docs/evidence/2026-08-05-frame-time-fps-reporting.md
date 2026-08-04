# Frame telemetry now reports FPS throughput and lows directly

**Date:** 2026-08-05
**Status:** implementation, unit verification, and first live readout complete

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
exact installed-core transform pass; a fresh live readout remains pending.

## Rosetta profiling boundary

The immediately preceding JFR-enabled attempt crashed in Zulu 17.0.10's
`SharedRuntime::get_poll_stub` under Rosetta with HotSpot's own safepoint-polling guarantee. The game
had not reached the radar renderer and no adapter report was published. Keeping the frame probe
available under `run-gameplay-pilot.sh --without-profile` provides FPS, percentile, and worst-frame
telemetry without enabling JFR's sampler on this unstable translated runtime.

Unit tests pin the arithmetic, budget percentages, empty-distribution null behavior, expiring state
observations, and the minimal completion transform. Full build verification is recorded with the
implementing commits.
