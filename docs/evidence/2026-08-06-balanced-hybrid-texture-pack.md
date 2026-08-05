# Balanced texture storage skips pointless compression without losing learned order

**Date:** 2026-08-06

**Runtime:** Starsector's bundled Zulu 17.0.10 x86_64 JRE under Rosetta 2

**Profile:** Starsector 0.98a-RC8 with 83 enabled mods

## Result

`balanced` now stores a prepared pixel array raw only when its exact LZ4 ratio is below 1.30x.
Everything else remains lossless LZ4. On the reviewed 30,638-blob profile this selects 3,440 raw
blobs and 27,198 LZ4 blobs:

| pack | bytes | change from all-LZ4 |
| --- | ---: | ---: |
| all-LZ4 | 2,204,050,670 | -- |
| balanced hybrid | 2,258,964,304 | +54,913,634 (+2.49%) |
| all-raw ceiling | about 5.0 GiB | about +3.1 GiB |

The selected codec remains explicit in each SPFT header and content-addressed filename. The
manifest and pack may contain both codecs; no reader behavior or blob format was weakened. Checked
preparation reads still validate full checksums. A missing, corrupt, wrong-identity, or malformed
selected artifact is quarantined or omitted and the existing vanilla runtime fallback remains in
force.

Preparation reports the raw/LZ4 blob counts. A repeat hybrid preparation reused all 30,638 loose
artifacts with zero build, failure, or quarantine and published the mixed pack atomically.

## Exact bundled-JVM crossover

The benchmark source is
[`2026-08-06-hybrid-texture-pack-benchmark.java`](2026-08-06-hybrid-texture-pack-benchmark.java).
It built an independent mixed pack from the same checked loose artifacts and replayed the exact
14,774 distinct successful startup accesses. Seven fresh JVMs per condition alternated order. Both
conditions restored 2,074,073,333 pixel bytes per pass and produced checksum
`-4767455416646045759`.

| condition | seven fresh-process observations, ms | median |
| --- | --- | ---: |
| all-LZ4 | 1205.551, 1254.792, 1217.199, 1229.179, 1248.944, 1278.089, 1248.789 | 1248.789 |
| hybrid | 1181.659, 1190.886, 1178.573, 1183.484, 1194.628, 1252.187, 1239.047 | **1190.886** |

This first 1.10x hybrid was **57.903ms faster (4.6%)** at the complete prepared-pack read seam. A
later threshold sweep is described below. The controlled result is causal CPU/I/O evidence, not a
claim that a whole launch must fall by exactly the replay delta.

## Threshold sweep selects 1.30x for balanced

Fresh bundled-game-JVM passes extended the crossover from 1.02x through all-raw. The knee suitable
for a compact default is 1.30x: it uses 3,440 raw blobs and grows the all-LZ4 pack by 54.9MB. Ten
fresh processes per condition alternated order:

| condition | pack bytes | median exact replay |
| --- | ---: | ---: |
| 1.10x | 2,213,834,789 | 1,122.878ms |
| **1.30x** | **2,258,964,304** | **1,067.301ms** |

The new balanced knee saves another **55.577ms (4.9%)** for 45.1MB beyond the first hybrid. Higher
thresholds continued trading substantially more space for speed, so they belong to the explicit
`fastest` choice rather than the compact default.

## Storage-policy switching no longer discards pack learning

The successful-access sidecar originally recorded codec-bearing blob paths. Switching from
`balanced` to `fastest` changed every `-identity-lz4.spft` path to `-identity.spft`; switching back
therefore matched none of the observations and silently rebuilt the pack in generic manifest order.

Pack ordering now matches first by exact path, then by the content/transformation identity with the
storage suffix removed. Ambiguous or unknown identities are ignored and unseen blobs retain stable
logical order. Tests exercise both raw-to-LZ4 and LZ4-to-raw switches. On the real cache, all 14,774
raw observations translated to their exact LZ4 counterparts as the rebuilt pack prefix.

The unattended layout-recovery gate is
`~/.starsector-preflight/runs/codec-independent-learned-pack-20260806-051144`. It reached the menu in
18.92s, served all 15,469 prepared texture hits plus the same three known dynamic misses, applied all
40 transformations with zero decline/failure, and stopped normally.

## Live mixed-pack gate

`~/.starsector-preflight/runs/balanced-hybrid-pack-20260806-052030` reached the menu in 19.30s and
stopped normally. It served 15,470 pack reads, the same 15,469 game-facing hits and 2,116,422,119
prepared bytes, the same three known misses, and zero pack failure, corruption, quarantine,
prepared-pixel fallback, transform decline, or contained failure. The one launch is a safety gate;
normal launch noise is larger than this optimization's expected whole-run effect.

## Corrected learned-order all-raw result

Before codec-independent order matching, a `fastest` raw pack was tested as an upper bound. The run at
`~/.starsector-preflight/runs/packed-texture-fastest-ceiling-20260806-045938` reached the menu in
20.48s and expanded the exact prepared load seam to 3,175ms, versus 1,736ms in the adjacent balanced
gate. Both policy switches had lost learned ordering under the bug above. That made the comparison
useful for finding the layout defect, not for judging raw storage after the defect was fixed.

With learned order preserved, ten shuffled fresh-game-JVM passes measured 1.10x balanced at
**1,137.457ms** and all-raw at **691.143ms** for the same 14,774 accesses, 2,074,073,333 output
bytes, and checksum. All-raw saves **446.314ms (39.2%)** at the exact seam and grows the pack from
2.214GB to 5.338GB. The real gate at
`~/.starsector-preflight/runs/learned-order-fastest-clean-20260806-053631` reached the menu in a new
record **18.71s**. Its texture-load seam was 1,445ms, all 15,469 prepared hits succeeded, the three
known dynamic misses remained, all 40 transformations applied, and shutdown was clean. `fastest`
is therefore a real speed option; `balanced` remains the default because it retains most of the
runtime win while saving about 3.08GB.
