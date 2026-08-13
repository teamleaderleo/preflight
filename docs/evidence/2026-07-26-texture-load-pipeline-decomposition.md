# What loading a texture actually costs (2026-07-26)

Two documents now claim that pre-encoded block textures belong on the *speed* track rather than
opposite it, on the reasoning that they remove the decode stage entirely. That was reasoning, not
measurement. This is the measurement.

Captured with `probe-kits/texture-pipeline` against the reviewed 78-mod profile: 295 real mod
textures, 10.3 M source pixels, run on the game's own bundled JVM (x86_64 Zulu 17.0.10, under
Rosetta) and its own GL context.

## Where the time goes

Three consecutive runs, reported as a range because a single run is not evidence of a stable ratio.

| stage | time | share |
|---|---|---|
| read PNG from disk | 26.1–27.3 ms | 2.9–3.1% |
| **ImageIO decode** | **560.9–649.8 ms** | **66.9–70.1%** |
| **raster walk + pad to power of two** | **226.7–255.0 ms** | **24.5–27.7%** |
| `glTexImage2D` (RGBA) | 19.4–23.9 ms | 2.3–2.6% |
| *(`glCompressedTexImage2D`, for comparison)* | *~12 ms* | *~1%* |

**CPU work is 94.6% of a texture load, in every run. The GPU upload is under 3%, and disk is
about 3%.**

That settles a question that had been assumed rather than checked. The bottleneck is neither the bus
nor the disk; it is decoding PNGs and walking rasters on one core. Anything that removes that work is
the whole game, and anything that only reduces bytes uploaded is nearly irrelevant to load time.

## The three pipelines

Vanilla is measured end to end. The two cache pipelines are modelled, using two separately measured
constants for disk rather than one figure scaled by size (see the methodology note below).

| pipeline | load | bytes read | VRAM | vs vanilla |
|---|---|---|---|---|
| vanilla | 838.8–927.2 ms | 12.2 MiB | 80.3 MiB | 1.00× |
| prepared pixels (preflight today) | ~55 ms | 80.3 MiB | 80.3 MiB | ~16× |
| **block cache** | **12.6–13.7 ms** | **20.1 MiB** | **20.1 MiB** | **61–74×** |

**A block cache strictly dominates the prepared-pixels cache on every axis at once**: it removes the
same decode work, reads a quarter of the bytes, and leaves a quarter of the video memory resident.
There is no trade to weigh. That is an unusual position to be in and it is the main result here.

The claim under test survives: pre-encoded blocks are a speed-track change, not a footprint change
that costs speed.

## Two things this surfaced that were not being looked for

**The power-of-two padding costs load time, not just memory.** The raster stage — about a quarter of
the pipeline, its second largest cost — includes allocating and filling a power-of-two buffer that is on
average substantially larger than the image. The padding was previously priced only as
[1.86 GiB of resident VRAM](2026-07-25-macos-gl-capability-probe.md). It is also paying a share of the
single largest block of CPU work at load. Removing it improves both, which strengthens a case that was
already the cleanest one available.

**Files inside the `.app` bundle read 1.5–3.7× slower than the same files outside it.** Reading the
same 283 textures from `/Applications/Starsector.app/mods` cost 25–63 µs per file against 16–17 µs
from a plain directory. On macOS the mods directory lives inside a signed application bundle, and the
system appears to be doing per-file validation work there. At 2.9% of load this is not a lever worth
pulling, but it is a real effect and worth knowing before anyone attributes it to something else.

## Methodology, including two mistakes worth recording

**Stages must be measured in separate passes.** The first version interleaved read, decode and encode
per texture. Reads then appeared to cost **742 µs per file**; measured in isolation the same reads
cost **63 µs**. The interleaved figure was really measuring garbage collection and page-cache
eviction caused by the decode and encode work surrounding it — an eleven-fold error, and one that
would have made disk look like a quarter of load time instead of three percent.

**Disk is two constants, not one.** Scaling a measured PNG read rate by blob size implies reads cost
only per byte. They do not: a read has a fixed per-file cost and a per-byte cost, and which dominates
flips between a mod tree of thousands of small files and a cache of a few large ones. Both were
measured on this machine — **2.90 GB/s** sequential, **17.8 µs** per warm file — and the cache
pipelines above are modelled as a single sequential pack. Modelling them as many small files instead
would cost the prepared-pixels pipeline far more than the block cache, since it reads four times the
bytes, so this assumption is the one that flatters the incumbent.

**AWT and LWJGL cannot share a process on macOS.** ImageIO initialises CoreFoundation, after which
creating a GL context segfaults inside it — deferring the GL work to a later phase of the same JVM is
not enough. The decode and upload halves therefore run as two processes joined by a handoff file. This
costs nothing in fidelity: upload time depends on how many bytes cross the bus, not on what they
contain, so the upload phase sends correctly sized buffers rather than the real ones.

## Caveats

- The cache rows are **modelled, not measured end to end**. No block cache exists yet; this prices
  one rather than benchmarking it.
- Reads are warm. A genuinely cold launch reads more slowly, which favours the caches further, since
  they read a pack rather than thousands of scattered files.
- Everything was measured **under Rosetta**, which is what the game gets on this machine
  ([why](2026-07-25-macos-rosetta-runtime.md)). A native ARM64 runtime would speed up the CPU stages
  specifically, shrinking the 94.6% somewhat. It would have to shrink enormously to change the
  conclusion.
- Run-to-run variance is real and the ranges above are the honest form of the result. Ordering
  matters too: running the storage probe *first* writes and reads a 256 MiB file, evicts the page
  cache, and roughly doubled measured decode time. The packaged script therefore runs the pipeline
  probe before it.
- The offline bake cost is real: **10.4 s for 10.3 M pixels**, about 1 Mpx/s, after the encoder work
  in [#182](https://github.com/teamleaderleo/preflight/pull/182) traded 4× throughput for
  quality. A full profile is roughly 1.1 Gpx, so a first bake is on the order of 18 minutes
  single-threaded — one time, cacheable, and trivially parallel across textures.

## What this implies for the program

The ordering that follows from this is different from the one the roadmap currently implies. Decode
elimination is worth 16–74×; every footprint lever is worth a few percent of load time and matters
only for VRAM. They are not competing priorities — the block cache happens to be the best available
answer to both, which is why it should come before further shrink work.
