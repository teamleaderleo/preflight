# Detailed Combat Results state-map reuse candidate

Date: 2026-08-27

Install: Detailed Combat Results 5.4.3 in the current Starsector 0.98a-RC8 mod profile

Candidate: `734555bc` (`detailed-combat-results-state-map-reuse-v1`), reverted by `418be653`

Status: **rejected after live ordinary and 1,040-DP validation**. The transform installed and ran
without a fatal, but the stress result was substantially worse and the intervention left large
allocation families inside the same method. The bounded record is
[`data/2026-08-27-dcr-state-map-reuse-rejected.json`](data/2026-08-27-dcr-state-map-reuse-rejected.json).

## Observed boundary

The installed archive and target class were read directly from the active mod installation:

- archive: `StarSectorDetailedCombatResults.jar`
- archive SHA-256: `e8dedffb3a34ab1f8eb7d5479258999b42cf1064bffad825055ed81fbdb9c79c`
- class: `data/scripts/combatanalytics/damagedetection/FrameProcessorState`
- class SHA-256: `4df669b6bacfecffb5bd96da9def7e1cd6a8d975d18b2f375fe8eb57bcf0eebf`
- class-file major version: 60 (Java 16 bytecode, valid on the project's Java 17 runtime)
- method: `updateCommonState(float, CombatEngineAPI)`

The method creates three `HashMap` instances on every active combat frame. It rebuilds retained
projectile history, copies the prior alive-ship map into the killed-ship map, and allocates the next
alive-ship map. The candidate keeps the same per-frame boundary and predicates while mutating the
projectile map in place and rotating three already-distinct ship maps.

This class is transient combat analysis state. The candidate does not transform save discovery,
load/write behavior, campaign objects, or serialization.

## Exact gates and offline verification

The adapter requires the exact class hash, archive hash, class-file major version, method descriptor,
source suffix, URL class loader, and reviewed instruction shape. Unknown or changed bytes retain the
original class. The candidate is registered only when
`preflight.combat.detailedResultsStateReuse=true`; it is not part of Recommended.

The installed-JAR test reads the class entry in memory, applies the real transformer, runs ASM dataflow
analysis over every transformed method, confirms all three `HashMap` allocations left the target
method, and confirms a second rewrite fails closed. Java 17 verification passed with 657 agent tests,
zero failures, zero errors, and one unrelated skip.

An earlier verification attempt expected class-file major version 61 and correctly rejected the
installed class. Inspection established major version 60; the exact gate and fixture were corrected,
then the real installed bytes passed. Preflight source and helper bytecode continue to target Java 17.

## Hypothesis and falsifier

Hypothesis: retaining map tables and entries where possible reduces allocation pressure and recurring
combat hitches, with the largest observable effect in the established 1,040-DP fixture.

Falsify or reject the candidate if any of these occurs:

- the live source/loader gate does not install;
- ordinary combat produces a DCR error, result discontinuity, or gameplay fatal;
- the controller fails a semantic step or exact process shutdown;
- the matching allocation family and recurring-cluster exposure do not improve meaningfully in the
  existing stress workload; or
- map reuse exposes an encounter-order dependency. `HashMap` order was already unspecified, but
  retaining buckets can change encounter order relative to rebuilding a map.

## Live result and decision

Both Preflight-only live runs passed their semantic controller steps, exact process shutdown, source
gate, and DCR/game-log safety checks. Ordinary combat exercised 3,014 history/ship frames. The stress
fixture exercised 613 and confirmed the intended 24-versus-24, mirrored 520-DP-per-side setup at 2x
speed with the camera zoomed out.

The ordinary clean window measured 48.06 average FPS, 16.18 FPS 1% low, 33.32 ms/s stutter burden,
and 2.61% repeated-slow-frame exposure. It was directionally worse than the retained ordinary B,
although that comparison alone is not thermally or workload locked.

The 1,040-DP clean window was decisive enough to reject rather than promote:

| Metric | Retained accepted B | Map-reuse candidate | Direction |
| --- | ---: | ---: | ---: |
| Average FPS | 26.63 | 19.15 | -28.09% |
| 1% low | 8.51 FPS | 7.32 FPS | -13.98% |
| Stutter burden | 167.54 ms/s | 362.93 ms/s | +116.62% |
| Repeated slow frames | 56.15% | 96.20% | +40.05 points |
| Longest repeated cluster | 3.169 s | 22.461 s | +608.77% |

This is a directional intervention result, not proof that a particular changed `HashMap` iteration
order caused the entire delta. It is sufficient for the product decision: the candidate does not
earn another live run or a Recommended default. Its exact-window allocation profile also retained
roughly 6 MiB of sampled `Double.valueOf` weight while aging projectile-history entries and roughly
6 MiB under `Helpers.concat`/`Arrays.copyOf`. Removing three map constructions did not remove the
larger DCR work visible in this workload.

## Explored, not exhausted

The rejected representation should not be restored unless a new implementation preserves original
encounter order and has a stronger allocation premise. DCR itself remains open: projectile-age
boxing and concatenation are now sharper, falsifiable leads. A narrower transform can eliminate one
of those families without rotating or mutating the original maps in place.
