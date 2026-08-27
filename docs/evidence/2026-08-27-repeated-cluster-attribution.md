# Repeated slow-frame cluster attribution

**Status:** cluster-aware measurement accepted; one exact allocation removal accepted with a
bounded sampled claim; the broader active-campaign stutter remains open

## Why the measurement changed

A converted 1% low does not distinguish one isolated transition hitch from a visible run of slow
frames. The frame recorder now retains the 32 longest clusters containing at least two consecutive
frames over 33.33 ms for every state-separated distribution. Each bounded record includes frame
count, total duration, excess time over the threshold, and exact monotonic/epoch window bounds.

`starsector_gameplay_hotspots.py --repeated-clusters N` maps those windows into the Rosetta-scaled
JFR clock and aggregates each sample once, even if requested series overlap. This lets future work
ask what ran during recurring visible stutter rather than ranking a whole scenario step or only its
single worst frame.

## First live attribution

One Preflight-only `campaign-sample-paused-unpaused` run passed all ten semantic steps in one owned
process. It kept the initial state untouched for three seconds, observed that the save was already
paused, retained a settled paused window, explicitly unpaused once, retained a settled active
window, and stopped the exact process. No inactive or invalid frame intervals were admitted.

The settled paused state contained one two-frame repeated cluster. The settled active state
contained 22 repeated clusters spanning 63 frames; its ten longest clusters covered 2.176 seconds
of wall time. Inside those ten windows, 51 of 54 execution samples were campaign samples:

- `CampaignEngine.advance`: 47/51 (92.16%) inclusive;
- `BaseLocation.advance` / `StarSystem.advance`: 25/51 (49.02%);
- `CampaignFleet.advance`: 18/51 (35.29%);
- `Economy.advance`: 8/51 (15.69%);
- `ModularFleetAI.advance`: 8/51 (15.69%).

No single leaf dominated. The current evidence therefore points to broad batched or catch-up
campaign work, not one tooltip or one already-known wrapper. That explanation remains a hypothesis:
it would be falsified by a repeated run whose cluster windows concentrate in a different coherent
stack or by a cadence probe showing no burst at those times.

Allocation samples exposed one exact removable cost. Eight MiB of sampled weight in the first run
was attributed to `MutableStat.getFlatMods`; every such sample was reached from
`CommodityOnMarket.preflight$eventModValidateOrDelegate`. The public accessor defensively copies
the complete map while the exact transformed class already exposes the backing map for validation.

## Direct-backed fingerprint follow-up

Commit `0e7c00e45f6f3deb6a17573098b68da34d47a6c2` now reads the exact `eMod` entry from that reviewed
backing map after vanilla authors the slow-path state. A null backing field is treated as the exact
empty representation used by a fresh installed `MutableStat`. A missing companion accessor throws
`LinkageError`, permanently disables the memo, and returns after the already-completed vanilla call;
it never invokes vanilla twice. The retained memo fields remain private and transient.

A second Preflight-only run passed the same ten semantic steps. In its ten longest active clusters,
`MutableStat.getFlatMods` and the commodity wrapper were both absent from all 28 campaign allocation
samples. This supports the narrow claim that the observed defensive-copy stack was removed. It does
not establish an FPS gain: the two runs were not lockstep, the computer's thermal state was changing,
and allocation sampling is weighted rather than exhaustive.

The second run still recorded 22 repeated active clusters and 59.44 ms/s stutter burden. Its 43
campaign execution samples again spread across `CampaignEngine`, location, fleet, economy, AI, UI,
and event-generation work. Removing one paper cut did not spend the category.

## Verification and safety

- Cluster recorder tests cover completed and in-progress clusters without mutating live counters.
- Analyzer tests cover ranking, bounds, overlap de-duplication, and union-duration reporting.
- Structural and installed Starsector class tests passed 8/8 for the direct-backed commodity plan,
  including the null-as-empty representation and missing-accessor fail-open path.
- Java 17 `./mvnw verify` passed all five modules: 2,225 tests, zero failures or errors, and nine
  intentional skips.
- The run opened no save-management surface, performed no save action, and stopped the one process
  it launched. The adapter still adds no serialized save state.

The bounded machine-readable record is
[`data/2026-08-27-repeated-cluster-attribution.json`](data/2026-08-27-repeated-cluster-attribution.json).
Raw JFRs, reports, logs, and generated binaries are disposable and identified there by SHA-256.

## Next falsifiable questions

1. Do repeated clusters align with a stable campaign-time cadence or accumulated delta/catch-up
   boundary across another B-only run?
2. Which location/fleet/economy sub-call owns the largest *excess cluster time*, not merely the most
   whole-window samples?
3. Does a symmetric 1,000+ DP combat window produce a different cluster topology and different
   dominant inclusive stacks?
4. Does the direct-backed commodity change remain absent from allocation samples in a deep
   call-time-probe run, and does the residual nonempty exact-key path matter after JIT compilation?

