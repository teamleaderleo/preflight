# Prepared LZ4 ranges now use one positioned read

**Date:** 2026-08-06

**Runtime:** Starsector's bundled Zulu 17.0.10 x86_64 JRE under Rosetta 2

**Profile:** Starsector 0.98a-RC8 with 83 enabled mods; balanced learned-order pack

## Accepted result

The trusted pack reader previously issued one positioned read for each 88-byte SPFT header and a
second for its encoded pixels. A known `-lz4.spft` range now reads header and compressed pixels once
into the existing bounded thread-local heap scratch, parses in place, and gives Airlift LZ4 the
compressed offset. Raw ranges retain the direct-to-final-array reader.

Ten fresh bundled-JVM pairs alternated the old and candidate paths while replaying the exact 14,774
distinct successful startup accesses. Every pass restored 2,074,073,333 pixel bytes and checksum
`-4767455416646045759`.

| path | fresh-process observations, ms | median |
| --- | --- | ---: |
| two reads | 1273.372, 1203.071, 1203.167, 1129.723, 1064.581, 1107.383, 1125.203, 1103.777, 1121.690, 1130.247 | **1127.463** |
| one read | 1103.491, 1239.134, 1237.126, 1121.456, 1087.121, 1061.117, 1066.235, 1073.680, 1098.592, 1072.994 | **1092.857** |

The exact seam improved **34.607ms / 3.1%**. This is smaller than whole-launch noise and is not a
whole-startup claim. `-Dpreflight.texture.singleReadLz4=false` restores the old reader for controlled
comparison or emergency diagnosis. Structural checks remain; a suffix/codec disagreement throws
and the existing pack circuit breaker falls back to the authoritative loose artifact.

A real direct launch then exercised the built artifact on the same 83-mod profile. It stopped
normally with adapter health `ACTIVE`: 13,160 compressed pack hits used the one-read path out of
15,482 total pack hits, with zero pack failures, zero corruptions, no circuit breaker, no declined
transformations, and no contained failures. Other desktop activity overlapped the run, so this is a
correctness gate only and contributes no whole-launch timing observation.

## Rejected mapping experiment

A broader experiment mapped the 2.26GB pack in read-only 256MiB segments and passed direct input
buffers to Airlift LZ4, avoiding the compressed heap read altogether. Six alternating fresh JVMs
put the existing reader at a 1177.900ms median and the mapped reader at 1282.326ms: **104.426ms /
8.9% slower**, with identical bytes and checksum. Direct-buffer Unsafe access under Rosetta lost
more than mapping saved. The mapping implementation was deleted completely before the accepted
one-read path was written.
