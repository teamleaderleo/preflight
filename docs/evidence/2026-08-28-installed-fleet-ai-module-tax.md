# Installed vanilla fleet-AI module tax

Date: 2026-08-28

Issues: #1158, #449. Branch: `codex/1158-physical` at `4626fcff`.

Status: **successful intrusive decomposition; no FPS optimization claim**.

The first installed #1158 owner/hitch-tax pass selected vanilla `ModularFleetAI.advance(float)` as
the strongest exact campaign hitch owner. This successor preserves all five existing module calls
and times assignment, strategic, tactical, navigation, and per-ability AI separately. It reports
concrete module classes and retains at most 32 calls over 5 ms with a per-run fleet-AI identity.
The plan is exact-hash gated, composed behind the reviewed fleet-AI target, fails back to original
bytecode, and has the kill switch
`preflight.campaign.fleetAiModuleTimes.disabled=true`.

## Result

| module | calls | total | average | maximum | >16 ms | >33 ms | >50 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| assignment | 18,463 | 37.115 ms | 2.010 us | 1.207 ms | 0 | 0 | 0 |
| strategic | 18,463 | 113.526 ms | 6.149 us | 6.964 ms | 0 | 0 | 0 |
| **tactical** | **18,463** | **745.422 ms** | **40.374 us** | **76.945 ms** | **3** | **3** | **1** |
| navigation | 18,463 | 56.595 ms | 3.065 us | 2.384 ms | 0 | 0 | 0 |
| ability | 90,365 | 305.464 ms | 3.380 us | 41.700 ms | 2 | 1 | 0 |

Tactical AI owns 59.3% of the 1,258.123 ms measured inside the five module seams. Ability AI owns
24.3%, but that total spans about five ability calls per fleet-AI advance.

The strongest concrete ability class was vanilla `SensorBurstAbilityAI`: 14,869 calls, 112.015 ms
total, 7.533 us average, and 41.700 ms maximum. `EmergencyBurnAbilityAI` was next at 56.874 ms
total and 8.595 ms maximum; Nexerelin sustained-burn AI measured 54.877 ms total and 6.815 ms
maximum. The other retained ability classes stayed below 3.3 ms maximum.

## Exact frame joins

The largest 76.945 ms tactical span and 41.700 ms sensor-burst span occurred during the deliberately
excluded five-second unpause transition. They explain 66.7% of a 115.397 ms severe transition
frame and 58.4% of a 71.350 ms transition frame respectively, but they are not measurement-window
performance evidence.

The clean unpaused measurement window still retained three materially explanatory joins:

| exact span | containing/nearest retained frame | span share |
| --- | ---: | ---: |
| tactical 34.492 ms | 51.713 ms | 66.7% |
| tactical 33.661 ms | 64.423 ms | 52.2% |
| `SensorBurstAbilityAI` 22.919 ms | 42.501 ms | 53.9% |

A later 10.039 ms sensor-burst span occupied 31.7% of a 31.708 ms frame. These are timestamp joins
in the shared wall-clock domain, not additive attribution across unrelated frames. They do establish
that tactical and sensor-burst work can consume a player-visible fraction of real bad frames.

The exact measurement window retained 1,650 frames / 31.596 active seconds: p50 17.3 ms, p95
32.7 ms, p99 62.5 ms, 1% low 16.00 FPS, 34 frames over 50 ms, three over 100 ms, and 15 repeated
slow-frame clusters. These values remain discovery context because the route is intrusive and not a
baseline/candidate cohort.

## Correctness and observer health

The semantic route passed every step, observed the initial save already paused, left it paused for
the warmup and settled window, then verified unpause before starting the exact measurement window.
The harness owned and stopped the exact process. Adapter mode stayed enabled with 73 transformations,
zero declines, source-binding rejects, malformed classes, or contained failures; the kill switch
stayed off. Combat runtime linkage remained assignable and same-loader.

The display-boundary hook averaged 26.00 us and reached 13.50 ms once. Per-module timing overhead is
not independently subtracted, and the owner/JFR suite was still active. This is therefore a target
selection result, not an FPS claim.

## Decision

The decomposition does not support optimizing assignment, strategic, or navigation. It selects two
bounded successors:

1. split the interval-gated tactical scan using vanilla's existing semantic seams: every-frame work,
   avoid-list update, location fleet-list acquisition, the `Looking at other fleets` loop, encounter
   option selection, and post-scan work;
2. inspect exact `SensorBurstAbilityAI.advance(float)` bytecode for an obviously repeated query or
   allocation, but do not prioritize it over tactical until its 22.919 ms measurement-window span is
   reproduced or explained.

The tactical bytecode is exact class SHA-256
`53d6b876055d44a1dd97c9bf66561d974e102116c818aac654baf5ba1d70531c`. Its long interval branch
explicitly obtains the location's `CampaignFleet` list, then scans other fleets under vanilla's
`Looking at other fleets` profiler label. Count and time those semantic regions before changing
behavior. A safe optimization must preserve target selection, hostility/transponder decisions,
battle-join behavior, fleet movement, and interval cadence.

Compact retained data is in
[`data/2026-08-28-installed-fleet-ai-module-tax.json`](data/2026-08-28-installed-fleet-ai-module-tax.json).
Raw logs, JFR, and run directories remain disposable local evidence.
