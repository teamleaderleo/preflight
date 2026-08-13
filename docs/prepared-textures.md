# Prepared texture blobs

Preflight converts encoded images into the byte layout consumed by Starsector's texture upload path and stores that result in versioned prepared-texture blobs. The literal converter remains the compatibility authority; optimized preparation is accepted only when it reproduces the complete `PreparedTexture` value.

## Commands

Prepare one image:

```bash
java -jar preflight.jar texture prepare graphics/ships/example.png
```

The default content-addressed output is:

```text
~/.starsector-preflight/textures/SOURCE_SHA256-identity.spft
```

Choose an explicit output:

```bash
java -jar preflight.jar texture prepare example.png --output example.spft
```

Inspect and verify:

```bash
java -jar preflight.jar texture inspect example.spft
java -jar preflight.jar texture verify example.png example.spft
```

`verify` hashes the current source, performs a fresh literal reference conversion, and compares the complete prepared result with the blob. It does not reuse the optimized preparation path.

Compare literal conversion, bulk-row conversion, and blob reads:

```bash
java -jar preflight.jar texture benchmark example.png example.spft --runs 10
```

The benchmark performs untimed validation passes, alternates literal and bulk measurement order, reports every sample plus minimum, median, mean, and maximum, and keeps blob reads separate. CI checks correctness rather than timing ratios.

Build a profile-wide cache from a discovered or explicit resource index:

```bash
java -jar preflight.jar texture build --game "/path/to/Starsector.app"
java -jar preflight.jar texture build --index profile.spfi --cache-dir cache
```

The build also materializes the profile's distinct SPFT blobs into one indexed SPFP pack. Runtime reads use one validated channel and fail open to loose content-addressed blobs if the pack is absent or unusable. A successful launch records a checksummed access-order hint; the next build reorders the same pack for that profile and appends unseen assets deterministically.

### Deterministic subset builds

For profiling and staged rollouts, pass a newline-delimited list of logical resource paths:

```bash
java -jar preflight.jar texture build \
  --game "/path/to/Starsector.app" \
  --cache-dir cache \
  --paths-file startup-images.txt
```

The selection file accepts blank lines and `#` comments. Every other line must be a relative logical resource path, for example:

```text
graphics/ships/example.png
graphics/icons/example.jpg
cache/generated_normal.png
```

Subset preparation normalizes, lowercases, deduplicates, and sorts paths with the resource-index rules; rejects absolute and traversal paths; reports missing and non-image paths without discarding valid selections; derives a deterministic subset fingerprint; writes a matching subset `.spfi` and `.spfm`; reuses the content-addressed blob store; and never overwrites the full-profile index or manifest.

## Prepared payload

A prepared texture contains:

- source SHA-256;
- transformation identifier;
- original image dimensions;
- stored payload dimensions;
- three or four channels;
- bottom-up RGB or RGBA bytes;
- three packed `RRGGBBAA` color values used by the loader.

`ALPHA_ADDER` is represented in the format and remains unsupported until its exact transformation has an equivalence fixture.

## Literal and bulk conversion

The literal reference converter mirrors the reviewed loader behavior:

- decode through Java ImageIO;
- read pixels through `Raster.getPixel`;
- traverse source rows bottom to top;
- store RGB for opaque images and RGBA for alpha images;
- leave fully transparent output texels zeroed;
- exclude fully transparent texels from color statistics;
- preserve float accumulation order, histograms, and derived-color calculations.

The optimized converter calls `Raster.getPixels()` once per source row and executes the same ordered per-pixel arithmetic. Unsupported or unusual raster layouts fall back to the literal converter. The equivalence suite covers the standard JDK image types, premultiplied alpha, BGR/ABGR, ushort RGB and grayscale, binary/indexed images, custom grayscale-plus-alpha and indexed-alpha models, odd dimensions, transparent pixels, translated subimages, and file-backed PNG/JPEG decoding.

## Blob format

Version 1 uses:

```text
magic:      SPFT
version:    32-bit integer
length:     32-bit payload length
payload:    metadata plus prepared pixels
checksum:   SHA-256 of the payload
```

The writer uses a sibling temporary file and atomic replacement where supported. Strict readers validate bounds, payload length, checksum, transformation, codec, dimensions, channel count, expected pixel length, and trailing data before constructing the texture.

## Runtime consumers

Both live consumers bind the exact reviewed `TextureLoader` class, archive, methods, source, loader, and bytecode/dataflow contract.

- `compatibility` reconstructs a `BufferedImage` at the decoded-image seam. Starsector retains its original pixel conversion, OpenGL upload, cleanup, and texture lifetime.
- `prepared-pixels` carries the prepared payload to the lower `BufferedImage -> ByteBuffer` seam. A hit supplies the stored bottom-up bytes and all three stored derived colors, bypassing ImageIO decode, raster traversal, row reversal, RGB/RGBA conversion, transparent-texel normalization, and derived-color calculation. Starsector retains texture ownership, filtering, mipmaps, cleanup, flags, and lifetime.

Both version-2 plans preserve Starsector's original asynchronous preloader handoff before any Preflight lookup. A preloaded image remains authoritative. Preflight is consulted only on the direct-decode branch after that handoff returns `null`; an absent or ambiguous handoff leaves the class unchanged.

`--texture-auto` resolves the already-prepared manifest and index for the exact current installed profile and now works with both consumers. It is read-only: a missing or changed profile fails before launch and asks for preparation rather than selecting an older artifact.

The current reviewed 0.98a-RC8 target has passed the installed contract check and live prepared-pixel gates. `PreparedPixelContractCheck` remains the offline review tool for a new or changed target:

```bash
java -cp preflight-cli/target/preflight.jar \
  dev.starsector.preflight.agent.PreparedPixelContractCheck \
  "/path/to/Starsector.app/Contents/Resources/Java/fs.common_obf.jar"
```

Prepared-pixels-v2 accepts either three direct non-static `java.awt.Color` fields on the texture object, or three non-static `TextureLoader` color fields that each flow through an instance method into one distinct, exactly typed texture-object setter. Mixed, incomplete, ambiguous, untyped, static-transfer, or raster-free models decline.

## Runtime validation and hash policy

Before serving anything, the texture runtime verifies that the cache artifacts remain inside the supplied cache root, the manifest and resource index share an identity, counts are bounded, and the complete resource index still matches the installation.

Normal product launches do **not** re-hash every source file and every prepared pixel payload on the loading thread. That policy was removed after it became a dominant Rosetta CPU cost.

- Recommended/`--fast` includes `--trust-validated-texture-index`, treating the complete configure-time provider validation as the immutable source snapshot for that launch.
- `--recheck-texture-sources` restores a per-hit filesystem staleness check using the same size/mtime contract as `ResourceIndexValidator`.
- `-Dpreflight.texture.verifySourceHash=true` additionally restores a full source SHA-256 on those per-hit checks.
- `-Dpreflight.texture.verifyBlobChecksum=true` restores strict SPFT payload checksum verification on every prepared-blob read.

Without the diagnostic hash switches, runtime serving still checks the manifest entry, winning provider identity, transformation, dimensions, channels, payload length, path containment, and cache format/identity needed by the selected serving path. Missing or malformed data, identity mismatches, unsupported textures, direct-memory pressure, bridge failures, and contained runtime errors return to the preserved original path. Strict `texture verify` remains available for an explicit integrity pass.

## NPOT and padding policy

The July 22, 2026 prepared-pixel prototype guessed an NPOT padded-buffer arrangement and produced visibly wrong launcher textures. That result is retained because it established that a plausible buffer length is not a sufficient layout contract.

The corrected path subsequently passed the exact reviewed launcher → main menu → campaign → combat → save → clean-exit route on July 23. That run recorded 5,015 prepared hits, zero fallbacks/internal errors, complete direct-buffer release accounting, and normal accepted visuals. The later interleaved August 1 campaign measured the composed prepared path at a 62.60s median versus 72.25s for the non-pixel fast condition and 88.13s for the same-session no-Preflight condition; the prepared-pixel contribution was isolated at about 9.65s on that profile. Those are development-profile results, not a cross-hardware release claim.

Current NPOT handling has two reviewed modes:

- **Recommended / `--prepared-unpadded`**: the prepared bridge supplies the true-size pixel buffer and the exact installed dimension fold is bypassed in the same plan. This becomes effective only when the live LWJGL context exposes OpenGL 2.0 or `GL_ARB_texture_non_power_of_two`. If the fold rewrite is absent or the capability probe declines, NPOT textures stay on the original padded path.
- **Conservative / `--prepared-npot`**: the prepared bridge supplies the reviewed padded upload layout and leaves Starsector's power-of-two allocation in place. It does not require NPOT driver capability and is useful for separating conversion behavior from padding removal.

An explicit prepared-pixels launch with neither option still serves already-power-of-two textures and declines NPOT direct-buffer creation. `--prepared-npot` and `--prepared-unpadded` are mutually exclusive.

The true-size route removes allocation that Starsector never samples; the current gate is deliberately two-sided so a true-size buffer cannot be paired with a padded allocation or vice versa.

## Memory ownership and fallback

Prepared direct-buffer ownership is bounded to:

- 32 MiB per texture;
- 64 MiB active and pending direct bytes;
- 1,024 active and pending buffers.

The existing Starsector cleanup method always runs. Preflight releases its identity-tracked accounting only after original cleanup, including exceptional caller paths. The adapter report records attempts, hits, fallbacks, bypassed conversion/decode work, supplied bytes, padding behavior, active/pending/peak ownership, releases, and contained errors.

Compatibility mode remains an independent rollback path, and adapter-wide or per-plan kill switches remain available for troubleshooting. A changed game build, source archive, loader, method contract, or runtime capability declines the shortcut instead of broadening the allowlist automatically.

## Evidence

The retained implementation history includes:

- `docs/evidence/2026-07-22-prepared-pixel-dimension-replay-visual-corruption.md` — rejected guessed NPOT layout;
- `docs/evidence/2026-07-23-prepared-pixel-gameplay-smoke-pass.md` — corrected live lifecycle acceptance;
- `docs/evidence/2026-08-01-twenty-nine-percent-when-they-compose.md` — controlled composed startup campaign;
- `docs/evidence/2026-08-02-the-padding-is-gone.md` — true-size upload evidence and footprint reduction.
