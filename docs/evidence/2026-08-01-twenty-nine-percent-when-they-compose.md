# Twenty-nine percent, when the two bypasses compose

**Date:** 2026-08-01 (campaign ran into 2026-08-02 local time)
**Install:** Starsector 0.98a-RC8, 77 enabled mods, macOS 15, M5 MacBook Air (10 cores), 24 GB
**Session:** `~/.starsector-preflight/benchmarks/20260801-225218`
**Repository head:** `30c8319`
**Protocol:** direct (unattended), 240s cooldown before every launch, discarded settling launch, 5 rounds x 3 conditions
**Status:** `benchmarkAccepted: true`, 15 of 15 runs, **no exclusions**

## The result

| condition | n | median | min | max | range |
| --- | --- | --- | --- | --- | --- |
| `vanilla` | 5 | 88.13s | 87.22 | 89.97 | 2.75 |
| `fast` (cache + prefetch bypass) | 5 | 72.25s | 71.52 | 72.87 | 1.35 |
| **`prepared`** (pixel bypass + prefetch bypass) | 5 | **62.60s** | 61.48 | 63.74 | 2.26 |

| comparison | delta | p | isolates |
| --- | --- | --- | --- |
| **`prepared` vs `vanilla`** | **+25.53s (29.0%)** | 0.048 | what a user feels, best path |
| `fast` vs `vanilla` | +15.88s (18.0%) | 0.048 | the cache and prefetch bypass alone |
| `prepared` vs `fast` | +9.65s (13.4%) | 0.048 | the pixel bypass, everything else held |

Paired within rounds:

| round | prepared | fast | vanilla | prepared vs vanilla |
| --- | --- | --- | --- | --- |
| 1 | 63.74 | 71.63 | 87.71 | -23.97s |
| 2 | 61.94 | 72.25 | 88.13 | -26.19s |
| 3 | 62.60 | 72.87 | 89.97 | -27.37s |
| 4 | 63.20 | 72.28 | 87.22 | -24.02s |
| 5 | 61.48 | 71.52 | 89.69 | -28.21s |

**`prepared` beats `vanilla` 5 rounds of 5, mean -25.95s, sd 1.93.** It beats `fast` 5 of 5, mean
-9.52s, sd 1.04. `fast` beats `vanilla` 5 of 5. Every ordering is unanimous, and the three
conditions do not overlap at all -- the slowest `prepared` run is 7.8s faster than the fastest
`fast` run, which is 15.6s faster than the fastest `vanilla` run.

Launch-order drift across the whole campaign was **-0.04s**, 0.0% of variance. The machine did not
move.

## The control that makes it believable

`vanilla` was measured on the same install, the same profile and the same machine ten hours earlier
in [the previous campaign](2026-08-01-ten-percent-by-not-waiting.md):

| condition | previous campaign | this campaign | shift |
| --- | --- | --- | --- |
| `vanilla` | 88.49s | 88.13s | **-0.36s** |
| `fast` | 78.93s | 72.25s | -6.68s |
| `prepared` | 87.89s | 62.60s | **-25.29s** |

The untouched condition moved by 0.4%. So the other two moved because the code did, and by how much
the code did.

## What changed between the campaigns

Two commits, both landed the same day:

1. **[`b75c5b2`] `prepared` can take the prefetch bypass.** It previously could not: the mode
   answered with a 1x1 carrier that reported the texture's real dimensions, a token only the
   rewritten conversion could read, and widening the paths it served handed that token to
   `com.fs.graphics.oO0O` -- a greyscale-to-alpha mask converter that walks the raster -- and
   crashed the load at 23.6s. Every carrier is now a readable raster unconditionally. The published
   objection to doing this, that it "costs a materialisation the mode exists to avoid," was wrong:
   6,123 of 6,651 carriers already paid it, because every NPOT texture took the coherent path
   already.
2. **[`9844be9`] The per-lookup source SHA-256 is off the loading thread.** Once the prefetch wait
   was gone it was **40.9% of `main`'s on-CPU samples** -- up to 1.34 GB of PNGs hashed per launch
   at 292 MB/s, because the game ships an x86_64 JRE and Rosetta 2 exposes no SHA-NI, so
   `UseSHA256Intrinsics` can never fire. Replaced by the size-and-mtime staleness check
   `configure()`'s index validation already performs.
   ([why](2026-08-01-the-game-runs-under-rosetta.md))

Change 2 alone accounts for the `fast` improvement, since `fast` gained nothing else: **-6.68s**.
Change 1 is what turned `prepared` from the losing condition into the winning one.

## Telemetry from the final `prepared` run

```
prefetchSkipped              50879     enqueues taken off the game's queue
prefetchKept                     1
attempts                     21656
hits                         21652     2.53 GB served
fallbacks                        3     entry-missing
npotProbeFallbacks               0
dimensionFallbacks               0
internalErrors                   0
circuitBreakerActive         false

conversionCallsBypassed      21652
imageDecodesBypassed         21652
derivedColorCalculations…    21652
uploadBytesSupplied     3923988688     3.92 GB handed straight to glTexImage2D
carrierRasterBytes      2530022691
paddedUploads                17525
paddingBytes            1394162605     35.5% of every upload is zero padding
peakDirectBytes           25165824     25 MB high-water mark, cap 64 MB
pendingBuffers                   0     nothing leaked
```

Both bypasses fired in the same run: 50,879 skipped enqueues *and* 21,652 bypassed pixel
conversions. That is the composition, and the campaign gate independently verified it -- a
`prepared` run that failed either check would have been excluded as
`prepared-pixels-served-nothing`, and none were.

## What this does not say

- **One machine, one profile, one scenario.** `main-menu-v1`, 77 mods, an M5 under Rosetta. The
  SHA-256 removal is worth far less on a machine whose JVM has the intrinsic, and the whole result
  is proportional to how many textures a profile loads.
- **p = 0.048 is the floor of a 5-round permutation test**, not evidence of a large effect. The
  unanimous pairing and the non-overlapping ranges are the stronger claim.
- **It measures to the main menu.** Nothing here says anything about frame rate, campaign
  performance, or save loading -- and save loading is untouched by every one of these changes.
- **`vanilla` still includes the recorder-free launcher path.** The comparison is Preflight-off
  versus Preflight-on, not Starsector-alone versus Preflight.

## What is next in this profile

Unchanged from the Rosetta write-up, and now the largest remaining items:

1. **The game's own path resolution** -- `File.exists` across 77 mod roots per resource, when
   `ResourceIndex` already knows the winner.
2. **The JSON/spec path**, entirely untouched.
3. **1.39 GB of zero padding** uploaded to VRAM, which the unpadded fold would reclaim.
