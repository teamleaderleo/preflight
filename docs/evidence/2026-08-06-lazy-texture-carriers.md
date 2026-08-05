# Prepared texture carriers stop copying two gigabytes

**Date:** 2026-08-06  
**Install:** Starsector 0.98a-RC8, current 89-mod profile, macOS, M5 MacBook Air  
**Run:** `lazy-texture-carrier-clean-20260806-033318`  
**Status:** clean main-menu correctness and allocation result accepted; gameplay follow-up remains useful

## The duplicate

SPFT stores the exact bottom-up byte sequence OpenGL consumes. The prepared upload path read that
array and copied it into a direct buffer, but every carrier also allocated a second, top-down heap
array solely to satisfy `BufferedImage` consumers. On the preceding clean launch that compatibility
raster cost 2,116,618,727 bytes across 15,470 carriers even though the rewritten upload path never
read it.

The old 1x1 token-carrier experiment was not a solution: it reported full image dimensions while
only one pixel was addressable, and `com.fs.graphics.oO0O` walked the raster and crashed the load.

## The boundary

The new carrier is full-size and truthful from construction. Its ordinary raster uses a byte
`ComponentSampleModel` over a read-only data buffer that maps top-down raster offsets onto the
bottom-up SPFT view. No pixel array is copied. If another consumer asks for a raster, data snapshot,
writable tile, graphics context, subimage, or mutation API, the carrier lazily builds the same
conventional `DataBufferByte` surface it used to build eagerly.

The full installed game/mod classpath was scanned before the live gate: 29,139 classes in 116
archives. Three classes mention `DataBufferByte`. GraphicsLib's cast is applied to an image it
constructs itself; Starsector's generic image uploader obtains its buffer through `getData()`, which
the carrier routes through conventional materialization; the WebP writer is not on this load path.

The focused and packaged tests establish:

- exact top-down RGB and RGBA reads over bottom-up stored pixels;
- exact unchanged bottom-up bytes supplied to the upload buffer;
- no raster materialization on a direct prepared upload;
- a real `DataBufferByte` after external raster access;
- full-raster walks for both power-of-two and NPOT images;
- original-converter fallback, dimension replay, color replay, and cleanup behavior;
- full `mvn verify` success.

## Live result

| counter | eager carrier | lazy carrier |
| --- | ---: | ---: |
| carriers | 15,470 | 15,470 |
| raster materializations | 15,470 | **1** |
| carrier raster bytes | 2,116,618,727 | **196,608** |
| bytes eliminated | — | **2,116,422,119** |
| prepared hits | 15,469 | 15,469 |
| upload bytes | 2,116,422,119 | 2,116,422,119 |
| padded uploads / padding bytes | 0 / 0 | **0 / 0** |
| prepared fallbacks / internal errors | 0 / 0 | **0 / 0** |
| active / pending buffers at shutdown | 0 / 0 | **0 / 0** |

Only 196,608 bytes, **0.009%** of the old compatibility allocation, materialized. The exact stored
pixel byte count was unchanged, which is independent evidence that the optimization removed a copy
rather than pixel data.

The adjacent clean gates were 23.75 seconds eager and 23.00 seconds lazy. One adjacent pair is not a
timing campaign, so the accepted claim is the exact 2.116 GB allocation/copy reduction—not a
0.75-second startup improvement. Lower allocation and memory traffic should also reduce CPU heat and
GC pressure on lower-end machines, but that transfer remains an inference until measured there.
