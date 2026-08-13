# Prepared audio now reaches OpenAL without redundant heap copies

**Date:** 2026-08-06
**Runtime:** Starsector's bundled Zulu 17.0.10 x86_64 JRE under Rosetta 2
**Corpus:** installed 83-mod prepared-audio cache
**Status:** exact replay, full verification, and live main-menu gate pass

## Why this was measured

The trusted prepared-audio reader had already stopped recomputing SHA-256 over 1.23 GB of PCM, but
it still materialized the same bytes repeatedly:

1. the complete `.spau` file;
2. a separate payload array;
3. a separate PCM array read from that payload;
4. the defensive PCM clone owned by `PreparedAudio`;
5. another defensive clone returned to the runtime;
6. the required direct buffer consumed by OpenAL.

Only the immutable model storage and final direct buffer are necessary on this private cache-read
path. `readTrusted` now reads fixed metadata separately and streams PCM directly into a fresh array
that `PreparedAudio` adopts. The runtime copies that array directly into OpenAL's required direct
buffer. The public constructor and `pcmBytes()` accessor remain defensive.

For the recorded launch's 1,226,415,962 served PCM bytes, this removes four redundant PCM-sized heap
arrays, or about **4.91 GB of transient heap traffic**. The direct OpenAL copy remains unavoidable.

## Exact game-JVM replay

The benchmark source is
`docs/evidence/2026-08-06-prepared-audio-direct-read-benchmark.java`. Each observation is a fresh
process on Starsector's bundled x86 JVM. The legacy JAR and candidate JAR alternated over the same
519 blobs: 297,083,426 file bytes and 296,987,930 PCM bytes. Every run produced checksum
`3432287904825204693`.

| implementation | seven runs, ms | median |
| --- | --- | ---: |
| legacy copies | 593.261, 384.338, 388.587, 400.104, 394.112, 400.510, 394.604 | 394.604 ms |
| direct read/copy | 220.167, 218.149, 214.311, 212.173, 217.816, 218.462, 218.179 | 218.149 ms |

The exact seam is **1.81x faster**, saving **176.455 ms (44.7%)** on this 297 MB subset. The first
legacy result is a cold outlier but remains in the reported median; excluding it barely changes the
legacy midpoint (394.358 ms).

This is a CPU/allocation result, not a startup-wall-time claim. Linear scaling suggests about 0.73
CPU-seconds over the launch's full logical served volume, but two loader threads overlap this work
with other startup phases.

The final unattended `--fast` gate reached the main menu in **22.36 seconds**, then stopped the game
gracefully and left no JVM. It served 2,049/2,050 intercepted decodes from cache, moved
1,226,415,962 PCM bytes through the direct path, used the expected one vanilla fallback, and
reported zero prepared-audio failure. Adapter health was ACTIVE: all 40 exact transformations
applied with zero decline or contained failure, and the run collector found no fatal condition.
This is the coolest single diagnostic seen so far, but it is not a controlled cohort and therefore
does not establish a new startup median.

## Fidelity and failure boundary

An all-blob comparison ran the checksum-verifying reader and the direct trusted reader over every
distinct installed blob and compared complete `PreparedAudio` values:

```text
PASS files=2020 pcmBytes=1212686724
```

The trusted path still rejects a bad magic/version, invalid or changing file length, malformed
payload or PCM lengths, invalid enums or dimensions, inconsistent frame/sample counts, truncation,
non-files, and symlinks. It still consumes the stored checksum bytes to prove EOF, but—as before—does
not recompute either checksum. The runtime continues to match the embedded source, decoder, policy,
and exact 16-bit little-endian PCM shape before serving. Any problem falls through to vanilla decode;
`PreparedAudioIO.read` remains checksum-verifying for builders and inspection tools, and
`-Dpreflight.audio.verifyBlobChecksum=true` restores runtime verification for diagnostics.
