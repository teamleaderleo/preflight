# Collision-grid cache candidate: rejected

**Status:** exact RC8 implementation probe completed; no optimization retained

The 30-second deterministic 2× simulation profile identified vanilla
`com.fs.starfarer.combat.o0OO.G$o.<init>` as a 3.88% leaf CPU sample and the surrounding collision
iterator as the first non-JDK owner for 484.1 MiB, or 16.57%, of weighted allocation samples. The
owner class has SHA-256
`82903aac76201c513207d08eada9384ced90c28badb0deb716ba5d92ed910945` in the exact Starsector
0.98a-RC8 `starfarer_obf.jar`.

The class contains a complete query cache behind a hard-coded false local. It cannot safely be
enabled as shipped: its four-coordinate base-200 key performs the larger products as 32-bit integer
arithmetic before converting to `long`, and its map is not invalidated by either public grid
mutator. A temporary exact-gated candidate repaired the key with 64-bit arithmetic, rejected bounds
outside the injective base-200 domain, cleared before every `addObject` and `removeObject`, and
bounded the map to 4,096 entries. The candidate was exercised only in a disposable build and was
removed afterward.

## Live opportunity result

The unattended Preflight-only 8-v-25, 2× simulation route completed every controller step. Runtime
telemetry observed:

- 274,369 eligible collision queries;
- 13,431 cache hits and 260,938 misses, a 4.8952% hit rate;
- 803,798 grid mutations;
- 210,887 mutation-triggered clears containing 212,117 cached entries; and
- zero capacity clears and zero rejected bounds.

This is a poor leverage point. Even a free cache hit can remove only about 4.9% of a constructor
that represented 3.88% of sampled CPU, placing the observed CPU ceiling near 0.19% before accounting
for key lookups and more than 800,000 mutation hooks. It would likewise avoid only a small fraction
of the iterator's weighted allocations. The mutation/query shape explains why the dormant cache was
not a hidden large win: almost every completed snapshot becomes stale before another identical
query can use it.

The candidate run is not a performance comparison. Advanced Gunnery Control independently raised a
top-level combat `ClassCastException` from `TagBasedAI.kt:101`; Preflight correctly classified the
launch as `FATAL_LOG_EVIDENCE` even though the scenario controller finished its steps. Its frame
window is therefore confounded and excluded from performance evidence. The cache-opportunity
counters remain sufficient to reject the design on leverage grounds.

The compact, non-binary probe receipt is retained in
[`data/2026-08-27-collision-grid-cache-rejected.json`](data/2026-08-27-collision-grid-cache-rejected.json).
Raw JFR, screenshots, full logs, and transformed binaries were treated as disposable local artifacts.

## Direction after rejection

Do not re-enable the dormant map or add per-mutation instrumentation. Future collision work should
either reduce query creation at a higher-level caller or improve the iterator's per-miss data
structure while preserving its `LinkedHashSet` encounter order and equality semantics. The sampled
AI Tweaks targeting and autofire work remains the larger combined CPU category.
