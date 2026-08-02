# The prepared-blob checksum costs eight times the rest of the read

**Date:** 2026-08-02
**Runtime:** Starsector's bundled Zulu 17.0.10 x86_64 JRE under Rosetta 2
**Machine:** Apple-silicon MacBook Air
**Status:** synthetic hot-cache measurement; implementation tested, real startup not yet rerun

## Why this was measured

A live recording put `SHA2.implCompress0` under
`PreparedTextureIO.fromBytes` in 26.3% of the main thread's execution samples. This is not the
source-image hash removed earlier. It is the checksum inside each `.spft` blob, recomputed over all
prepared pixels as the loading thread serves them.

The blob is already content-addressed, written with an atomic move by Preflight, reached through a
checksum-protected manifest, matched against that manifest after parsing, and structurally checked
while it is read. The remaining guarantee from the payload SHA-256 is detection of a same-length
edit to a local cache blob between preparation and consumption.

## Measurement

The benchmark in
[`2026-08-02-prepared-blob-checksum-benchmark.java.txt`](2026-08-02-prepared-blob-checksum-benchmark.java.txt)
writes one 4096×4096 RGBA identity blob (64 MiB of pixels), warms both readers three times, then
alternates seven verified and trusted reads. The values below are the median of each set. The file
was already in the page cache, so this isolates CPU and memory-copy work rather than disk latency.

```text
blobBytes=67108984 verifiedMedianMs=261.723 trustedMedianMs=33.051 speedup=7.92x
```

This is not a startup-time claim. Blob sizes and allocation/GC behavior vary, and a real launch
serves many blobs rather than one repeatedly. It does establish that the checksum is most of this
read path on the exact runtime whose loading thread pays for it.

## Change

`PreparedTextureIO.read` remains fully checksum-verifying and is still used by builders, cache
validation, inspection commands, and general core lookups. A separate `readTrusted` entrypoint
skips only the SHA-256 comparison while retaining magic, version, total length, payload length,
codec, dimension, channel, pixel-length, EOF, and trailing-data checks.

Only `TextureCompatibilityRuntime`, the measured in-game hot path, uses the trusted read by
default. `-Dpreflight.texture.verifyBlobChecksum=true` restores full per-lookup verification for
diagnostics. Tests state the trade explicitly: truncation and malformed structure still fail by
default; same-length pixel corruption is accepted unless the property is enabled.

## What remains

Run one real startup with the same profile and confirm that `SHA2.implCompress0` disappears from
the `PreparedTextureIO` stack. The stopwatch comparison must use the repository's paired-run
contract; this synthetic result is not a substitute for it.
