# Detailed Combat Results state-map reuse candidate

Date: 2026-08-27

Install: Detailed Combat Results 5.4.3 in the current Starsector 0.98a-RC8 mod profile

Candidate: `734555bc` (`detailed-combat-results-state-map-reuse-v1`), explicit opt-in only

Status: exact installed-bytecode and Java 17 verification passed; live ordinary/stress validation
remains required before any promotion or performance claim

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

## Next evidence

Run one ordinary Preflight-only B first and require nonzero `historyFrames` and `shipFrames` telemetry
plus a clean DCR/game log. If that passes, run the existing 1,040-DP B and compare against already
retained observations. Do not rerun A, do not launch without Preflight, and do not promote the property
from this offline result alone.
