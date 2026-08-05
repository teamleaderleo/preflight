# Prepared audio no longer re-hashes the encoded corpus under Rosetta

**Date:** 2026-08-05

**Install:** Starsector 0.98a-RC8, 83 enabled mods, macOS on Apple M5 under Rosetta

**Result:** 2,049/2,050 live decodes avoided SHA-256; first repeatable sub-25-second cohort

## Finding

Prepared audio had already removed the installation's 19.7 core-seconds of Vorbis decoding and the
main thread's 3.46-second wait for the two sound workers. Its remaining lookup still read and
SHA-256-hashed all 133.3MB of encoded Ogg input inside Starsector's x86-64 JVM. The corrected current
`--fast` JFR attributed 58 execution samples to that hash, consistent with the runtime's earlier
roughly 0.5 CPU-second measurement. Rosetta exposes neither `UseSHA` nor `UseSHA256Intrinsics` in the
bundled JVM.

Installed bytecode supplies a safer key. `com.fs.starfarer.loading.A.run()` obtains the encoded bytes
with `com.fs.graphics.L.new(filename)` and passes that same filename beside the resulting stream to
`sound.Sound(String, String, InputStream)`. The Ogg branch forwards the same pair to
`sound.ooOO.o00000(String, InputStream)` immediately before constructing `sound.J` and invoking its
decoder. The string is also the sound store's own buffer-cache key and error identifier. It is not
inferred from a log gap or recovered from a mutable global.

## Change and correctness boundary

The audio bake now writes the existing checksummed SPAM manifest for the exact resource-profile
fingerprint. Each eligible logical path carries its encoded source SHA-256, source metadata, decoder
identity, prepared-blob key, and PCM metadata. Before launch, native arm64 Preflight requires the
exact profile, game build, and decoder identities, resolves every current winning provider through
the normal containment checks, matches recorded size and mtime, and content-hashes every source.
Only then does it pass the manifest and its independent identity to the agent.

The exact shipped `sound.ooOO` transform passes its existing filename and untouched input stream to
the prepared-audio runtime. A manifest hit selects the same content-addressed blob without hashing
the stream. Unknown/invalid paths, missing/corrupt blobs, unreadable or mismatched manifests, profile
drift, game/decoder drift, or transform drift all retain the stream and call the existing
content-hash wrapper, which in turn retains the untouched vanilla decoder. No OpenAL, registration,
pool-width, ordering, or shared sound-store behavior moved.

Alternating `--dry-run` cohorts measured full native validation of 2,049 files / 133.3MB at
54.6--73.0ms wall. Warm complete launcher runs were effectively tied at 1.41s indexed versus 1.39s
without prepared audio in the closest pair. This moves a much slower Rosetta hash off the two game
loader threads while keeping an exact content check before process start.

Tests cover path-hit stream non-consumption, unknown-path fallback with the original stream,
manifest-identity fallback to content hashing, deterministic manifest persistence, changed decoder
call shape, idempotence, and the exact installed `fs.sound_obf.jar` rewrite. Full `mvn verify`
passes.

## Live gate and cohort

Diagnostic gate:

- `~/.starsector-preflight/runs/audio-path-index-20260805-221915`
- path manifest ready with 2,049 entries
- 2,049 path hits, one unknown-path miss, one byte-hash lookup
- 2,049 prepared blobs served / 1,226,415,962 PCM bytes
- zero audio failures, transform declines, or contained failures
- game stopped automatically after the main-menu marker

Ordinary non-probed cohort:

- `~/.starsector-preflight/benchmarks/20260805-222034`
- **24.81s, 24.61s, 24.76s**
- **24.76s median**, 0.20s full range
- every run repeated 2,049 path hits, one hash fallback, and zero failure/decline
- every game process was stopped after its main-menu marker

The earlier five-run deduplicated-Janino cohort was 25.58s median (25.08--25.80), and the subsequent
tagged-rules change removed an exact 188ms seam but only reached 25.092602s in its cooled follow-up.
Those are not a shuffled A/B for this individual change, so the defensible direct claim is the
eliminated Rosetta hash and the new repeatable cohort. The project has nevertheless now measured a
sub-25-second startup three times consecutively, versus the original 62.6-second baseline.
