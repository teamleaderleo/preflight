# Balanced texture storage removes 3.13 GB without a measurable startup regression

> **Later update:** `balanced` now leaves blobs raw when LZ4 saves less than 23.1%. A threshold
> sweep selected that compact knee, and codec-independent learned ordering made `fastest`
> materially faster than the earlier poisoned-layout test. The original all-LZ4 cohort below
> remains the evidence that established the policy; current behavior is documented in
> `2026-08-06-balanced-hybrid-texture-pack.md`.

**Date:** 2026-08-06
**Profile:** Starsector 0.98a-RC8, 83 enabled mods, macOS on Apple M5

## Result

Prepared textures now support two exact storage policies:

| policy | representation | real blob bytes | behavior |
| --- | --- | ---: | --- |
| `fastest` | raw RGB/RGBA | 5,335,292,414 | minimum runtime CPU |
| `balanced` (default) | lossless LZ4 | 2,200,772,280 | 3.13 GB smaller |

Both policies reconstruct byte-identical upload-ready pixels. `balanced` reduces this profile's
texture store by **58.8%**, from 4.97 GiB to 2.05 GiB. Its one-minute-cooled five-launch cohort had a
23.15s median, only 0.07s above the adjacent raw cohort's 23.08s median; that difference is below
normal launch noise.

## Why LZ4

A standalone full-corpus replay measured all 30,639 unique prepared pixel arrays with the same
pure-Java Aircompressor implementation used by Preflight:

| runtime / codec | stored bytes | ratio | encode | decode |
| --- | ---: | ---: | ---: | ---: |
| native arm64 / LZ4 | 2,197,372,392 | 2.426x | 4.437s | 1.145s |
| native arm64 / Zstandard | 1,635,009,747 | 3.261x | 19.304s | 4.435s |
| Starsector x86 JVM under Rosetta / LZ4 | 2,197,372,392 | 2.426x | 5.451s | 1.533s |
| Starsector x86 JVM under Rosetta / Zstandard | 1,635,009,747 | 3.261x | 21.743s | 5.318s |

Zstandard saved another 562 MB, but cost 3.5x the Rosetta decode time and 4.0x the preparation
encode time. LZ4 is the appropriate balanced tier; a future maximum-compression tier can remain a
separate explicit policy if its actual launch tradeoff proves useful.

The dependency is pure Java and contains no platform-native library, so the format and implementation
are the same on macOS, Windows, and Linux. Aircompressor 2.0.3 is published under Apache 2.0.

## Format and failure boundaries

SPFT already reserved a codec field. Codec zero remains raw and is emitted byte-for-byte as before;
codec one is LZ4. The declared pixel length always describes the decoded RGB/RGBA array, allowing
the reader to validate dimensions and bound allocation before decompression.

Raw and LZ4 blobs use distinct content-addressed names. A policy switch therefore cannot reinterpret
an old artifact, and the active manifest points to exactly the chosen representation. Checked reads
still validate the complete SPFT checksum. The launch-time trusted reader is reached only after the
manifest and source profile have already been validated. Unknown codecs, malformed compressed data,
wrong decoded lengths, corrupt artifacts, and unavailable cache entries all retain the existing
quarantine/fall-through behavior rather than preventing the game from loading.

Validation of existing compressed blobs reserves the full declared decoded size against the
preparation memory budget. This prevents several highly compressible images from passing the budget
as tiny files and then being expanded concurrently beyond it.

## Real preparation

The deep balanced preparation report is:

`~/.starsector-preflight/cache/reports/preparation-balanced-lz4.json`

It recorded:

- 32,920 candidate entries and 30,639 unique contents;
- 30,638 blobs built and one already-known fidelity-gated extended WebP skipped;
- 32,919 entries checked, zero invalid entries, zero failures;
- 5,331,135,254 logical pixel bytes stored in 2,200,772,280 artifact bytes;
- 151.650s for the first full compression build.

Repeating the same preparation is cache-hit work. Returning to `fastest` reused the retained raw
blobs and completed the texture stage in 7.04s.

## Live launch gate

The profiled balanced launch is retained at:

`~/.starsector-preflight/benchmarks/20260806-003317`

It reached the menu in 24.27s and reported 15,469 prepared texture hits, three expected dynamic
misses, 50,880 prefetch skips, zero prefetch items retained, 33 applied transforms, and zero transform
failure. The profile contained 47 main-execution samples in LZ4 decompression; native file reads
were not higher than the nearby raw profile (136 versus 152 samples).

The following cooled cohort is retained at:

`~/.starsector-preflight/benchmarks/20260806-003416`

| run | seconds |
| ---: | ---: |
| 1 | 22.59 |
| 2 | 23.21 |
| 3 | 23.14 |
| 4 | 23.15 |
| 5 | 23.18 |
| **median** | **23.15** |

The adjacent raw cohort was 23.19/22.88/23.08/23.09/22.54s, or 23.08s median. This is not a
shuffled A/B, so the defensible statement is that balanced storage showed no measurable startup
regression—not that compression made launch faster.

The 58.8% space reduction and lack of a measurable regression make `balanced` the general default.
The format is platform-independent, but the launch cohort above is macOS/Rosetta evidence rather
than a universal AMD/Intel timing result. Lower-end CPUs could make LZ4 decode more visible, so
`fastest` remains an explicit supported choice instead of being removed.

## User operation

```bash
java -jar preflight.jar prepare --texture-storage fastest
java -jar preflight.jar prepare --texture-storage balanced
```

The default is `balanced`. Existing installations keep using their currently published manifest
until preparation runs again; the default change does not rewrite a live cache during launch. A
switch publishes the new manifest only after preparation succeeds. Old blobs remain recoverable
until explicitly cleaned:

```bash
java -jar preflight.jar cache prune
java -jar preflight.jar cache prune --yes
```

The first command is a read-only plan. Prune refuses if it cannot prove the current manifest's
reachable set, and the second command removes only what that successful plan found unreachable.
