# Prepared audio cache

Prepared audio is a production cache domain for deterministic decoded PCM. It stores no live sound objects, OpenAL state, streams, classloaders, decoder instances, or JVM state.

## Eligibility policy

Every audio resource has one explicit policy:

- `FULLY_DECODED_EFFECT` — eligible for decoded PCM reuse
- `STREAMED` — original streaming behavior always runs
- `UNSUPPORTED` — original behavior always runs

Only `FULLY_DECODED_EFFECT` may create or consume a `PreparedAudio` blob. Unknown loader behavior must use `STREAMED` or `UNSUPPORTED` until real evidence proves full startup decoding.

## Exact identity

A prepared blob is addressed by:

- encoded source SHA-256
- decoder-policy identity SHA-256
- eligibility policy

The decoder-policy identity is supplied by the future exact target adapter. Its canonical inputs must include every decoder behavior input, including:

- Starsector build identity
- decoder class name and class SHA-256
- source archive identity
- method name and descriptor
- classloader class and name
- decoder options and output policy
- PCM normalization rules

A changed input creates a different identity. A producer may add stricter inputs and may never omit a required input.

Logical resource paths are excluded from blob addressing, so identical encoded content under multiple paths can reuse one exact prepared payload. Logical paths, source size, source modification time, and winning-provider policy belong in `PreparedAudioManifest`.

## Blob format

Version 1 uses the `SPAU` format. The authenticated payload contains:

- source SHA-256
- decoder-policy identity SHA-256
- policy
- PCM encoding and bit depth
- byte order
- sample rate
- channel count
- frame and sample counts
- PCM byte count
- PCM SHA-256
- raw PCM bytes

The file also contains a SHA-256 of the complete payload. Reads validate both checksums, every count and length, all enum values, sample/frame arithmetic, and a 64 MiB per-effect PCM ceiling.

## Manifest format

Version 1 uses the `SPAM` format. Each deterministic sorted entry records:

- normalized logical path
- encoded source SHA-256, size, and modification time
- policy
- derived cache key for eligible effects
- exact PCM metadata and PCM SHA-256 for eligible effects

The manifest identity includes the profile fingerprint, Starsector build identity, decoder-policy identity, and every entry field. Manifest allocation, entry count, path bytes, and file size are bounded before serialization.

## Fail-open behavior

`PreparedAudioCache.lookup` returns one of:

- `HIT`
- `MISS`
- `INELIGIBLE`
- `CORRUPT`
- `ERROR`

Every result except `HIT` means the future adapter executes the untouched original loader path. Cache writes are explicit and atomic. Cache failures must never prevent Starsector from launching.

## Current boundary

The [installed JOrbis equivalence gate](installed-jorbis-equivalence.md) now verifies exact shipped decoder archives, application-classloader identity, PCM and metadata identity, malformed-input behavior, and stream ownership in a packaged child JVM. It remains an evidence command: it writes no `SPAU` files and installs no live sound-loader hook.

A real run against the reviewed installation must pass before a target-specific prepared-audio wrapper is considered. Packaged cold/warm/corrupt adapter proofs and real OFF-versus-ENABLED launches remain later gates before any startup improvement claim.

## What this cache would be sized against

The [audio census](audio-census.md) measures the reviewed profile without decoding it: **1,803
declared effects holding 1.17 GB of PCM**, against 2.86 GB of declared music that stays ineligible and
197 files declared nowhere at all. Vorbis expands 9.4-fold across the effects, so "cache the decoded
effects" is a gigabyte-scale commitment unless it is bounded.

Three constraints the cache design has to answer, all recorded in
[the evidence](evidence/2026-07-26-what-prepared-audio-would-have-to-hold.md):

- **Eligibility comes from `sounds.json`, not from paths.** Effects and music are distinguished only
  by the top-level `"music"` object; directory naming is wrong in both directions in this profile.
  Files declared nowhere must never be prepared.
- **A duration bound carries most of the benefit.** 17 effects over a minute hold 27% of the eligible
  bytes; the 1,511 under five seconds total 403 MB.
- **Undecodable inputs are ordinary.** The profile ships a declared effect that is FLAC in an Ogg
  container and a zero-byte `.ogg`. These must classify as `INELIGIBLE`, not `ERROR`, and must not
  block a launch.

Also still open from the [wrapper observation](evidence/2026-07-26-the-wrapper-payload-was-never-the-problem.md):
the installed wrapper stores a one-byte `0xff` sentinel for fully silent streams instead of PCM, so
silent effects need either the same sentinel or exclusion from the cache.
