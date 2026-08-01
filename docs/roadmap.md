# Roadmap

Preflight follows a measurement-first sequence. Each optimization keeps the original loader available as a fallback.

## Measured result (2026-08-01)

The first campaign in this project's history to reach `benchmarkAccepted`. Unattended, 240s
cooldown before every launch, 5 rounds x 3 conditions, launch-order drift +0.47s.

| condition | median | paired result |
| --- | --- | --- |
| `vanilla` | 95.78s | -- |
| `fast` (cache, compatibility) | 97.22s | **loses to vanilla by 1.28s, 4 rounds of 5** |
| `prepared` (cache + pixel bypass) | 94.10s | **beats fast by 2.68s, 5 rounds of 5** |

**What a user feels: 1.4s off 95.7s, about 1.5%.** Small, and the direction is probably real.

Three things follow, in priority order:

1. **The load is serial on one thread and the machine is 27.8% busy.** Removing the conversion
   entirely bought 2.68s of 96s, which is what a serial chain predicts. Further CPU
   micro-optimization of the texture path should be dropped: the conversion was the largest single
   item on that thread. What stays reachable is the O(n) registry scan (6.4% of the loading
   thread) and the per-lookup SHA-256 (1.01s).
2. **The compatibility cache is a regression on this profile** and must not ship as a speed
   feature. It buys the PNG decode (13-16% of texture time) while paying blob I/O and a
   per-lookup SHA-256 on the loading thread measured at 1.01s. It is worth carrying only as the
   substrate the prepared-pixel path needs.
3. **Take the SHA-256 off the loading thread** -- designed, never built, now with a measured
   justification.

Full write-up: [the first valid startup number](evidence/2026-08-01-the-first-valid-startup-number.md).

### Why it is only 1.5%, measured

A sampling profile of the load answers it: **the machine is 27.8% busy for the whole 96 seconds**
-- under three of ten cores, seven idle. Stop-the-world GC is 0.00s, GPU upload is ~1% of
samples, and the loading thread never parks, sleeps or waits on a monitor. The load is one long
serial chain, and its length is the load time. Making a link of that chain cheaper returns exactly
that link and no more, which is what 2.68s for the conversion bypass is.

This retires the question this work opened with -- whether async, worker pools or cache-locality
tricks could split the serialized load. The opportunity is real and large, and it is not reachable
from where Preflight sits: restructuring the loader's serial chain means changing the loader, not
decorating it from outside with a fail-open agent.

Also measured, and unaddressed: **the JSON/spec path is comparable to the texture path** in both
wall time (`LoadingUtils` owns 0-25s and 65-85s; `TextureLoader` owns 25-65s and 85-95s) and
allocation (27% of ~126 GB, the single largest site). The resource index solves *finding* a
resource; nothing caches the parsed result.

Full write-up: [what the load is actually waiting for](evidence/2026-08-01-what-the-load-is-actually-waiting-for.md),
reproducible with `scripts/starsector_critical_path.py <recording.jfr>`.

## Standing correction: every startup number before 2026-08-01 is void

The benchmark measured from the first log line that appeared after its own snapshot. Starsector's
launcher writes into the same log the game does, so log4j flush timing decided whether the early
part of loading fell inside the measured interval. That is the whole of what was recorded as an
"unexplained 18s bimodality": every run anchored on the launcher's line measured 92-99s, every
run anchored on a later mid-load line measured 74-78s.

Read straight out of the game's own log, the same launches are unimodal at 89.6-99.1s. **Startup
on the reviewed profile is ~92 seconds, not ~75.** The measurement is fixed
([evidence](evidence/2026-08-01-the-bimodality-was-the-anchor.md)), and
`scripts/starsector_log_load_times.py` is the independent check that the harness now has to
agree with.

What this voids, and what it does not:

- **Void:** every recorded `gameLogStartToGraphicsPreloadMs`, every campaign summary built on
  one, and the prepared-pixels-versus-compatibility pilot — `prepared` was recorded at 99.1s
  against `fast` at 92.2s and 74.9s, and only one of those two was measuring the same quantity.
- **Not void:** everything established by JFR attribution, adapter reports, or the game's own
  behaviour. The profile shares (texture conversion 34-40% of the loading thread, PNG decode
  13-16%, the O(n) LinkedList scan 5-7%) are ratios within a recording and do not depend on the
  harness's wall-clock boundary at all. The correctness work — compatibility-v2 acceptance, the
  prepared-pixel contract check, the padding invariant — is untouched.

The next campaign is the first that can produce a reportable number. Run it unattended, into a
fresh session directory, and check it against `starsector_log_load_times.py` before believing it.

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

**Deprioritised on 2026-07-30, on evidence, and not on the evidence below.** The work described here
is real and happens before the menu, but it runs on a worker pool the loading thread never waits for:
**one audio sample out of 4,423 on the loading thread**, which blocks 67 ms in 90 seconds while the
two decode workers sit parked for 22 seconds each. Removing it would remove CPU and energy from a
thread nobody is waiting on, which on a 10-core machine is close to invisible in wall-clock startup.
It may still matter on a core-starved machine or a far larger audio profile, neither measured. See
[the loading thread never waits for the audio](evidence/2026-07-30-the-loading-thread-never-waits-for-the-audio.md);
texture preparation, at 40–53% of the loading thread, is the one of the three domains that is on the
critical path.

**The premise below is measured and holds — as a statement about work performed, not about wall
clock.** A run on 2026-07-29 through
[`audio decode-probe`](audio-decode-probe.md) found the game opening **all 2,050 declared effects
inside a 1.5-second window**, 23 seconds into a 360-second session, from a single caller — **1,169.4
MB of PCM built before the player sees the main menu**, on every launch. The session reached the
campaign map and flew on it, and **no sound file was opened for the first time after the 24.7-second
mark**, so entering the campaign added nothing to that set.

This is the whole declared effect set rather than a floor: combat cannot add to a set the game has
already opened completely, and a sound loaded from outside the census would show as an unmatched read.
**Nothing opens the 220 unreferenced files**, which independently confirms the `audio-unreferenced`
lint rule from the game's own behaviour rather than from config.

Music remains out of scope on the same caution as before, not on this evidence. None of the 156
declared music files were opened, but vanilla music is one container — `sounds/music/music.bin`, read
1,806 times in this run — that the census has no entry for, so the probe says nothing about it.

The first published version of this said 1,278 effects and 940.3 MB. The probe was resolving the
recording's relative paths against its own working directory instead of the game's, so every resource
the game opened by relative path looked unopened ([#232](https://github.com/teamleaderleo/starsector-preflight/issues/232)).

Reads are still not decodes; the equivalence work remains what proves what the decoder does with
them. [Evidence](evidence/2026-07-29-the-game-builds-1-2-gb-of-pcm-before-the-main-menu.md).

**Equivalence is owed to the installed decoder, not to a reference decoder.** The first version of the
gate decoded through `com.jcraft.jorbis.VorbisFile`, which no shipped Starsector code path calls, and
compared against libvorbis output. Rebuilt in #207 and #208 onto the low-level sequence `sound/void`
drives, it **passes against the reviewed installation** (2026-07-26). The wrapper observation carried the same defect and was
corrected the same day: four of five wrapper payloads match the installed decode byte for byte, and the
fifth is the wrapper's one-byte sentinel for silent streams, which prepared audio must reproduce or
exclude ([follow-up](evidence/2026-07-26-the-wrapper-payload-was-never-the-problem.md)).
See [the evidence](evidence/2026-07-26-the-audio-gate-decodes-an-api-the-game-never-calls.md).

**"Short fully decoded effects" now has numbers behind it.** The
[audio census](audio-census.md) sizes the reviewed profile without decoding it: 1,803 declared
effects hold **1.17 GB** of PCM, declared music holds another 2.86 GB, and 197 files are declared
nowhere. Three facts shape the policy the second bullet still needs
([evidence](evidence/2026-07-26-what-prepared-audio-would-have-to-hold.md)):

- 17 effects over a minute long hold 313 MB — 27% of the eligible bytes in under 1% of the files —
  so a duration bound is worth more than any other knob. The 1,511 effects under five seconds total
  403 MB.
- 195 effects recorded at 96 kHz or above hold 33% of the eligible bytes, and cost proportionally
  more MDCT work to decode. Resampling them is a transformation, not a cache, and stays out of scope.
- Effect versus music is decided by `sounds.json`, never by directory naming, which is wrong in both
  directions in this profile.

## Exploratory tracks (not yet evidence-gated)

Separate from the speed-first milestone program above:

- [Asset Lint](asset-lint.md) — `preflight lint`, a read-only report of asset problems attributed to
  the mod that ships them. Same analysis as the speed work, pointed at the source instead of routed
  around it: a mod author who fixes an asset helps every user of that mod with no cache, no adapter,
  and no equivalence gate. Twelve rules over sound, textures, config and shipped files, runnable
  against a whole profile or a single mod directory. Against the reviewed 84-root profile it finds
  1,392 issues — 285 progressively-encoded images that ImageIO decodes
  [about 8.75x slower](evidence/2026-07-28-progressive-jpeg-costs-nine-times-the-decode.md) than the
  identical image stored normally, 771.9 MB of video memory lost to non-power-of-two padding,
  687.9 MB of avoidable audio decode, 100.8 MB of disk in shadowed copies, duplicates and editor
  project files, and two files the game cannot decode at all.
  [Calibrated across 86 mods](evidence/2026-07-28-what-eighty-six-mods-ship.md) as independent
  samples: median 0 findings, 44 of 86 completely clean.

  The two config rules are the only ones that report something *broken* rather than something
  expensive. They read the 15,353 JSON-shaped files the profile ships and find
  [five](evidence/2026-07-28-config-the-game-silently-never-reads.md), four of them real defects in
  released mods — including a missile whose `PROXIMITY_FUSE` block sits outside the top-level object
  and so does nothing in game. Getting there required discarding a first version that flagged 27
  working files, because the dialect accepts far more than JSON does and a stray trailing brace is
  invisible to the game.

  Applies no fixes; a transform mode would touch other people's assets and has no safety story yet.

- **Desktop GUI (unreviewed exploration).** `preflight-desktop/` is a Vite/React shell, and
  `preflight desktop` is a read-only bridge command that discovers the installation and prints one
  JSON snapshot. It is hidden from CLI help, `preflight-desktop` is not a Maven module, and its build
  output is gitignored, so neither affects the Java build. Notes in
  [desktop app research](desktop-app-research.md).

  This arrived on main inside #216, a commit about asset-lint calibration, because that commit staged
  every modified file rather than the ones it touched. The code was exploration in progress and was
  never reviewed as part of that PR. It is recorded here rather than reverted — it is inert and
  works — but nothing here endorses it as a direction, and it needs its own review before anything
  depends on it.

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
