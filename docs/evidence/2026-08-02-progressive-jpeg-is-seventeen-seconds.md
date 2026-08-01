# Progressive JPEG is 17 seconds of the vanilla load

**Date:** 2026-08-02
**Install:** Starsector 0.98a-RC8, 89 mods, macOS 15, M5 MacBook Air
**Method:** direct file census plus pixel-weighted decode sampling on the game's own JVM. No launch.
**Status:** ratio measured and solid; the aggregate is an estimate, and the machine was thermally
constrained, so treat the seconds as indicative and the ratio as the result.

## The count, measured rather than extrapolated

`preflight lint` reports 285 `texture-progressive` findings but truncates its listing at 25 per
rule, so the format split had to be counted directly from file headers:

| format | total | stored progressively | share | bytes |
| --- | ---: | ---: | ---: | ---: |
| PNG | 33,579 | **55** (Adam7 interlaced) | 0.2% | 4.6 MB |
| JPEG | 615 | **238** (progressive) | **38.7%** | 65.5 MB |

The PNG side is negligible. **Nearly two in five JPEGs in this profile are progressive**, and
because JPEGs here are backgrounds they are large: 238 files carry **403.3 megapixels**.

## What it costs

Java's ImageIO decoding the same image both ways, on the game's own x86_64 JVM:

One real file, `armaa_city2.jpg`, 2048x2048:

| | median | min |
| --- | ---: | ---: |
| progressive | 318.8 ms | 311.3 ms |
| baseline | 41.3 ms | 38.1 ms |
| **ratio** | **8.17x** | |

Pixel-weighted across a 13-file sample spanning the size range, then applied to the real 403.3
megapixel total:

| | per megapixel | over 403.3 MP |
| --- | ---: | ---: |
| progressive | 49.2 ms | 19.85 s |
| baseline | 6.3 ms | 2.53 s |
| **ratio / difference** | **7.85x** | **17.32 s** |

Progressive JPEG stores the image as successive refinement passes; a decoder has to walk all of them
and recombine. The refinements exist so an image can appear early over a slow network, which buys
nothing at all for a file read from a local SSD.

## This explains our own result rather than adding to it

The obvious reading -- "17 seconds available" -- is wrong for anyone running Preflight, and the
reason matters.

**543 JPEGs are already in the texture manifest.** In `prepared` mode the adapter reports
`imageDecodesBypassed: 21,652`: ImageIO is not called at all for a cached texture, so the 8x penalty
is not paid at runtime. This cost is **already inside the 25.53 s that the last campaign measured**,
not on top of it.

That is worth more as an explanation than as an opportunity. The
[27-second prefetch wait](2026-08-01-ten-percent-by-not-waiting.md) was the loading thread sleeping
on a single decoder thread, and this says a large part of what that one thread was so busy with:
403 megapixels of progressive JPEG at roughly eight times the necessary cost. The two findings are
the same fact seen from opposite ends.

So the ledger is:

| who | pays the 17 s |
| --- | --- |
| Preflight user, warm cache | **no** -- ImageIO is bypassed |
| Preflight `prepare`, first run | yes, once |
| Preflight user, cache miss or unmanifested texture | yes, for those files |
| **everyone not running Preflight** | **yes, every launch** |

## What to do about it, and what not to

**Do not rewrite files inside mod directories.** They are replaced on the next mod update, the edit
changes checksums other tooling may check, and for JPEG it cannot be done losslessly with what the
JVM ships: re-encoding decodes and recompresses, so pixels change. A lossless progressive-to-baseline
transform is a well-known operation (`jpegtran -copy all`) but it is not available in pure Java, and
adding a native dependency to save a cost our own cache already removes is a bad trade.

The 55 interlaced PNGs *can* be re-saved losslessly, and are worth 4.6 MB and a rounding error of
time. Not worth a tool.

**The finding's real audience is mod authors**, and `preflight lint` already names the files and the
mods. Three mods account for most of it -- Unknown Skies, Kaleidoscope and Arma Armatura. A
one-command `jpegtran` pass on their art would give every one of their users most of 17 seconds back,
whether or not Preflight is involved.

**The second audience is our own `prepare` step**, which pays this once per profile and could be
measurably faster if it decoded these with something other than ImageIO. Unmeasured, and a separate
question.

## Method caveats

- The 17.32 s aggregate scales a 13-file sample by megapixels. Decode cost is not perfectly linear
  in pixel count, so treat it as indicative; the 7.85x-8.17x ratio is the robust part and was
  consistent between the single-file and sampled measurements.
- The machine had no room cooling during this run. Absolute milliseconds may be inflated; a ratio
  between two measurements taken seconds apart is not.
- ImageIO is not necessarily what Starsector uses for every texture; the manifest bypass makes this
  moot for cached paths, but the vanilla-path claim assumes ImageIO, consistent with the profile
  showing ImageIO frames under the texture loader.
