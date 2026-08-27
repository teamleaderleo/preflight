# Collision-query ordered-set allocation reduction

**Status:** accepted with limit; exact installed-bytecode and two live Preflight-only B runs passed

The deterministic 1,040-DP combat profile made vanilla
`com.fs.starfarer.combat.o0OO.G$o.<init>` both the largest individual leaf CPU method and a
1.2-GiB weighted allocation family in its clean 30-second window. The existing dormant query cache
was already rejected because it hit only 4.8952% while observing more than 800,000 grid mutations.
This change improves every cache miss instead.

The reviewed constructor builds an insertion-ordered unique candidate collection by adding each
non-null collision-grid cell to a temporary `LinkedHashSet`, then exposes only its iterator. On the
exact RC8 class, Preflight substitutes a compact open-addressed set with a separate encounter-order
array. It preserves `LinkedHashSet` null, hash/equality, uniqueness, and first-encounter iteration
behavior. Exact `ArrayList` cells are copied by index, avoiding their short-lived iterators; any
other collection falls back to ordinary `Set.addAll`.

The transform is pinned to Java 17 class major 61, class SHA-256
`fd932939e0a61ebf73e56e48e06e66b18dcb311ca6a355a274a1df974173dd28`, the reviewed
`starfarer_obf.jar` SHA-256
`a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149`, the app loader, both
method descriptors, and the exact allocation/add-all/iterator shape. Drift returns the original
bytecode. The replacement adds no field to a game object, no static mutable game state, and no save
or serialization path.

## Live result

All three observations used Preflight, the same save route, the same mirrored 24-vs-24 high-tech
fixture (520 DP per side), autopilot, explicit SpeedUp 2×, an unpaused battle, and a verified
viewport width of 6,120. Setup completed before the controller reset the frame distribution. The
table therefore describes the clean 30-second combat window, not launch, Continue, menus, fleet
construction, deployment, or camera movement.

| Metric | Original | Open set | Open set + iterator-free cell copy |
|---|---:|---:|---:|
| frames | 765 | 781 | 807 |
| average FPS | 25.39 | 26.56 | 27.00 |
| median FPS | 27.10 | 28.57 | 28.65 |
| 1% low FPS | 8.85 | 9.35 | 8.47 |
| 0.1% low FPS | 6.99 | 6.93 | 7.24 |
| p95 frame time | 59.8 ms | 60.1 ms | 56.8 ms |
| p99 frame time | 113.0 ms | 107.0 ms | 118.0 ms |
| frames over 50 ms | 104 | 70 | 58 |
| stutter burden | 205.93 ms/s | 179.83 ms/s | 161.42 ms/s |
| repeated slow-frame exposure | 57.78% | 52.62% | 51.80% |
| longest slow cluster | 267 frames / 12.66 s | 190 / 8.80 s | 232 / 9.82 s |

Relative to the original observation, the final run rendered 6.34% more frames per active second,
cut excess sustained hitch time by 21.61%, cut frames over 50 ms by 44.23%, and reduced repeated
slow-frame exposure by 10.35%. The filtered collision family fell from approximately 1,261.9 MiB
to 686.3 MiB of JFR weighted allocation samples, a 45.61% reduction. Its sampled leaf CPU share was
7.93% originally and 9.03% finally; the higher share despite better overall throughput means this
constructor remains a worthwhile future target rather than proving direct CPU-time reduction.

The 1% low moved down 4.29% and p99 moved up 4.42% in the final single observation, while the 0.1%
low and maximum improved. That mixed tail is why the accepted result is the direct allocation
reduction plus the directional sustained-smoothness result—not a universal FPS or percentile claim.
More repetitions would be required for a public performance percentage.

Java 17 verification passed 2,181 tests with zero failures or errors and nine environment-gated
skips. The focused set/transform/installed-class/fixture gate passed eight tests. Source Boundary
and Benchmark Claim Provenance also passed; no installed game class, JFR, screenshot, log, save, or
transformed binary is tracked.

## Startup hitch boundary

Startup hitching is tracked separately. These runs reached the interactive main menu in 31.421,
31.995, and 30.807 seconds and each contained a roughly 6.1-second pre-swap presentation gap during
startup. The combat candidate did not change that stable startup hitch, and those gaps are excluded
from the combat measurement window. Time to interactive alone would conceal the gap; conversely,
including it in gameplay percentiles would misclassify launch work as combat roughness. The next
startup pass should rank startup stall duration and phase attribution alongside time to interactive.

The compact metrics and artifact identities are retained in
[`data/2026-08-27-collision-query-open-set.json`](data/2026-08-27-collision-query-open-set.json).
Raw JFRs, full logs, screenshots, and transformed binaries remain disposable local artifacts.
