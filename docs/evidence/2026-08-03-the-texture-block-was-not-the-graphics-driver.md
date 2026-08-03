# The texture block was not the graphics driver

**Date:** 2026-08-03
**Install:** Starsector 0.98a-RC8, 83 enabled mods, macOS 26.5, M5 MacBook Air (10 cores), 24 GB
**Runs:** `~/.starsector-preflight/runs/prepared-audio-20260803-162430`,
`seam-gaps-20260803-165632`, `fewer-copies-20260803-170154`
**Status:** an earlier claim corrected, two candidate optimisations killed by measurement, one small
one landed, and a way to stop guessing at this.

## The claim being corrected

After the audio work, the largest remaining block was described like this:

> the per-texture floor (21,657 textures × ~0.4 ms ≈ 8.5 s, needs fewer textures or batched GL)

The 21,657 and the 0.4 ms are right. **The conclusion is wrong.** Almost none of that time is the
graphics driver, so batching GL calls would not have moved it, and the work it implied -- loading
fewer textures at startup -- would have been spent against the wrong thing.

## Where the 0.4 ms came from, and why it was not what it looked like

Charging each gap between consecutive `[main]` log lines to the logger that spoke last gives
`com.fs.graphics.TextureLoader` 13.12 s of a 36.33 s main thread, over 21,657 textures, all
distinct, none loaded twice. 92% of them cost 1 ms or less and together account for 7.50 s, which
reads as a fixed charge per texture rather than a charge per pixel -- and that part is true:

| | textures | seconds | mean |
| --- | ---: | ---: | ---: |
| 32,768 pixels and under | 18,041 | 10.59 | 0.587 ms |
| over 32,768 pixels | 3,614 | 7.13 | 1.97 ms |

What is not true is that the charge belongs to the texture. **A gap after a `TextureLoader` line is
only "texture time" if nothing else happens in it**, and the game logs nothing while it does
anything else.

## Asking the driver instead

The game's own sequence is `glGenTextures`, `glBindTexture`, two `glTexParameteri`, and one
`glTexImage2D` of `GL_RGBA`/`GL_RGB` with `GL_UNSIGNED_BYTE`, at power-of-two dimensions. Replaying
the launch's own 21,655 textures, in the launch's own order, at the launch's own padded sizes,
through the game's own `lwjgl.jar` on this machine's GL:

| | |
| --- | ---: |
| the launch's `TextureLoader` block | 17.72 s |
| **the same uploads, driver only** | **1.15 s** |
| the same uploads with no power-of-two padding | 0.85 s |

3.92 GB uploaded at 3.4 GB/s. The fixed part of a GL upload is **5.3 µs** -- `glGenTextures` alone
is 0.03 µs, plus bind and two parameters 0.5 µs, and a 1×1 `glTexImage2D` takes it to 5.3 µs. Not
0.4 ms.

**Two ideas die here.** Batching or reducing GL calls has at most ~1 s to win. And removing the
1.39 GB of power-of-two padding -- 35.5% of every byte uploaded -- buys 0.30 s of driver time, which
is not worth touching the allocation contract for. Padding remains worth removing for VRAM, which is
a different argument on a different budget.

`GL_BGRA` with `GL_UNSIGNED_INT_8_8_8_8_REV` was measured too, on the theory that macOS stores BGRA
natively and swizzles on upload. It is within noise of `GL_RGBA` at every size from 1×1 to 2048×2048.
The Metal translation layer does not care.

## Then the whole sequence, offline

Reading the prepared blob, cloning, allocating the direct buffer, padding, uploading, the reflective
buffer teardown, and the log line -- every stage the game performs, over the same corpus:

| | seconds |
| --- | ---: |
| everything | **3.36** |
| ... without the `log4j` line | 2.90 |
| ... without the reflective cleaner | 3.18 |
| ... without the GL upload | 1.82 |
| reading and parsing the blobs alone | 1.71 |

3.36 s against the launch's 17.72 s. Whatever the rest is, it is not the work of loading a texture.

## Measuring it instead of inferring it

The seam already knew how many textures it served and how many bytes it handed over. It did not
know how long anything took, which is why the log timestamps were being asked a question they
cannot answer. `SeamTimer` records, per seam, the time from each entry to its own exit, and the gaps
between one exit and the next entry on the same thread, bucketed by size -- so the split between
"loaded the next texture straight away" and "went and did something else for two seconds" is visible
rather than chosen by a cutoff.

One launch, `--fast`:

| | calls | inside | between |
| --- | ---: | ---: | ---: |
| `TexturePreparedPixelRuntime.load` | 21,656 | **7.39 s** | 26.65 s |
| `TexturePreparedPixelRuntime.prepare` | 21,652 | **0.56 s** | 33.30 s |

and the between-call gaps for `load`:

| gap | count | seconds |
| --- | ---: | ---: |
| under 100 µs | 12,738 | 0.64 |
| under 300 µs | 4,473 | 0.77 |
| under 1 ms | 3,246 | 1.74 |
| under 10 ms | 1,137 | 2.90 |
| under 100 ms | 47 | 1.40 |
| **over 100 ms** | **14** | **19.19** |

Fourteen gaps hold 19.19 s. Those are the phase boundaries -- spec store, mod callbacks -- with no
textures in them at all, and the log-gap method had no way to tell them from a slow texture. The
texture window is the other 14.85 s, of which 7.95 s is inside this seam and the rest is the game's.

## What the 7.39 s is not

Serving one texture takes four filesystem metadata operations before it reads anything: a
`toRealPath` on the source, a `readAttributes` on the source, a `toRealPath` on the blob, and an
`isRegularFile`. Two of those resolve every component of a path running through
`Contents/Resources/Java/../../../mods/<Some Mod>/graphics/...`, which looked expensive.

| over 21,655 textures | seconds |
| --- | ---: |
| `toRealPath` on the source | 0.17 |
| `readAttributes` on the source | 0.03 |
| `toRealPath` + `isRegularFile` on the blob | 0.18 |
| reading the blob itself | 1.17 |

0.38 s. Not that either, and a listing-based index would save less than it costs.

## What did land

Serving a texture copied its pixels six times: off the stream into the payload array, out of that
into the pixel array, again in the constructor's defensive clone, again in `pixels()` to build the
readable raster, again into the flipped raster, and again into the upload buffer. Three of those are
`clone()` calls whose results are read once and dropped -- **7.6 GB of allocation and copying per
launch** on the reviewed profile.

`PreparedTexture.pixelsView()` returns a read-only `ByteBuffer` over the stored pixels for callers
that only read them, and `PreparedTexture.adopting(...)` lets the blob reader hand over an array it
just allocated instead of having it copied. Read-only rather than the array itself: immutability is
what makes the class safe to share.

| | before | after |
| --- | ---: | ---: |
| `load` inside the seam | 7.39 s | **7.00 s** |
| `prepare` inside the seam | 0.56 s | **0.45 s** |
| main menu | 35.68 s | **34.94 s** |

0.5 s off the seam, on identical counts -- 21,652 hits, 3 fallbacks, 0 corruptions, 0 internal
errors, 2,529,826,083 bytes served, both runs. Less than 7.6 GB of copying suggests, which is its
own answer: on this machine a large `System.arraycopy` is nearly free, and the remaining 7.00 s is
not copying either.

## The machine underneath

The game runs Zulu 17.0.10 **x86_64 under Rosetta**, and cannot do otherwise: `liblwjgl.jnilib` and
`openal.dylib` ship x86_64 only, so an arm64 JVM has no natives to load. Same work, same corpus, on
the game's JVM against a native arm64 JDK 21:

| | Rosetta x86_64 | native arm64 | |
| --- | ---: | ---: | ---: |
| read + parse 2.53 GB of blobs | 1.12 s | 0.80 s | 1.4x |
| full serve of the same | 1.36 s | 1.12 s | 1.2x |
| regex + split + map | 0.20 s | 0.14 s | 1.4x |
| integer mixing | 0.41 s | 0.41 s | 1.0x |
| **SHA-256 over 512 MB** | **1.80 s** | **0.18 s** | **10x** |

Tight integer work is free -- Rosetta translates it once and it runs at native speed. Copying and
branchy work pay 20-40%. **Hashing pays 10x**, because the x86 SHA intrinsics are not available and
the JVM falls back to software.

That last row is a standing constraint, not a curiosity. It is the same 285 MB/s that made a
redundant SHA-256 cost 4.4 s of a 10.21 s audio serve. Blob checksum verification is off at launch
for this reason; turning it on would add roughly 8.9 s for 2.53 GB of textures.

## Reproduction

```bash
scripts/probe-launch.sh --label seam-timing -- --fast
```

The three numbers are in `adapter.json` under `textureCompatibility.preparedPixels`, as
`loadInsideMillis`, `loadBetweenByGapSize`, and the same pair for `prepare`.
