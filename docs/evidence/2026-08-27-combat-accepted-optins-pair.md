# Accepted combat opt-ins: exact-window directional pair

Date: 2026-08-27

Install: Starsector 0.98a-RC8, current heavily modded profile, macOS on Apple M5,
Java 17 under Rosetta, Preflight fast preset

Status: one clean default run and one clean combined-opt-in run; structurally positive and
directionally favorable, not yet a universal FPS claim

## Controlled route

Both consecutive Preflight-only runs used source checkpoint `07b5921b`, one owned game process at a
time, and the same `campaign-simulation-combat-1000dp` scenario. All 34 semantic steps passed in
each run. The controller prepared 24 mirrored ships and 520 DP per side, enabled 2x simulation
speed, verified zoom from 1,800 to 6,120 world units, measured one exact 30-second window, and
stopped the exact process normally. macOS reported no thermal or performance warning immediately
before the first run or after the pair.

The second run enabled only previously reviewed opt-ins:

- `preflight.combat.aiTweaksArcCapacity=true`
- `preflight.combat.aiTweaksAffineVectors=true`
- `preflight.combat.listenerRangeSnapshotArray=true`
- `preflight.combat.listenerRangeEmptySnapshot=true`

The adapter report confirms the exact listener, three affine-vector, and split-arcs transforms all
applied. The collision-query v2 plan remained enabled in both runs.

## Result

| Exact measurement-window metric | Released/default stack | Accepted opt-ins | Directional delta |
| --- | ---: | ---: | ---: |
| Frames | 758 | 805 | +47 |
| Average FPS | 25.06 | 26.63 | +6.26% |
| Median FPS | 27.78 | 28.82 | +3.74% |
| 1% low | 8.23 FPS | 8.51 FPS | +3.40% |
| 0.1% low | 6.37 FPS | 7.09 FPS | +11.30% |
| p95 frame time | 63.9 ms | 58.9 ms | -7.82% |
| p99 frame time | 121.5 ms | 117.5 ms | -3.29% |
| Stutter burden | 193.18 ms/s | 167.54 ms/s | -13.27% |
| Frames in recurring slow clusters | 65.70% | 56.15% | -9.55 points |
| Longest recurring cluster | 5,508.82 ms | 3,168.86 ms | -42.48% |

The 32-window profiler intersected recurring combat clusters with the exact scenario receipt. It
retained 28 windows covering 21.264 seconds and about 3.8 GiB of weighted allocation in the default
run, versus 26 windows covering 17.911 seconds and about 2.9 GiB in the opt-in run. JFR allocation
weights are statistical samples, not an allocation census.

## Direct structural evidence

The listener shortcut served **45,881,368** exact empty `ArrayList` snapshots from one private empty
array. It observed **zero** non-empty delegations and **zero** unknown-list delegations. This is a
direct confirmation of the narrow semantic premise: on this stress route the eliminated snapshots
were all empty, while the fallback paths remained available.

Collision-query v2 also remained healthy. The default run hit 830,242 of 831,063 hints (99.90%) and
avoided 496,928 growths. The opt-in run hit 856,011 of 857,076 hints (99.88%) and avoided 522,638
growths. No gameplay fatal occurred.

The remaining hitch profile is broad. AI Tweaks autofire and ship AI, vanilla ship/weapon-group
advance, collision construction, graphics effects, and damage-analysis mods all recur. This pair
supports keeping the accepted knobs in the promotion queue; it does not justify reviving the
retired AI Tweaks `SelectTarget` field rewrite.

## Claim boundary and falsifier

Battle evolution is deterministic enough for repeatable stress, but not lockstep, and the machine
was physically warm. A single consecutive pair can be directionally useful without proving that
each knob independently caused each frame-metric delta. Promotion should require either another
foreground B observation or an ordinary-DP confirmation with the same direction and no semantic
failure. A clean reversal in that confirmation, any non-empty listener case that changes callback
behavior, or an exact-transform failure falsifies promotion.

Compact values and artifact hashes are retained in
[`data/2026-08-27-combat-accepted-optins-pair.json`](data/2026-08-27-combat-accepted-optins-pair.json).
Raw JFRs, copied log tails, and launch directories are disposable.
