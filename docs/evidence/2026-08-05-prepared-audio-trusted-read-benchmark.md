# Prepared-audio checksum verification costs eight times the rest of the read

**Date:** 2026-08-05  
**Runtime:** Starsector's bundled Zulu 17.0.10 x86_64 JRE under Rosetta 2  
**Corpus:** 618 real prepared-audio blobs, 268,587,380 bytes  
**Status:** synthetic hot-cache measurement; focused tests pass; paired startup run pending

## Why this was measured

A live startup recording attributed 452 ten-millisecond execution samples to
`PreparedAudioIO.fromBytes` hashing prepared PCM payloads. Another 58 samples hashed the encoded
audio to select the content-addressed blob; that source hash remains necessary. The payload pass is
different: Preflight wrote the blob atomically, the runtime selects it by the exact source and
decoder identities in its path, and parsing still checks those identities plus the entire bounded
audio shape.

The launch serves 2,049 blobs containing 1,226,415,962 PCM bytes. Recomputing SHA-256 over that
corpus on Starsector's two audio loader threads duplicates integrity work on the critical path.

## Measurement

The benchmark warmed both readers, then alternated three verified and trusted passes over the first
268.6 MB of the installed cache. Each reader parsed the complete blob and constructed the same
`PreparedAudio`; only the payload SHA-256 comparison differed.

```text
round=1 files=618 blobBytes=268587380 verifiedMs=1124.920 trustedMs=134.711 speedup=8.35x
round=2 files=618 blobBytes=268587380 verifiedMs=1090.575 trustedMs=135.090 speedup=8.07x
round=3 files=618 blobBytes=268587380 verifiedMs=1060.454 trustedMs=132.470 speedup=8.01x
```

This is not a startup-time result. Linear extrapolation only says that the recorded 1.21 GB corpus
contains about 4.2--4.5 CPU-seconds of avoidable checksum work. Because the game uses two loader
threads and overlaps audio with other loading, only a paired live run can establish the wall-clock
recovery.

## Change and boundary

`PreparedAudioIO.read` remains checksum-verifying for builders, cache inspection, and validation.
The game runtime uses `readTrusted`, which retains magic, version, size, payload length, enum,
sample-rate, channel, frame, PCM-length, EOF, and trailing-data checks. It additionally matches the
blob's embedded source, decoder, and policy identities to the exact lookup; the old runtime did not
perform that cross-key check. `-Dpreflight.audio.verifyBlobChecksum=true` restores the payload hash
for diagnostics.

The omitted guarantee is detection of a same-length edit to an atomically-written local PCM blob.
A truncation, malformed structure, wrong identity, wrong audio shape, missing file, or incompatible
format still fails open to the game's decoder.
