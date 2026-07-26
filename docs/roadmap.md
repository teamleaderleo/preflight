# Roadmap

Preflight follows a measurement-first sequence. Each optimization keeps the original loader available as a fallback.

## Current near-term program

The July 2026 unified real-install runs completed the broad discovery gate for texture loading, Janino compilation, and audio decoding.

- [Optimization North Star](optimization-north-star.md) records the real-install evidence, exact reviewed targets, ordered implementation program, benchmark protocol, and release gates.
- [Real texture preparation and compatibility pilot](evidence/2026-07-18-real-texture-preparation-and-compatibility-pilot.md) records the passing full-profile preparation, the title-screen renderer failure, and the bounded launcher-lifecycle reporting fix.
- [Compatibility-v2 acceptance evidence](evidence/2026-07-19-real-texture-compatibility-v2-acceptance.md) records one bounded accepted real-install texture run.
- [Prepared-pixel operator and LLM handoff](prepared-pixels-operator-handoff.md) defines the exact current sequence and stop points.
- [Next LLM Implementation Handoff](next-llm-handoff.md) provides current identities, responsibilities, prohibited shortcuts, and the next implementation decision tree.
- [Verification strategy](verification-strategy.md) records which claims can be proved without the game, which need the reviewed installation, why each machine in the fleet is or is not suitable, and the 2026 tooling survey behind those choices.

The adapter-OFF control reached the main screen and exited normally. Compatibility-v2 preserves Starsector's asynchronous image-preloader handoff, matches the exact installed bytes, and passed bounded real-install behavioral acceptance on 2026-07-19. PR #117 repaired the installed-style prepared-pixel color flow, and PR #119 added an offline exact installed-class contract checker. The immediate sequence is now: run that checker against the reviewed installation, review the report, complete one prepared-pixel lifecycle through campaign/combat/save/clean exit, and only then run repeated OFF-versus-compatibility-versus-prepared-pixel measurements. Audio and Janino remain exact-evidence gated until the texture decision is made.

## M0: Measurement foundation

- Launch-time JFR agent
- Startup trace summarizer
- Repeatable benchmark protocol
- Profile and environment fingerprints

Exit condition: a baseline result bundle explains the dominant startup costs for at least one large mod profile.

## M1: Resource index

- Ordered enabled-mod fingerprint
- Winning-provider lookup
- All-provider lookup for mergeable resources
- Negative lookup cache
- Case-collision diagnostics

Exit condition: fixture tests match reference resource resolution and benchmarks show the saved lookup work.

## M2: Prepared textures

- Benchmark current decode and conversion path
- Bulk conversion for common image layouts
- Versioned prepared-texture payload
- Content-addressed cache pack
- Corruption detection and rebuilding
- Exact-gated compatibility and upload-ready runtime consumers

Exit condition: cached and uncached texture data are equivalent and repeat startup improves on image-heavy profiles.

## M3: Script bytecode

- Measure loose-source compilation cost
- Persist generated and transformed bytecode
- Capture complete ordered source/resource dependencies
- Conservative exact-context invalidation

Exit condition: representative source-heavy profiles compile once and safely reuse complete generated class maps.

## M4: Scheduling and integration

- Separate image and script worker pools
- In-flight decoded-byte budget
- Runtime adapter for vanilla Starsector and optional Fast Rendering support
- Cross-platform packaging and diagnostics

## M5: Prepared audio and later experiments

- Prove installed-JOrbis PCM and wrapper-contract equivalence
- Reuse short fully decoded effects with exact keys and untouched fallback
- Preserve streaming music until its policy is proven safe
- Evaluate selective lazy loading only when traces identify a narrow safe target

Exit condition: prepared audio is byte-for-byte and metadata-equivalent, bounded, fail-open, and measurably reduces repeat-launch decoding work.

**Equivalence is owed to the installed decoder, not to a reference decoder.** The gate built for the
first bullet decodes through `com.jcraft.jorbis.VorbisFile`, which no shipped Starsector code path
calls, and compares against libvorbis output; run against the reviewed installation on 2026-07-26 it
fails all five valid fixtures, and the real path disagrees with libvorbis by up to 2 LSB plus one
untrimmed final block regardless. Byte-for-byte is reachable only against JOrbis driven through
`sound/void`'s own call sequence. Correcting the harness to that oracle is the prerequisite for M5.
See [the evidence](evidence/2026-07-26-the-audio-gate-decodes-an-api-the-game-never-calls.md).

## Exploratory tracks (not yet evidence-gated)

Separate from the speed-first milestone program above:

- [Asset Quality Track](asset-quality-track.md) — proposed opt-in visual-fidelity track
  (crisper BMFont atlases as a candidate standalone mod; offline texture super-resolution
  behind a faithfulness gate). Records concrete font-asset facts, the bigger-vs-sharper
  design question, the gameplay-coordinate gotcha for hull sprites, the VRAM estimator /
  Asset Lab budget, why in-game FPS is out of scope, and external references. Must not
  regress speed-track measurements.
- [Community Evidence and Benchmark Additions](community-evidence.md) — Reddit/forum sweep
  (tier-4/5) that justifies keeping the identity-heavy benchmark design and adds two
  concrete items: a VRAM/decoded-texture estimator and separate runtime/launcher campaign
  orchestration (vanilla+bundled-Java, vanilla+alternate-Java, FR — each OFF and warm).

### Revised near-term priorities (speed track first, quality track opt-in)

1. Complete the prepared-texture lifecycle and controlled timing campaign.
2. Benchmark Starsector's built-in script cache against Preflight Scripts.
3. Run separate bundled-Java, alternate-Java, and FR campaigns (identities never merged).
4. Add VRAM and decoded-texture estimates to `doctor` and profile reports.
   *(In progress: `TextureMemoryEstimator` core + tests landed; `prepare` emits
   `.stages.textures.details.memoryEstimate` and `texture manifest inspect` prints it.
   Census working-set breakdowns landed: `ImageHeaderReader` (exact PNG/JPEG dimensions,
   header-only) feeds a per-mod decoded-VRAM breakdown in `scan` — `decodedWorkingSet`,
   per-mod `decodedImageBytes`, and `largestDecodedMods`. On a real ~70-mod profile this
   surfaced a 4.7 GB decoded floor from 1.1 GB on disk, and a decoded ranking that inverts
   the on-disk one (e.g. a 47 MB mod = 986 MB VRAM). `scan --vram-budget <size>` now grades
   that floor with a three-way advisory verdict (`over` / `at-risk` / `under`), where `at-risk`
   means the base levels fit but a full mip chain (floor + floor/3) would not. The verdict is
   graded against the override-resolved `winnerDecodedImageBytes` (only the loaded provider at
   each logical path), not the all-providers total, so texture-replacer overlap can't inflate it
   into a false `over` — on the real profile a 4G budget reads `over` by 388 MB. `doctor` now
   prints a compact decoded-working-set summary (override-resolved floor, loudest decoded mods,
   pointer to the budget verdict) so the estimate is visible from the command users actually run,
   not just `scan --json`. That closes roadmap #4. `scan --max-texture-size <pixels>` then answers
   the follow-up question a verdict alone cannot — *what would I actually cut* — by projecting the
   floor after capping every override-winning texture's long edge, using exact repeated halving
   (a 2x2 box reduction divides decoded cost by exactly 4 and keeps power-of-two sizes), and
   re-grading the budget against that projection. On the real profile this refuted the obvious
   guess: a 2048 cap touches only 5 textures and saves 74 MiB, still `over`; the cost is a long
   tail, and a 1024 cap (211 textures) takes 4.36 GiB to 2.76 GiB and clears 4G. See roadmap #7.)*
5. Add save/load and clean-exit outcomes to launcher-compatibility campaigns.
6. Font quality: **mechanism confirmed in-game** — mod override of core fonts works, and the
   core UI renders at declared `.fnt` metrics (so an `N×` pack is *bigger* text, not
   same-size-sharper; residual graininess is display/UI-scale). **Landed**: `BitmapFont` codec,
   `FontAtlasGenerator` (AWT rasterizer), `preflight font generate`, and `font generate-pack`
   (whole-UI bring-your-own-font mod generator). Remaining: polish the readable-font mod
   (font picker / packaging), optional kerning, and a fits-in-layout larger-text option.
7. Build the texture Asset Lab as an offline, opt-in overlay generator with a budget gate.
   *(Landed, gate first then generator. The gate: `scan --max-texture-size <pixels>` projects the
   override-resolved floor after capping oversized textures, ranks the largest individual savings,
   and re-grades the VRAM budget against the projection — pure arithmetic over image headers,
   rewriting nothing. The generator: `preflight assets shrink --max-texture-size <pixels> --out-dir
   <mod-dir>` writes the capped textures as a drop-in override mod, so the projection can actually
   be taken. It never touches the installation; undoing it is disabling one mod. On the real
   ~70-mod profile a 1024 cap wrote all 211 oversized textures in 30 s (44 MB on disk) and the
   written pack re-measures to **exactly** the projected 521.85 MiB — 1.60 GiB saved, projection
   and delivery byte-identical. The overlay direction is reduction, not super-resolution: see
   [asset-quality-track.md](asset-quality-track.md) for why that is the honest win.)*
8. Keep enhanced assets in a separate cache namespace and manifest.
   *(Prerequisite measurement done. `assets compression-probe` scored real profile art through a real
   BC1/BC3 encoder in CIELAB ΔE and inverted the standing 2019 objection: the large smooth textures
   holding **55% of art VRAM** round-trip at median mean ΔE **0.80**, below human perceptibility,
   while the small sprites that genuinely mangle are under 1% of VRAM. Then
   [`probe-kits/gpu-capability`](../probe-kits/gpu-capability/README.txt) asked the driver what it
   will accept — see
   [2026-07-25-macos-gl-capability-probe.md](evidence/2026-07-25-macos-gl-capability-probe.md).
   BC1–BC5 upload by both routes; **BC7 and ASTC return `GL_INVALID_ENUM`** on Apple's GL, so the
   macOS choice is BC1/BC3 or nothing. Two further facts fell out: non-power-of-two textures upload
   natively, so the loader's `get2Fold` padding — **1.86 GiB, 27% of resident VRAM** — is inherited
   Slick2D behaviour rather than a hardware requirement; and the engine's hardcoded `GL_RGBA`
   internal format could be swapped for a compressed one at the same call site, one constant for a
   4× cut. The offline-encoder path is preferred anyway: `BlockCompressor` beats the driver's encoder
   by 1.6–2.0×, it allows a per-texture selective policy, and a pre-encoded block texture removes the
   PNG decode stage entirely — putting block compression on the speed track rather than opposite it.
   **That last claim is now measured** — see
   [2026-07-26-texture-load-pipeline-decomposition.md](evidence/2026-07-26-texture-load-pipeline-decomposition.md).
   On the real profile, ImageIO decode is 67–70% of texture load and the raster walk plus power-of-two
   padding another 25–28%, against **under 3% for the GPU upload** and about 3% for disk: CPU work is
   **94.6%** of a texture load. A block cache is therefore **61–74× faster than vanilla and ~4× faster
   than the existing prepared-pixels cache**, while reading a quarter of the bytes and leaving a
   quarter of the VRAM resident — it dominates the incumbent on every axis simultaneously. This
   reorders the program: decode elimination is worth 16–74×, every footprint lever is worth a few
   percent of load time, and the block cache is the best available answer to both, so it should come
   before further shrink work.
   **The encoder underneath that estimate is now verified against real hardware** — see
   [2026-07-26-encoder-driver-byte-agreement.md](evidence/2026-07-26-encoder-driver-byte-agreement.md).
   `BlockCompressor` previously round-tripped pixels without ever forming a block; it now emits the
   exact byte layout `glCompressedTexImage2D` expects, and `roundTrip` is defined as encode-then-decode
   so published fidelity numbers describe the file rather than the palette the encoder had in mind.
   Serialising surfaced a defect that could not have been seen before: **BC1 reads its endpoint order
   as a mode bit**, nothing upstream constrained it, and leaving it unordered would have taken mean ΔE
   from 1.69 to 18.44 on half of all blocks. Ordering costs nothing. `BlockUploadProbe` then had the
   driver arbitrate rather than trusting encoder-agrees-with-decoder: **BC1 and BC3 are now bit-exact
   against Apple's decoder over 65,536 pixels each**, after matching one measured quirk — the colour
   blends truncate while the alpha blends round, in the same block.
   **The blocks now have somewhere to live.** `BlockTexture` / `BlockTextureIO` (magic `SPFB`) and
   `BlockTextureBaker` are the block cache's blob format and bake path. It is a separate type from
   `PreparedTexture` on purpose: that cache promises pixels identical to the loader's, block data
   breaks that promise by design, and collapsing both into one codec field would leave the difference
   to a check any consumer could omit — with no exception and no log line when they do. The blob
   carries the encoder's `CODEC_VERSION` (a blob from an older encoder is not corrupt and cannot be
   recognised by inspection), the GL internal format, and the bake-time `TextureFidelity.Report`, which
   is required rather than optional so a lossy texture cannot enter the cache without its loss having
   been measured. The baker picks BC1 for opaque art and BC3 for anything with alpha, and can bake a
   full mip chain. Writing that chain exposed a defect in the shipped `assets shrink` downsampler: a
   2x2 box filter is only correct on even dimensions, so a 5-pixel row halving to 2 never reads the
   fifth pixel at all. Both now area-average through a shared `ImageResampler` — identical to the 2x2
   box on power-of-two art, so nothing a released `assets shrink` has written changes, and correct off
   it.
   **A profile can now be baked.** `BlockCacheManifest` / `BlockCacheManifestIO` (magic `SPFC`) hold the
   separate namespace this item asks for, and `preflight assets bake-blocks --out-dir <cache-dir>`
   fills it. The interesting behaviour is the refusal to encode: every texture is baked, measured, and
   kept only if p99 ΔE came in under a stated gate (default 1.0, the just-noticeable threshold), so a
   texture with no entry simply keeps the ordinary decode path. That per-texture policy is the reason
   an offline encoder beats flipping the engine's internal-format constant. Shader maps are excluded on
   principle rather than on their number — ΔE does not describe a normal map. Blobs are
   content-addressed, so duplicated art across mods is encoded once. The manifest holds the encoder
   version once for the whole cache, so a stale cache is one integer compare at startup rather than ten
   thousand. **Nothing reads it yet** — the cache is inert until a runtime adapter exists, which is
   deliberate: it can be baked, inspected and argued about before anything touches a loading game.
   **The cache is now driver-checked, not just self-checked.** `preflight assets cache-conformance`
   exports a sample of a baked cache in the same `SPFV` format `block-conformance-probe` already reads,
   so the real driver arbitrates the real cache — the one part of this a synthetic harness structurally
   cannot reach, since every existing harness stops at the byte level and a wrong internal-format
   constant or mip-level order would pass all of them. Confirmed bit-exact on Apple M5 hardware. The
   consumer is the remaining piece.)*
9. Turn community reports into regression cases.
10. Reserve performance claims for repeatable runs with exact identities.

## Milestone numbering in the issue tracker

M0–M5 above are the original document milestones. Later work continued the numbering in the issue tracker rather than here:

- M6 — synthetic production-cache workload proofs (PRs #67–#68).
- M7 — self-contained real-install probe kits (PR #72).
- M8 — exact real-install identity and equivalence gates before live reuse: issues #75 (audio), #77 (Janino), #78 (texture shape).
- M9 — one exact-profile pre-launch build and launch context: issue #76.
- M10 — repeated real OFF-versus-ENABLED startup benchmarks: issue #80.
