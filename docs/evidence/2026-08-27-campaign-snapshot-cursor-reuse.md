# Reusing exhausted campaign snapshot cursors

Two timer-free campaign recordings exposed a repeatable allocation category inside Preflight's
existing stable-snapshot optimization. The optimization had already removed vanilla defensive list
copies, but every non-empty market or location traversal still created a new `SnapshotIterator`.
JFR `ObjectAllocationSample` weights attributed 59.7 and 30.4 MiB to those cursor paths in the two
recordings. Allocation weights are statistical estimates, not exact byte counters.

The runtime now retains one cursor per stable snapshot identity. A cursor becomes reusable only
after its prior traversal observes `hasNext() == false`. If a traversal overlaps, re-enters, breaks
early, or has not yet proved exhaustion, the new pass receives an independent fallback cursor.
Disabling stable snapshots bypasses cursor retention as well. The cursor identity is private to the
exact transformed loop and never enters game or save state.

Executable coverage verifies independent overlapping passes, reuse after exhaustion, stable-array
mutation behavior, empty traversal, and disabled-cache behavior. Full Java 17 verification passed
after the change.

## Two Preflight-only follow-ups

Both sampled follow-ups completed Continue, initial paused observation, paused settled measurement,
mapped unpause, and unpaused settled measurement. Both used one Preflight-owned process, produced
one valid 163/164-second JFR chunk, and dropped zero inactive frame intervals.

The runtime ended with 475 and 485 retained cursors for 479 and 482 stable snapshot owners. The
same cursor-attributed allocation ranking fell to 10.0 and 19.8 MiB, respectively: 66.6% below the
lower prior result and 83.3% below the higher one at best. Total campaign-main-thread allocation
weight was also repeatably lower at 1.66/1.65 GiB, compared with 1.73/1.89 GiB before. Workload
variation means the total-weight movement is corroborating rather than causal evidence; the
targeted cursor reduction is the direct result.

Settled paused tail behavior moved in the desired direction but remains noisy. The two earlier
runs recorded 30.21/30.03 FPS 1% lows and 33.1/33.3 ms p99. The cursor follow-ups recorded
30.03/38.61 FPS 1% lows and 33.3/25.9 ms p99. Their 0.1% lows were 29.59 and 22.03 FPS, compared
with 20.83 and 20.58 FPS before. Average FPS varied with workload and machine temperature, so this
is not presented as a universal frame-rate claim.

The bounded record is
[`data/2026-08-27-campaign-snapshot-cursor-reuse.json`](data/2026-08-27-campaign-snapshot-cursor-reuse.json).
The raw JFRs remain disposable local artifacts and are not committed.
