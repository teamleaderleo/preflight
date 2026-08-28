# Installed combat-scaling ladder

Date: 2026-08-28

Install: Starsector 0.98a-RC8, current 83-mod profile, macOS on Apple M5, shipped x86-64
Java 17 game runtime under Rosetta, Preflight Recommended/Fast runtime path

Status: **repeatable discovery result; no materially bad nonlinear scaling law confirmed**.
Three shuffled, balanced, Preflight-only ladders measured exact 260, 520, 780, and 1,040 DP
combat fixtures. The result identifies broad AI/entity/ordnance tax and several owner leads, but it
does not justify a gameplay optimization or a player-facing FPS claim by itself.

## Decision

The first ladder alone preferred a `totalAi` threshold near 123 entities. That apparent cliff did
not survive replication. Across three independent run IDs and 72 ten-second workload buckets, the
best leave-one-run-out model was a fighter-by-missile interaction:

`advanceMicros = 1249.44 + 129.346*fighters + 34.5499*missiles + 1.73555*fighters*missiles`

Its run-blocked RMSE was 5,415 microseconds and its R² was only 0.290. A simple linear `totalAi`
model had a 5,465-microsecond run-blocked RMSE: less than one percent worse. The fitter therefore
correctly reports `confirmedBadScaling=false` and `confirmedSuperlinear=false`.

The installed-host evidence supports this narrower conclusion:

`more ships/fighters/missiles -> more combat advance work, mostly as diffuse accumulated tax`

It does **not** support a stable entity-count cliff, one dominant nonlinear owner, or a new
optimization candidate. The apparent run-one threshold is retained as a rejected exploratory lead
rather than promoted from a convenient single result.

## Exact cohort

Each cell used the same prepared 83-mod profile and save, exact in-memory symmetric fixture,
autopilot, two-times combat speed, 4.0 view multiplier centered at (0,0), and a sealed 30-second wall
window. The three orders were deliberately varied:

- `dp-ladder-r1`: 260, 520, 780, 1,040 DP;
- `dp-ladder-r2`: 1,040, 520, 780, 260 DP;
- `dp-ladder-r3`: 520, 260, 1,040, 780 DP.

Every cell began at its requested DP, split exactly in half between the two sides. All 12 runs had
zero unavailable-DP primaries, zero workload-sample failures, zero dropped samples, and healthy
combat runtime integrity. One 780-DP run lost one 8-DP ship late in the window; the fitter uses live
time-bucketed workload values, so the late 772-DP state is represented rather than silently treated
as 780 DP.

| battle DP | primary ships | median of run-median `advance` | run medians | samples per run |
| ---: | ---: | ---: | --- | --- |
| 260 | 12 | 3.149 ms | 3.694, 2.932, 3.149 ms | 27, 28, 27 |
| 520 | 24 | 4.983 ms | 4.821, 4.983, 5.263 ms | 26, 25, 25 |
| 780 | 36 | 7.310 ms | 6.993, 7.394, 7.310 ms | 21, 22, 20 |
| 1,040 | 48 | 9.683 ms | 9.892, 9.279, 9.683 ms | 19, 19, 17 |

The workload sampler ran every 60 combat ticks. Its average cost on a sampled tick ranged from
4.40 to 7.32 milliseconds and is excluded from the corresponding `advanceMicros` timer. This is
intrusive discovery instrumentation: the cadence and overhead are retained, and frame-report FPS
from these runs is not used as a performance claim.

## Owner-attribution calibration

The coefficient cohort kept JFR off. After the fit settled, one 260-DP and one 1,040-DP cell were
repeated with sample recording as `dp-owner-r1`. Both passed the same identity gates. The existing
clock-calibrated owner tool aligned 12 workload buckets with combat execution samples and excluded
nine samples overlapping the workload probe itself.

For `totalAi`, predictor versus sampled `CombatEngine.advance` was r=0.798. The strongest narrower
leads rising with workload were:

- vanilla collision-grid iterator construction/query (`G$o.<init>` / `G.getCheckIterator`), about
  5.1% mean sampled inclusive share and +9.1 percentage points from low to high workload buckets;
- broad vanilla combat AI and fighter AI;
- Combat Analytics' per-frame damage detector chain, about 3.0% mean share and +4.6 points;
- weapon-group/autofire work;
- AI Tweaks autofire/hit-analysis methods;
- listener/range lookup and repository-list access.

The fighter-by-missile attribution produced the same families, with predictor versus advance only
r=0.517. These are sample-composition associations from one profiled high/low pair, not elapsed CPU
claims. None is promoted from this pass. Collision work is already partly addressed by the active
exact collision-query plan, while the mod owners belong in #1158's frame-tax lane for independent
confirmation before another bytecode candidate.

## Harness corrections retained

The installed pass found and fixed two measurement bugs before accepting any coefficient:

1. the initial sampler observed setup ticks and read `ShipAPI.getDeployCost()` as though it were DP;
   sampling is now sealed to the exact frame window and uses fleet-member deployment points;
2. the simulation manager removed prior fleet members but their engine entities survived until a
   later advance, contaminating a nominal 260-DP cell at roughly 739 live DP. The fixture now removes
   every pre-fixture engine ship, delays verification until after settlement, and proves by object
   identity that no prior ship survives while every expected primary does.

Failed and contaminated pilots were moved recoverably to Trash and excluded from the corpus. The
accepted carrier fails closed for unsupported DP labels, surviving pre-fixture entities, missing
primary entities, mismatched per-side DP, sampler failure, and route failure.

## Reproduction and retained data

One exact cell is:

```bash
scripts/run-combat-scaling-pilot.sh \
  --run-id dp-ladder-r1 \
  --cell-id symmetric-1040 \
  --battle-dp 1040 \
  --game /Applications/Starsector.app
```

The complete run-blocked model table, equations, observed ranges, and source report names are in
[`data/2026-08-28-installed-combat-scaling-fit.json`](data/2026-08-28-installed-combat-scaling-fit.json).
Raw JFRs, logs, game screenshots, and run directories remain disposable local evidence and are not
committed. `./mvnw verify` passed after the final harness correction.

## Consequence for #449 / #1155

#1155 now has real installed coefficients and a bounded owner calibration. The result spends the
specific “large hidden nonlinear combat law” hypothesis for this symmetric high-tech ladder without
spending the broader combat-performance area. The next physical-host work should not manufacture a
candidate from a weak model. Run a separated #1153 renderer/state candidate or a bounded #1156 GPU
resource/synchronization diagnostic; route the Combat Analytics and AI Tweaks frame-tax leads to
#1158 for independent confirmation.
