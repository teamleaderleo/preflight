# Balanced texture reads reuse bounded compressed scratch

**Date:** 2026-08-06

**Runtime:** Starsector's bundled Zulu 17.0.10 x86_64 JRE under Rosetta 2

**Corpus:** installed 83-mod exact lossless-LZ4 prepared textures

**Status:** exact full-corpus replay, full verification, and two live menu gates pass

## Why this exists

`balanced` is now the default texture-storage policy because its 2.20 GB exact LZ4 corpus launches
within noise of the 5.33 GB raw representation. Its trusted reader still allocated a fresh array
for every compressed payload, decompressed into the final pixel array, and immediately discarded
the compressed array. Across all 30,638 installed LZ4 blobs that is 2,200,772,280 bytes of transient
heap allocation in addition to the pixels the caller actually needs.

The trusted LZ4 path now grows one scratch array per serving thread and passes the exact encoded
length to the decompressor. The reviewed corpus's largest encoded blob is 13,637,898 bytes, so the
power-of-two scratch settles at 16 MiB. Retention is capped at 16 MiB per thread; anything larger is
still a one-shot exact array. Raw/`fastest` blobs continue to adopt their exact read array, the
checksum-verifying tooling reader is unchanged, and the blob format and manifest identity do not
change.

The critical edge case is a smaller compressed blob following a larger one: stale bytes in unused
scratch capacity must not enter decompression. A regression test grows the scratch, reads the
smaller blob again, and compares the complete `PreparedTexture` value.

## Alternating bundled-JVM replay

The benchmark source is
`docs/evidence/2026-08-06-balanced-texture-scratch-benchmark.java`. Each observation is a fresh game
JVM. The committed reader and scratch candidate alternate over the same first 5,000 sorted identity
blobs: 359,929,909 stored bytes and 891,820,613 restored pixel bytes. Every result produced checksum
`6466340020396171967`.

| implementation | seven runs, ms | median |
| --- | --- | ---: |
| fresh compressed array per blob | 642.849, 615.103, 629.025, 634.491, 632.905, 627.013, 625.484 | 629.025 ms |
| bounded thread scratch | 591.085, 611.209, 594.933, 607.822, 606.378, 615.450, 610.787 | 607.822 ms |

The scratch reader is **3.4% faster**, saving **21.203 ms** on this subset while eliminating its
359.9 MB of throwaway arrays. The full installed corpus was also read once by both implementations:
all 30,638 blobs, 2,200,772,280 stored bytes, and 5,331,135,254 pixel bytes produced the identical
checksum `6477030001454269313`.

This is primarily allocation, GC-headroom, and fanless thermal evidence. The replay saving scales
to only low hundreds of CPU milliseconds over the complete corpus, and startup overlaps texture
work with other threads, so it is not a whole-launch timing claim.

## Live gates

The final unattended `--fast` gate is
`~/.starsector-preflight/runs/balanced-texture-scratch-clean-20260806-015728`. It reached the menu in
21.61 seconds and left no JVM. The exact balanced cache served 15,469 prepared-pixel hits and
2,116,422,119 source pixel bytes with zero prepared-pixel fallback, dimension fallback, internal
error, blob corruption, or quarantine. The three ordinary entry-missing lookups are the stable
known fallbacks. Adapter health was ACTIVE: all 40 transformations applied with zero decline or
contained failure.

The probe's SIGTERM teardown raced Starsector's still-running OpenAL worker after the menu marker.
The same shutdown-only `UnsatisfiedLinkError` is present in the immediately preceding committed
SpecStore and prepared-audio gates; the wrapper exited 0 and its run collector found no in-game
fatal. It is a probe-shutdown artifact, not texture-path evidence.
