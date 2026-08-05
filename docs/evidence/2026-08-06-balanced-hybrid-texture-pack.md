# Balanced texture storage skips pointless compression without losing learned order

**Date:** 2026-08-06

**Runtime:** Starsector's bundled Zulu 17.0.10 x86_64 JRE under Rosetta 2

**Profile:** Starsector 0.98a-RC8 with 83 enabled mods

## Result

`balanced` now stores a prepared pixel array raw only when its exact LZ4 ratio is below 1.10x.
Everything else remains lossless LZ4. On the reviewed 30,638-blob profile this selects 1,476 raw
blobs and 29,162 LZ4 blobs:

| pack | bytes | change from all-LZ4 |
| --- | ---: | ---: |
| all-LZ4 | 2,204,050,670 | -- |
| balanced hybrid | 2,213,834,789 | +9,784,119 (+0.44%) |
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

The hybrid is **57.903ms faster (4.6%)** at the complete prepared-pack read seam. The controlled
result is causal CPU/I/O evidence, not a claim that a whole launch must fall by exactly 58ms.

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

## Rejected all-raw ceiling

Before the hybrid, a current `fastest` raw pack was tested as an upper bound. The unattended run at
`~/.starsector-preflight/runs/packed-texture-fastest-ceiling-20260806-045938` reached the menu in
20.48s and expanded the exact prepared load seam to 3,175ms, versus 1,736ms in the adjacent balanced
gate. Both policy switches had lost learned ordering under the bug above, so their generic ordering
was comparable. The extra roughly 2.9GB of pack reads dominated removal of LZ4 work. Full raw remains
a supported explicit choice for hardware with a different CPU/storage crossover, but it is not the
general default on this machine.
