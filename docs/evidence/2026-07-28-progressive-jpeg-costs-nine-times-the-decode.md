# Progressive JPEG costs about nine times the decode (2026-07-28)

The asset linter grew a rule for progressively-encoded images on the reasoning that progressive
formats cost more to decode and buy nothing for art read from a local disk. That was folklore. The
number I had in mind was two or three times. This is the measurement, and it is **8.75×**.

## Why it matters here specifically

[What loading a texture actually costs](2026-07-26-texture-load-pipeline-decomposition.md) measured
the stages of a texture load against this profile, on the game's own bundled JVM:

| stage | share |
|---|---:|
| **ImageIO decode** | **66.9–70.1%** |
| raster walk + pad to power of two | 24.5–27.7% |
| read from disk | 2.9–3.1% |
| `glTexImage2D` | 2.3–2.6% |

Decode is two thirds of the cost, and it is Java's `ImageIO`. So a format choice that multiplies
ImageIO's decode time is not an abstract inefficiency — it lands squarely on the dominant stage.

## The measurement

Controlled: same pixels, same decoder, one variable. For each sample the progressive original was
decoded, re-encoded to baseline through `ImageIO` with progressive mode disabled, and both were
decoded ten times after three warm-up rounds.

15 progressive JPEGs over 100 KB, drawn at random (seed 7) from the profile's mods, 44.6 Mpixel total:

| image | size | progressive | baseline | ratio |
| --- | --- | ---: | ---: | ---: |
| `swp_masteryofwar.jpg` | 2048² | 467.4 ms | 25.0 ms | 18.70× |
| `US_planet_textureAlpine.jpg` | 1024×512 | 276.5 ms | 17.5 ms | 15.83× |
| `armaa_homesystem.jpg` | 2048² | 610.2 ms | 38.9 ms | 15.67× |
| `swp_thecoreissue.jpg` | 2048² | 377.9 ms | 27.6 ms | 13.70× |
| `US_background137n.jpg` | 2048² | 197.2 ms | 24.0 ms | 8.23× |
| `cryovolcanic01.jpg` | 1024×512 | 172.2 ms | 21.1 ms | 8.17× |
| `desert02.jpg` | 1024×512 | 81.9 ms | 25.7 ms | 3.18× |
| `barren03.jpg` | 1024×512 | 38.3 ms | 13.0 ms | 2.95× |

**Total: 4,207 ms progressive against 481 ms baseline — 8.75×.** Every sample was slower, the
smallest margin being 2.95×.

Re-encoding changes no pixels. Progressive and baseline JPEG are the same lossy data in a different
scan order; decoded output is identical.

## How much of the profile this is

| | files | pixels |
| --- | ---: | ---: |
| progressive JPEG | 224 | 388.8 Mpixel |
| interlaced PNG | 55 | 0.7 Mpixel |
| **progressively encoded** | **279** | **389.4 Mpixel** |
| baseline JPEG | 321 | 311.8 Mpixel |

**41% of the profile's JPEGs are progressive**, and they carry more pixels than all the baseline ones
combined. Concentrated, too — 83 files in `US` account for 278 Mpixel, and 54 belong to the base game.

At the rates measured here, 389.4 Mpixel costs about **3.7 s to decode progressively against 0.4 s
baseline: roughly 3.3 s of avoidable decode work.**

## What the number does and does not support

The **ratio** is the robust part. It was measured on one decoder with one variable changed, and every
sample agreed on the direction.

Three things it does not establish:

- **The absolute seconds are host figures.** This ran on the host JVM; the game runs x86_64 Zulu 17
  under Rosetta, where absolute times differ. Treat 3.3 s as an order of magnitude, not a launch-time
  saving.
- **Not all of this art is decoded at startup.** Planet textures and campaign backgrounds are loaded
  when needed. The claim is that 389 Mpixel of art costs about nine times what it needs to *whenever
  it is decoded*, not that 3.3 s is on the critical path to the main menu.
- **8.75× is partly a property of ImageIO.** Other implementations decode progressive JPEG at
  perhaps 2–3× baseline. That does not soften the finding — the game decodes through ImageIO, so
  ImageIO's ratio is the one its users pay — but the number should not be quoted as a general fact
  about progressive JPEG.

## Consequence

`preflight lint` reports these as `texture-progressive`, at warning severity, with the ratio stated.
The fix is re-saving without progressive mode, which is lossless with respect to the decoded image and
needs no change to any code that reads it.

This is the linter's most actionable finding so far: 279 files, a mechanical fix, no judgement call
about art, and it lands on the stage that dominates texture loading.
