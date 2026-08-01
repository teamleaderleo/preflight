# What the load is actually waiting for

**Date:** 2026-08-01
**Install:** Starsector 0.98a-RC8, 77 enabled mods, macOS 15, M5 MacBook Air (10 cores), 24 GB
**Recording:** `20260801-100133/runs/profile-1/startup.jfr`, SAMPLE mode, 99s, direct protocol
**Prompted by:** deleting ~15s of sampled CPU from the loading thread bought 2.68s of wall clock

## The question

The [first valid campaign](2026-08-01-the-first-valid-startup-number.md) showed the prepared-pixel
bypass removing the `BufferedImage`→`ByteBuffer` conversion — a large share of the loading
thread's CPU — and returning 2.68 seconds of a 96-second load. Most of the deleted work was not
in the wall clock. Execution sampling of Java frames says which frames are on a CPU; it cannot
say what a thread is waiting for. This is that measurement.

## The finding

**The machine is 27.8% busy for the whole load.** Averaged over 97 samples of `jdk.CPULoad`,
`machineTotal` is 27.8% — under three of ten cores. Seven sit idle for a minute and a half.

Everything else follows from that.

| where the time is not going | measurement |
| --- | --- |
| stop-the-world GC | **0.00s** of pause across 128 `GCPhasePause` events (Shenandoah, concurrent; 32 collections, 2.3s of concurrent work) |
| GPU upload | `GL11.nglTexImage2D` is **68 of 6,683** samples, ~1% |
| Java-level blocking on the loading thread | `main` does not appear in the blocked ranking at all — no park, no sleep, no monitor wait |
| CPU saturation | 27.8% of ten cores |

So the loading thread is neither waiting on a lock, nor waiting on the GPU, nor stopped by the
collector, nor competing for a busy machine. It is a long serial chain, and its length is the
load time.

## Where the work is

On-CPU Java samples, by thread:

| thread | samples | share |
| --- | --- | --- |
| `main` (the loader, under the direct protocol) | 2123 | 47.4% |
| `pool-1-thread-1` / `-2` (audio decode) | 760 / 752 | 33.8% together |
| `Thread-4` | 526 | 11.7% |

`main`'s own hot frames — this is a compatibility-mode run, so the conversion is still present:

```
  18.9%  java/awt/image/ComponentSampleModel.getPixel      <- the conversion the bypass removes
  13.4%  java/lang/invoke/VarHandleByteArrayAsInts$ArrayHandle.index
   9.6%  com/fs/graphics/TextureLoader.o00000
   4.2%  java/util/LinkedList.indexOf                      \  the O(n) texture-registry scan,
   2.2%  java/util/Collections$SynchronizedCollection.contains  /  6.4% together
   3.5%  com/fs/starfarer/campaign/rules/Rules.super
   6.3%  sun/awt/image/ByteInterleavedRaster get/putByteData
```

Native samples (33% of all samples) are dominated by things that are not the GPU:

```
   552  sun/nio/ch/SocketDispatcher.read0          <- mod version checkers, on their own threads
   353  java/io/FileInputStream.readBytes
   286  java/io/UnixFileSystem.getBooleanAttributes0   <- File.exists()-style probing
   239  com/sun/imageio/plugins/jpeg/JPEGImageReader.readImage
   223  java/util/zip/Inflater.inflateBytesBytes
   106  sun/java2d/cmm/lcms/LCMS.colorConvert
    68  org/lwjgl/opengl/GL11.nglTexImage2D
```

## The allocation number

**~126 GB allocated during a 96-second load**, `main` responsible for ~82 GB of it. Roughly
1.3 GB/s sustained. It costs no pause time — Shenandoah collects concurrently on the idle cores —
but every byte of it is a cache line displaced from under the serial chain that is the critical
path.

Top allocation sites are not what the texture work would suggest:

```
  2952  com/fs/starfarer/loading/LoadingUtils.super     <- JSON loading, 27% of allocation
  1389  com/fs/util/C.Object                            <- resource lookup
  1222  sun/awt/image/ByteInterleavedRaster.getByteData \  texture decode,
  1089  java/awt/image/DataBufferByte.<init>            /  ~21% together
   924  java/util/Arrays.copyOf
```

This lines up with the log's own phase timeline, which splits the load in two rather than one:
`TextureLoader` owns roughly 25–65s and 85–95s, and `LoadingUtils` owns 0–25s and 65–85s. The
JSON path is comparable in both time and allocation to the texture path, and **Preflight does
nothing about it.** The resource index solves *finding* a resource; nothing caches the parsed
result.

## What this means for the project

**The headroom is in the shape of the load, not in the cost of its parts.** Seven idle cores for
ninety-six seconds is the whole story. Every optimization this project has shipped makes one link
of a serial chain cheaper, and the measured return has been proportional to that link and no
more — 2.68s for removing a substantial fraction of the loading thread's CPU is exactly what a
serial chain with a partly-CPU-bound head predicts.

This retires a question asked at the start of this work — whether async, worker pools or
cache-locality tricks could split the serialized load. The measured answer is that the
opportunity is real and large, and that **it is not reachable from where Preflight sits.**
Restructuring the loader's serial chain means changing the loader, not decorating it from the
outside with a fail-open agent.

What remains reachable, in order:

1. **The O(n) registry scan, 6.4% of the loading thread.** `Collections.synchronizedCollection(LinkedList).contains()`
   per texture lookup. It is a bounded, local change to a data structure and the one item here
   that is both on the critical path and shaped like something an adapter can fix. Also the
   cleanest thing to report upstream.
2. **The per-lookup SHA-256, 1.01s on the critical path.** Already designed. It is most of why
   the compatibility cache is a net regression.
3. **A prepared JSON/spec cache.** The largest single allocation site and roughly half the load's
   wall time, entirely unaddressed. Also much riskier than textures, because game logic consumes
   the parsed objects rather than opaque bytes — this is a research item, not a queued one.

What should be dropped: further CPU micro-optimization of the texture path. The conversion was
the largest single item on the loading thread and removing it entirely returned 2.8% of the load.
Nothing else there is bigger.
