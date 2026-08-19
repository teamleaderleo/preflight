# Preflight binary formats

Preflight owns a small family of versioned binary formats used for rebuildable cache data,
launch-time identity data, and engineering fixtures. These files use four-byte ASCII magic headers
such as `SPFI`, `SPFT`, and `SPTG` so a reader can reject the wrong byte layout immediately.

`SP` is reserved here as the **Starsector Preflight** project namespace. The final two letters are a
short mnemonic chosen for the artifact. These identifiers are project-owned protocol markers. They
are not cryptographic algorithms, external standards, or evidence that a file is authentic.

This file is the canonical registry for `SPxx` magic values. Humans, coding agents, and automated
maintenance work should consult it before introducing or changing a persisted binary format.

## Rules for maintainers and agents

1. **Search before assigning a magic.** Check this registry and the code before choosing a new
   `SPxx` value. A useful inventory command is:

   ```bash
   rg "MAGIC\\s*=\\s*\\{'S', 'P'" preflight-*/src/main
   ```

2. **New production formats get a unique magic.** Use four uppercase ASCII bytes in the
   `SP[A-Z][A-Z]` namespace and add the value to this registry in the same change. Do not create a
   second collision like the historical `SPFC` case below.
3. **Version schema changes deliberately.** A magic identifies an artifact family; its version
   identifies a byte layout within that family. Prefer incrementing the format version, together
   with the established cache-namespace migration rules, when the artifact remains the same thing.
   Use a new magic when the artifact has different semantics or must be impossible to confuse with
   the old family.
4. **Magic bytes are only the first check.** Readers still validate the expected version, bounded
   lengths/counts, canonical names and paths where applicable, checksums or content identities,
   EOF/trailing data, and the artifact's surrounding identity contract. Never authorize content
   merely because its first four bytes match.
5. **Paths and extensions are not type proof either.** Most formats use a lowercase extension that
   resembles their magic, but the owning reader/writer class is authoritative. A correctly named
   file with the wrong bytes must still be rejected.
6. **Rebuildable cache formats fail back to the original path.** Corrupt, stale, unsupported, or
   identity-mismatched acceleration data is discarded, repaired, or declined according to the
   owning subsystem. It does not acquire authority by existing on disk.
7. **Keep primitives and Preflight protocols distinct in documentation.** SHA-256, CRC32C, LZ4,
   NTFS USNs, and platform file-generation identifiers are external algorithms or operating-system
   primitives. `SPTG`, `SPFT`, and the other entries below are Preflight protocols/formats that may
   contain or depend on those primitives.

## Production and product-cache registry

| Magic | Artifact | Owning reader/writer | Notes |
| --- | --- | --- | --- |
| `SPFI` | resource-provider index | `ResourceIndexIO` | Exact provider/order index used by profile-aware resource lookup. |
| `SPFJ` | JAR archive index | `JarArchiveIndexIO` | Versioned archive-entry index used by classpath/profile work. |
| `SPFC` | classpath profile index | `ClasspathProfileIndexIO` | **Historical collision:** shares `SPFC` with the block-cache manifest. Kept for compatibility; do not reuse this magic again. |
| `SPFM` | prepared-texture manifest | `TextureManifestIO` | Binds logical source paths and exact source SHA-256 identities to prepared texture blobs. |
| `SPFT` | prepared texture blob | `PreparedTextureIO` | Upload-ready texture data; intentionally distinct from lossy block-texture data. |
| `SPFP` | prepared-texture pack | `PreparedTexturePackIO` | Indexed single-file packing of existing `SPFT` blobs. |
| `SPFO` | prepared-texture pack order | `PreparedTexturePackOrderIO` | Checksummed observed access order used to lay out a later pack. |
| `SPTG` | texture source-generation proof | `TextureSourceGenerationProofIO` | Added for generation-bound source authorization. It records the manifest identity plus platform generation tokens after exact source verification; it is not a hash algorithm. |
| `SPFB` | block-compressed texture blob | `BlockTextureIO` | Lossy/offline texture representation; deliberately cannot be mistaken for `SPFT`. |
| `SPFC` | block-cache manifest | `BlockCacheManifestIO` | **Historical collision:** shares `SPFC` with the classpath profile index. Current readers rely on the expected artifact location/schema/version, not magic alone. |
| `SPAU` | prepared audio blob | `PreparedAudioIO` | Deterministic decoded PCM cache payload. |
| `SPAM` | prepared-audio manifest | `PreparedAudioManifestIO` | Profile manifest for prepared audio identities and metadata. |
| `SPVJ` | prepared ship-variant JSON cache | `PreparedVariantJsonCacheIO` | Checksummed tagged JSON-tree cache. |
| `SPWJ` | prepared weapon JSON cache | `PreparedWeaponJsonCacheIO` | Checksummed tagged JSON-tree cache. |
| `SPPJ` | prepared projectile JSON cache | `PreparedProjectileJsonCacheIO` | Checksummed tagged JSON-tree cache. |
| `SPHJ` | prepared hull JSON cache | `PreparedHullJsonCacheIO` | Checksummed tagged JSON-tree cache. |
| `SPRC` | prepared rules CSV cache | `PreparedRulesCsvCacheIO` | Checksummed merged campaign-rules data. |
| `SPRT` | prepared rule-token cache | `PreparedRuleTokenCacheIO` | Checksummed learned rule-token forms. |
| `SPRK` | prepared rule-command class cache | `PreparedRuleCommandClassCacheIO` | Checksummed learned rule-command package/class data. |
| `SPMR` | prepared merged-read cache | `PreparedMergedReadCacheIO` | Checksummed prepared merged reads. |
| `SPJB` | generated-bytecode bundle | `GeneratedBytecodeBundleIO` | One deterministic complete generated-class map. |
| `SPJP` | generated-bytecode pack | `GeneratedBytecodePack` | Content-deduplicated pack of exact generated-class results. |

### The `SPFC` legacy collision

`SPFC` predates this registry and is used by both `ClasspathProfileIndexIO` and
`BlockCacheManifestIO`. The two artifacts live in different product contexts and have independent
schema/version validation, so current readers are not dispatched globally from the four magic bytes.
The collision is nevertheless a useful warning: four bytes are a cheap wrong-format guard, not a
universal type system.

Do not rename either established format casually; doing so creates a cache migration for no product
benefit. Instead, freeze this exception in place and require every new production `SPxx` assignment
to be unique.

## Engineering and synthetic formats

These values belong to repository tooling or packaged synthetic fixtures rather than ordinary
player cache state. They still live in the same registry because collisions are repository-wide.

| Magic | Artifact | Owner | Scope |
| --- | --- | --- | --- |
| `SPFV` | block conformance vector | `BlockConformanceVectorIO` | Cross-language/GPU conformance vector consumed by the native probe. |
| `SPXI` | synthetic prepared-image cache | `SyntheticPreparedImageCache` | Synthetic-startup fixture only. |
| `SPXR` | synthetic extended resource index | `SyntheticExtendedResourceIndex` | Synthetic-startup fixture only. |

## How to read the names

The suffixes are mnemonics, not a formal standards-body allocation. Some are immediately readable
(`FI` for an index family, `FM` for a manifest, `TG` for texture generation); others reflect the
artifact name that existed when the format was introduced. Treat the complete four-byte value as
an opaque registered identifier rather than reverse-engineering authority from individual letters.

The important distinction is:

- **SHA-256 / CRC32C / LZ4 / filesystem generation identifiers:** algorithms or platform primitives.
- **`SPxx`:** Preflight-owned serialization/protocol identifiers that tell Preflight which byte
  contract it expects to read.

For example, `SPTG` uses SHA-256 during sealing and platform file-generation primitives during
prelaunch validation. `SPTG` itself names the Preflight source-generation-proof file format and its
validation contract; it is not a newly invented cryptographic algorithm.

## Adding a format

A change that introduces a new persisted binary artifact should, in the same PR:

1. choose an unused `SPxx` magic and add it to this registry;
2. define an explicit format version;
3. define bounded reads before allocating from untrusted length/count fields;
4. reject wrong magic, wrong version, malformed/truncated input, and trailing data;
5. define the corruption/integrity check appropriate to the artifact;
6. define canonical path/name handling if the payload contains paths or identifiers;
7. publish atomically when partial files could otherwise be observed;
8. state whether the artifact is rebuildable cache, launch authority, durable evidence, or an
   engineering fixture;
9. state the stale/corrupt/unsupported fallback behavior; and
10. add round-trip plus malformed/corrupt-input tests.

If a new agent encounters an unregistered `SPxx` value, the code is authoritative for the bytes,
but the missing registry entry is documentation debt and should be repaired before adding another
format.
