# Trusted prepared textures now read once

**Date:** 2026-08-06

**Install:** Starsector 0.98a-RC8, 83 enabled mods, macOS on Apple M5 under Rosetta

## Finding

The prepared-texture serving path called `Files.readAllBytes()` and then decoded the payload through
a `ByteArrayInputStream`. Even after the `PreparedTexture` constructor's defensive copy had been
removed, `DataInputStream.readNBytes(pixelLength)` still copied every pixel from the whole-file
array into a second final pixel array.

The current profile serves **2,116,422,119 prepared pixel bytes** before the main menu. The complete
manifest contains 30,638 unique blobs and **5,331,135,254 pixel bytes**, so the redundant copy was
large enough to matter for allocation, memory bandwidth, GC pressure, and sustained thermals even
if storage I/O remained the wall-clock limit.

## Change

`PreparedTextureIO.readTrusted()` now opens a `FileChannel`, reads the fixed 88-byte header and
metadata, validates the same magic/version/length/dimension/channel/codec/trailing-data invariants,
and reads pixels directly into the final array adopted by `PreparedTexture`. The checked tooling
path is unchanged: `read()` still reads and verifies the complete payload checksum. The runtime
path remains fail-open because malformed trusted blobs still throw `IOException` and are
quarantined by the existing texture compatibility runtime.

## Real-cache micro-benchmark

A fresh JVM read every unique blob referenced by the real 32,919-entry manifest with `-Xmx2g`.
Runs alternated old/new implementations against the same cache:

| implementation | pass 1 | pass 2 |
| --- | ---: | ---: |
| old whole-file plus payload copy | 4.891s (1,039 MiB/s) | 1.831s (2,777 MiB/s) |
| direct-to-final-array | **0.817s (6,221 MiB/s)** | **0.815s (6,238 MiB/s)** |

The first pair includes different filesystem-cache state, so the retained claim is the stable warm
comparison: **1.831s -> 0.815s** for 5.33 GB of pixels, plus one full manifest-sized allocation and
copy removed. This benchmark isolates cache reading; it is not startup wall-clock attribution.

## Live cohort

The five one-minute-cooled unattended launches are
`~/.starsector-preflight/benchmarks/20260806-001245`:

- 23.19, 22.88, 23.08, 23.09, and 22.54 seconds;
- **23.08-second median**, 0.65-second range;
- every run served 15,469 prepared textures and bypassed 2.12 GB of image decoding;
- every run applied all 33 exact transformations with zero contained failure and stopped
  automatically.

The adjacent prior cohort's median was 23.03 seconds, so there is no defensible median wall-time
shift. The 22.54-second run is a new observed minimum, but one sample is not a speed claim. This is
retained as a measured CPU/allocation/thermal-headroom improvement whose end-to-end wall effect is
below current launch noise.
