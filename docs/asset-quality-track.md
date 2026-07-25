# Asset Quality Track (exploratory)

Status: **exploratory / not yet evidence-gated.** This document records a proposed
second track distinct from the measured speed-first program in
[optimization-north-star.md](optimization-north-star.md). Nothing here is committed
work or a release claim. It exists so the idea and its concrete asset facts are not
lost, and so the open questions can be researched (including by external tools).

## Framing: two opposite vectors on the same resources

Everything under consideration lands on one of two axes that pull against each other on
VRAM, GPU upload bandwidth, and decode time:

- **Speed track** (the existing program): make assets *cheaper* — pre-decoded textures,
  persisted Janino bytecode, prepared audio, AppCDS, bounded scheduling. Less work and
  less memory pressure at load.
- **Quality track** (this document): make selected assets *richer* — higher-resolution,
  crisper. This *costs* load time, VRAM, and upload.

They can coexist, but only as **separate, independently switchable opt-in modes**, and
the quality track must never silently regress the speed-track measurements. This matches
the project's exact-gated, fail-open discipline: a quality transformation is applied only
when it passes its gate, otherwise the original bytes are used.

Measured repeat-launch CPU attribution (from the north star) sets the guardrail the
quality track must respect: audio decoding ~24–28% and Janino ~25–27% of samples, with
texture preparation the third dominant domain. Any quality feature is validated against
the existing JFR per-subsystem attribution before and after, so a fidelity gain is never
confused with a load-time regression.

## Not frame generation

The relevant technique is **offline single-image super-resolution baked into assets
once**, not runtime temporal frame interpolation. Given fixed model weights and a fixed
input it is deterministic and therefore reproducible and content-addressable — it slots
into `prepare` as a new transformation type alongside the existing `IDENTITY` path, with
its own faithfulness gate. Model cost is paid once, offline; it does not touch the render
loop.

## Sub-proposal A: crisper fonts (best quality-per-risk; candidate standalone mod)

The most-felt, lowest-risk fidelity win, and a clean candidate to also ship as an
isolated drop-in mod. Fonts are UI-space: no gameplay geometry is coupled to them.

### Concrete asset facts (reviewed 0.98a-RC8 install)

- Location: `Contents/Resources/Java/graphics/fonts/` (this is the core resource root on
  macOS; note `stelnet.log` sits at the root of the same tree, which is why runtime logs
  are now excluded from the resource index — see [resource-index.md](resource-index.md)).
- 51 `*.fnt` files, ~2.1 MB total including atlases.
- Format: **AngelCode BMFont, plain ASCII text (CRLF)**. Each font is a pair:
  `name.fnt` + one or more `name_<page>.png` greyscale/alpha atlases.
- Example header (`insignia15LTaa.fnt`):
  ```text
  info face="InsigniaLT" size=15 bold=0 italic=0 ... stretchH=100 smooth=1 aa=4 ...
  common lineHeight=15 base=12 scaleW=256 scaleH=256 pages=1 packed=0 alphaChnl=1 ...
  page id=0 file="insignia15LTaa_0.png"
  char id=32 x=254 y=39 width=1 height=1 xoffset=0 yoffset=12 xadvance=4 page=0 chnl=15
  ```
- Field roles:
  - **Atlas-space (scale with resolution):** `char x, y, width, height`; `common scaleW, scaleH`.
  - **Layout-space (screen px):** `char xoffset, yoffset, xadvance`; `common lineHeight, base`; `info size`.

### The central design question (research this before building)

There are two very different outcomes, and they must not be conflated:

1. **"Bigger text"** — scale *every* number (atlas-space *and* layout-space) by 2×,
   upscale the atlas 2×. Text renders twice as large. Only useful as an accessibility
   option; it changes UI layout and may break fixed-size panels.
2. **"Same size, sharper" (the goal)** — keep layout-space metrics unchanged so text
   occupies the same screen space, but sample glyphs from a higher-resolution atlas so
   each glyph is supersampled/anti-aliased at its native on-screen size.

### Rendering mechanism (resolved)

Static analysis first: the engine's own font renderer is obfuscated (`fs.common_obf.jar`);
`com.fs.starfarer.api.ui.Fonts` is only a name-constants class, and no `LazyFont` string
appears in any shipped jar. I did not reverse-engineer the commercial binary. Instead the
mechanism is settled by the **public `LazyFont` javadoc** (LazyLib), which documents how
these same `.fnt` fonts are drawn:

- Fonts have a native size, `getBaseHeight()`; `createText(text, color, size, …)` scales
  glyphs from the atlas to the requested `size`.
- Quoted: the draw size *"should be evenly divisible by `getBaseHeight()`. Other values may
  cause slight blurriness or jaggedness."*

So the atlas resolution and the on-screen draw size are **decoupled through baseHeight
scaling**. A font whose atlas is rendered at an integer multiple of its draw size is
*downsampled* at draw time → supersampled → crisp. This **resolves the bigger-vs-sharper
question**: the vehicle for same-size sharpness is an `N×` descriptor (baseHeight and all
coordinates ×N) paired with a genuine `N×` atlas, drawn at the original size. A Starsector
developer tweet and the [1440p UI-scale blur thread](https://fractalsoftworks.com/forum/index.php?topic=25115.0)
confirm the complement: UI-scale (e.g. 140%) resamples the *whole rendered UI*, so even a
crisp native font softens under global scaling — argue for generating at a scale matched to
the user's UI-scale setting.

**The one load-bearing corollary:** upscaling the *baked* atlas gains nothing — interpolate
up, let the renderer scale down, and you are back to the original quality. A real gain
requires **re-rasterizing glyphs from the vector (TTF) source at N×**, not resampling the
shipped PNG. That in turn raises a licensing constraint (below).

The only residual unknown is whether the **core UI** draws each font at a fixed on-screen
size (so swapping in an `N×` descriptor+atlas at the same requested size yields crispness
directly) or at the font's declared size (which would enlarge text). The custom-font API —
`Global.getSettings().loadFont(path)` plus `setParaFont`/`setTitleFont`/… per the
[wiki.gg custom-fonts guide](https://starsector.wiki.gg/wiki/Using_Other/Custom_Fonts) —
lets a mod load fonts by path, and LazyFont-drawn text clearly honors an arbitrary size;
the core-UI size-selection is the single fact to confirm empirically (protocol below).

### What the community font evidence says (tier-4/5)

A 2024 report describes visible text blur at 140% UI scale; raising multisampling from 0
to 16 did little, and partial relief came only from a lower display resolution or by
selecting a larger existing font in the config. Others call the scaled UI blurry. Three
conclusions follow, and they narrow the design:

- **Launcher UI scaling resamples already-rasterized bitmap-font output** — it upscales
  finished text rather than supersampling the atlas, so a higher-density atlas does not
  automatically get sampled above 1:1 through UI scaling. This makes the naive "2× atlas +
  2× metrics" path unlikely to yield same-size sharpness on its own.
- **MSAA targets rendered geometry, not textured glyph interiors** — it offers little for
  an already-rasterized font atlas. Stop treating it as a font fix.
- **Selecting a larger font** improves readability through bigger glyphs but leaves the
  same-size/high-density problem unsolved.

**Refined recommendation.** Produce an `N×` font: an `N×` descriptor (this is the
`BitmapFont.scaled(N)` transform, landed — see below) paired with a **truly re-rasterized
`N×` atlas** from the vector source, drawn at the original on-screen size so the renderer
supersamples it down. Do **not** ship an upscaled baked atlas — it adds no detail.

**Licensing constraint (shapes the architecture).** The game ships baked atlases, not the
source TTFs, and several faces (Insignia LT, Victor, Futura) are commercially licensed.
Orbitron is OFL/free. So a *redistributable* upscaled-font pack is licensing-constrained,
while re-rasterization needs the vector source. The clean architecture is therefore a
**local generator tool** that ships code, not fonts: it rasterizes at the user's chosen
scale from OFL faces (or fonts the user already has), emitting `{name.fnt, name_<page>.png}`
pairs on their machine. A ready-made pack is only redistributable for OFL faces.

### Font tooling (landed — both halves + CLI)

The full offline `N×` font generator is implemented in `preflight-cli`:

- `BitmapFont` — parses AngelCode `.fnt` text; exposes `baseHeight()`, `charIds()`,
  `pageFiles()`; applies an exact integer `scaled(N)` over every pixel coordinate (`info
  size/padding/spacing/outline`, `common lineHeight/base/scaleW/scaleH`, `char
  x/y/width/height/xoffset/yoffset/xadvance`, `kerning amount`) while leaving ids, channels,
  flags, percentages, and page files untouched. Round-trips the real `insignia15LTaa.fnt`
  (233 glyphs, CRLF).
- `FontAtlasGenerator` — the atlas half: rasterizes a vector `java.awt.Font` into a shelf-
  packed atlas of white glyphs with anti-aliased alpha coverage (the layout Starsector's
  tinting renderer expects), computing exact BMFont metrics (`xoffset`/`yoffset` from the ink
  box relative to the baseline). Verified to pack every glyph inside atlas bounds.
- `preflight font generate` — `--ttf <font.ttf> | --logical sans-serif|serif|monospaced`,
  `--size <px>`, `--name`, `--out-dir`, `[--atlas-width] [--padding] [--charset-from
  <font.fnt> | --ascii | --latin1]`. Writes `<name>.fnt` + `<name>_0.png` and a JSON report.
  `--charset-from` copies an existing font's exact glyph coverage. Ships no fonts: the vector
  source is operator-supplied, keeping licensing with the user.

- `preflight font generate-pack` — batch mode: from one TTF and the game's `graphics/fonts`
  directory, generates a **matched replacement for every `.fnt`** (each at its original
  on-screen size × `--scale`, with its original coverage) and writes a complete drop-in mod
  (`mod_info.json` + `graphics/fonts/*`). One JVM, TTF loaded once — this is the
  bring-your-own-font mod generator.

### In-game results (confirmed 2026-07-24)

A real Starsector 0.98a-RC8 run with generated packs settled the open questions:

- **Mod override of core fonts works.** A mod shipping `graphics/fonts/<name>.fnt` at a core
  path replaces what the core UI renders. (First attempt changed almost nothing because it
  replaced `victor14`; `settings.json` sets `defaultFont = graphics/fonts/insignia15LTaa.fnt`
  — replacing that and the other insignia/orbitron sizes is what moves the UI. Hence
  `generate-pack`, which replaces all ~51 at once.)
- **The core UI renders each font at its declared `.fnt` metrics.** A 2× descriptor makes text
  **bigger**, not same-size-sharper. So LazyFont's baseHeight→draw-size downscaling applies to
  text a mod draws in code (`createText(…, size)`), **not** to the core UI. The N× same-size
  crispness route is therefore code-API-only; for the core UI, an N× pack is a *larger-text*
  (accessibility) option, best at native resolution.
- **Residual graininess is display/UI-scale, not the atlas.** At 100% UI scale a bitmap font
  renders ~1:1, so no atlas beats it; the softness the operator sees comes from running below
  native resolution / UI upscaling (dev-confirmed). The lever there is resolution settings.

Net: the shippable win is a **same-size whole-UI typeface swap** (`generate-pack --scale 1`) —
the bring-your-own-readable-font feature. "Bigger, clearer text" is a real but separate
accessibility option and would want fits-in-layout care (a whole-UI 2× overflows panels).

### Empirical protocol (resolves the residual unknown, license-clean)

Orbitron is OFL, so this needs no commercial font. Generate a 2×-size Orbitron pack matching a
core font's coverage — for example:

```bash
preflight font generate --ttf Orbitron-Regular.ttf --size 40 \
  --name orbitron20 --out-dir override/graphics/fonts \
  --charset-from /path/to/starsector-core/graphics/fonts/orbitron20.fnt
```

Drop it in as a core-font override and compare the same text at the same UI setting.
**Crisper at the same size** ⇒ the mechanism holds and the core UI honors a fixed draw size
(ship it). **Larger text** ⇒ the core UI uses the declared size (fall back to LazyFont-drawn
contexts via the custom-font API, or to a UI-scale-matched native re-rasterization).

### Font test matrix

```text
Display resolution:  native | one lower supported resolution
UI scale:            100% | 140% | 200%
Font source:         original atlas
                     hinted native-size atlas
                     offline-supersampled/downsampled native atlas
                     larger existing Starsector font
                     experimental 2x atlas with proportional metric changes
```

Capture lossless crops of identical text and measure: physical glyph height (screen px),
edge-spread width, local contrast, baseline position, character advance, fractional-position
blur, atlas bleed, clipping.

### Delivery options

- **Local generator tool (preferred; license-clean):** ship the generator, not the fonts.
  It re-rasterizes an `N×` atlas from an OFL face (or one the user supplies) and pairs it
  with `BitmapFont.scaled(N)`, writing `{name.fnt, name_<page>.png}` on the user's machine.
  Sidesteps redistribution of commercial faces entirely.
- **Standalone Starsector mod (only for OFL faces):** ship replacement pairs that override
  `graphics/fonts/*` — clean for Orbitron and any OFL substitute, not for Insignia LT /
  Victor / Futura. Verify a mod can shadow *core* font paths by load order (the project's
  winning-provider logic shows later roots win; confirm vanilla Starsector resolves core
  font resources the same way).
- **Folded into preflight:** a font atlas is a texture through the existing `TextureLoader`
  hook and the `.fnt` is an indexed resource, so the cache could serve enhanced pairs under
  the same exact/faithfulness gate — subject to the same licensing boundary.

## Sub-proposal B: texture super-resolution (broader, higher risk)

Offline SR as a new `Transformation` type, opt-in, deterministic, cached like prepared
textures.

- **Faithfulness gate (in the project's spirit):** round-trip check — downscale the
  upscaled result back to native and compare to the source; if it drifts past a
  threshold, reject and retain original bytes. A *faithfulness* budget instead of a
  *byte-identity* budget.
- **Model spectrum:** classical scalers (xBRZ/Super-xBR/scaleFX) are deterministic but
  tuned for pixel art and look waxy on Starsector's painterly sprites; ML models
  (Real-ESRGAN and kin) suit painterly art and are still reproducible (fixed weights, no
  seed) but can hallucinate — favor conservative settings + the round-trip gate.
- **Load-bearing gotcha — verify first:** ship/weapon sprites have gameplay-coupled pixel
  geometry (bounds points, weapon-mount coordinates, render scale defined relative to
  native sprite pixels in `.ship`/`.variant` data). Naive rescaling can change world
  render size or desync mounts/bounds. **Safe targets:** fonts, UI chrome, backgrounds,
  planet/star/nebula sprites, effect fringes. **Unsafe without display-size handling:**
  hulls and weapons.

### VRAM cost is first-class — the Asset Lab enforces a budget

A quality overlay improves fidelity while increasing launch work, heap pressure, upload
traffic, and VRAM. Because combat-only slowdown often traces to VRAM exhaustion (see
[community-evidence.md](community-evidence.md)), the cost must be estimated up front, not
discovered in a battle. Add a decoded-texture / VRAM estimator to `doctor` and profile
reports, built from the resource census:

```text
Decoded texture bytes:  RGB = w × h × 3      RGBA = w × h × 4
Additional allocations: mip levels, normal/material/surface maps, generated effect
                        textures, framebuffer attachments, temporary upload buffers
Enhancement multiplier: 2× width&height = 4× pixels    4× width&height = 16× pixels
```

Report the pixel multiplier prominently for any enhancement, and generate estimates for:
current profile; proposed enhanced overlay; current + overlay; common UI/campaign working
set; representative combat working set; GraphicsLib-associated maps; and the largest
individual texture allocations.

- The **Asset Lab** (offline, opt-in overlay generator) must **reject or warn** on outputs
  exceeding a configurable memory budget.
- Enhanced assets live in a **separate cache namespace and manifest** from the speed-track
  prepared textures, so a fidelity overlay can never silently enter a speed measurement.

**Implemented (core + prepare report).** `TextureMemoryEstimator` /
`TextureMemoryEstimate` (in `preflight-core`) compute the exact base decoded bytes, RGB/RGBA
counts, largest allocations, the full-mip-chain upper bound, the 2×/4× enhancement
projections (overlay-only and combined-if-both-resident), and a `exceedsBudget(long)` check —
pure arithmetic over the texture manifest, no game launch. `prepare` emits this under
`.stages.textures.details.memoryEstimate`, and `texture manifest inspect <manifest.spfm>`
prints it for any existing manifest without re-running preparation.

**Implemented (census budget gate).** `preflight scan --vram-budget <size>` grades the
override-resolved decoded floor `over` / `at-risk` / `under`, and `--max-texture-size <pixels>`
projects the floor after capping every override-winning texture's long edge — reporting the
oversized count, the projected floor, the largest individual savings, and the budget verdict
*after* the cut. The modelled reduction is repeated exact halving, because a 2x2 box reduction is
the one resize that is exact, preserves power-of-two dimensions, and matches GPU mip generation;
each step divides decoded cost by exactly four, so the projection is arithmetic rather than an
estimate. It projects memory only and rewrites no asset.

This is the "opposite of upscaling" lever from the FPS section below, and the first real-install
run corrected the intuitive plan: on a ~70-mod profile a **2048 cap touches only 5 textures and
saves 74 MiB** — the profile stays over a 4 GiB budget. The decoded cost is a long tail of
mid-sized art, not a handful of giants. A **1024 cap touches 211 textures and takes 4.36 GiB to
2.76 GiB**, comfortably under. Any future overlay generator inherits this gate.

**Implemented (the Asset Lab itself).** `preflight assets shrink --max-texture-size <pixels>
--out-dir <mod-dir>` turns the projection into an artifact: it writes capped copies of the
override-winning oversized textures as a **drop-in override mod**. The command is deliberately
separate from the speed-track `texture` cache commands, so a footprint overlay can never wander
into a speed measurement.

- **Never read-modify-write.** The installation is only read; the pack is a new directory. Undoing
  the change is disabling one mod. `--dry-run` reports without writing, and a non-empty output
  directory is refused without `--force`.
- **Exact and container-preserving.** Iterated 2×2 box halving, colour averaged *premultiplied by
  alpha* — a straight RGBA average pulls the colour of fully transparent pixels into the visible
  edge and haloes every sprite. Each file is written back in its own container (PNG, or JPEG
  re-encoded at quality 0.95) because the game resolves a texture by its exact logical path.
  Anything that would not round-trip at the same channel count is skipped and reported, not
  silently re-containered.
- **The projection is the delivery.** On the real ~70-mod profile a 1024 cap wrote all 211
  oversized textures in 30 s (44 MB on disk); re-measuring the written pack with the same header
  reader gives **exactly** the projected 521.85 MiB, against 1.60 GiB saved. Projection and
  delivered result are byte-identical, not approximately equal.
- **The caveat is the override order.** The pack only takes effect where it wins, so it must be
  enabled after every mod it replaces a texture for — the same enabled-order rule the census models
  as `probable-enabled-order-only`. Failing to win is visible as "no change", never as damage.

**Corrected 2026-07-25: resident VRAM is not `width * height * channels`.** Reading the installed
`com.fs.graphics.TextureLoader` (unobfuscated, a near-copy of Slick2D's loader) settled three facts,
now encoded once in `GpuTextureFootprint`:

1. **Both dimensions are rounded up to a power of two** by Slick's `get2Fold` before
   `glTexImage2D`. A 288×384 sprite allocates 512×512; the sprite's texture coordinates address only
   the used sub-rectangle and the rest is allocated and wasted. Preflight's agent already reproduced
   this padding for its upload buffers (see
   [2026-07-22-prepared-pixel-npot-padding.md](evidence/2026-07-22-prepared-pixel-npot-padding.md),
   which observed 597×373 → 1024×512 against the real engine); the *reports* had never learned it.
2. **The internal format is a hardcoded `GL_RGBA`**, so an opaque RGB source is resident at four
   bytes per pixel regardless of what was uploaded.
3. **Mip chains are opt-in per path** — the loader consults a static `Set<String>` of resource names
   and only those get `GL_LINEAR_MIPMAP_LINEAR`. So a full chain is an upper bound, not a given.

This is not new knowledge in the community, only new to preflight. Dark.Revenant published the same
arithmetic in July 2019 — the 288×384 Onslaught as "512x512 pixels with 4 bytes per pixel and an
overall 4/3 increase in size due to the mipmapping", 1365 KiB resident — and gave the estimate as
width × height × **16/3** bytes. Our `residentBytes` and `residentBytesWithMipChain` are exactly the
lower and upper ends of that. See
<https://fractalsoftworks.com/forum/index.php?topic=15674.15> (replies #15 and #21).

Consequences on the real ~70-mod profile:

| | |
|---|---|
| decoded pixel data (what preflight used to report) | 4.36 GiB |
| **resident VRAM** | **6.91 GiB** |
| of which pure power-of-two padding | 1.86 GiB (27%) |

Every budget verdict now grades resident bytes. This *reversed* earlier advice: a 1024 cap was
reported as clearing a 4 GiB budget and in fact leaves the profile `over` (6.91 → 4.56 GiB); even a
512 cap only reaches `at-risk`. The padding figure also confirms independently what xenoargh and
Dark.Revenant identified in that same 2019 thread as recoverable by atlasing (~42% for an even
sprite-size distribution) — reachable here without engine changes by snapping near-boundary textures
down to the power of two below.

Still to do: snap-to-POT in `assets shrink`, the census UI/campaign/combat/GraphicsLib-map
breakdowns, a separate cache namespace and manifest (roadmap #8), and judging the visual cost of a
cap in-game.

## Explicitly out of scope: in-game FPS

Big-battle slowdown is a largely **single-threaded CPU bottleneck** (combat sim,
projectile physics, AI scripts). This project is deliberately non-invasive to the game's
sim/render loop, and pre-decoded textures are already resident in VRAM by then, so they do
not help steady-state FPS. The only adjacent, honest win is the *opposite* of upscaling:
texture **compression / shrinking oversized mod textures** to reduce VRAM thrashing and
its stutter on low-VRAM machines — a frametime-stability lever, not a raw-FPS one, and one
that trades against Sub-proposal B. A July 2026 community case (smooth campaign, severe
combat slowdown, resolved by cutting VRAM demand and GraphicsLib settings) corroborates
this, though it remains a single recent report — see [community-evidence.md](community-evidence.md).

## Audio quality: deprioritized

Audio super-resolution (bandwidth extension) mostly hallucinates high frequencies onto
source that is already at an adequate sample rate — low ROI, easy to worsen. The audio
subsystem's measured value is decode/load **speed** (~24–28% of samples); keep it there.

## Suggested next steps (measurement-first, matching project culture)

1. Confirm where a heavy-profile load actually spends time using the existing JFR
   attribution, so any quality feature is judged against real per-subsystem numbers.
2. Prototype the font atlas at 2× on a single font, resolve the bigger-vs-sharper
   question empirically, and test core-font shadowing by a standalone mod.
3. Only then consider Sub-proposal B, starting with backgrounds/planets behind a
   round-trip faithfulness gate — never hulls/weapons first.

## External references and research terms

Community load-time and profiling knowledge:

- Fractal forum — Faster Save/Load/Boot times (settings.json): <https://fractalsoftworks.com/forum/index.php?topic=23851.0>
- Starsector Wiki — Troubleshooting slowdown (recommends VisualVM sampler): <https://starsector.fandom.com/wiki/Troubleshooting_slowdown>
- Fractal forum — Performance Issue / CPU bottleneck: <https://fractalsoftworks.com/forum/index.php?topic=18689.0>

Font rendering mechanism (authoritative):

- LazyFont javadoc — baseHeight scaling, "evenly divisible by getBaseHeight()": <https://lazywizard.github.io/lazylib/org/lazywizard/lazylib/ui/LazyFont.html>
- Starsector Wiki (wiki.gg) — Using Other/Custom Fonts (`loadFont`, `setParaFont`): <https://starsector.wiki.gg/wiki/Using_Other/Custom_Fonts>
- Fractal forum — Blurry fonts with UI scaling (2560×1440): <https://fractalsoftworks.com/forum/index.php?topic=25115.0>

Texture super-resolution prior art / pipelines:

- No One Lives Forever ESRGAN x4 upscale pack (ModDB): <https://www.moddb.com/mods/no-one-lives-forever-esrgan-upscale-pack>
- LithTech-engine upscaling tutorial (ESRGAN workflow): <https://www.moddb.com/mods/no-one-lives-forever-esrgan-upscale-pack/tutorials/upscaling-lithtech-engine-games>
- ESRGAN tag index (many game upscale packs): <https://www.moddb.com/tags/esrgan>
- SWTOR Textures Upscaler (reference batch pipeline, GitHub): <https://github.com/ZeroGravitasIndeed/SWTOR-Textures-Upscaler>

Search terms to run where the crawler is blocked (Reddit/forum behind Cloudflare):

- `Starsector settings.json faster loading boot time`
- `Starsector reddit load time many mods GraphicsLib performance`
- `Starsector single threaded combat FPS CPU bottleneck`
- `Starsector mod override core graphics fonts load order`
- `Starsector font mod crisp high resolution UI scaling retina`
- `AngelCode BMFont regenerate atlas 2x metrics supersample`
- `Real-ESRGAN faithful game sprite upscale round-trip validation`
- `GraphicsLib LunaLib MagicLib load time performance`

## Measured: what block compression actually costs on this art (2026-07-25)

The standing objection to compressing Starsector's textures is Dark.Revenant's, from the 2019 thread
above: S3TC "will have significant visual artifacts when we're talking about the detailed, 1:1 2D
textures that Starsector uses". That is an empirical claim about an art style, and in seven years
nobody appears to have measured it. `preflight assets compression-probe` measures it.

Method: every sampled override-winning texture is round-tripped through a real BC1/BC3 encoder
(`BlockCompressor`) and compared to its original in **CIELAB Delta-E** (`TextureFidelity`), where the
threshold for human perceptibility is a published number — under 1.0 is imperceptible, over 2.0 is
visible at a glance — rather than a matter of opinion. Errors are scaled by alpha coverage, since a
wrong colour under a near-transparent pixel is not seen. Nothing is written or uploaded.

Two corrections were needed before the numbers meant anything, both found by the measurement
disagreeing with itself:

- **Shader maps are not art.** Normal, material and surface maps store vectors and scalars in RGB
  channels. They are sampled by shaders, never viewed; Delta-E on one is not a perceptual quantity,
  and BC1/BC3 is the wrong codec for reconstructed vectors regardless (BC5 exists for this). They are
  reported separately and score far worse — median mean Delta-E **10.8**.
- **Texture count is not the question; bytes are.** A per-texture average is dominated by hundreds of
  tiny detailed sprites that together occupy almost no memory.

Sampled 2000 textures from the real ~70-mod profile, art only, bucketed by longest edge:

| bucket | textures | resident | compressed | median mean ΔE | median p99 ΔE | share of art VRAM |
|---|---|---|---|---|---|---|
| ≤64 px | 513 | 4.2 MiB | 0.9 MiB | 6.25 | 20.55 | 0.9% |
| ≤256 px | 382 | 39.7 MiB | 8.8 MiB | 4.26 | 15.25 | 8.9% |
| ≤1024 px | 158 | 156.3 MiB | 31.7 MiB | 2.47 | 10.50 | 34.9% |
| **>1024 px** | 17 | 248.0 MiB | 36.0 MiB | **0.80** | 2.50 | **55.3%** |

Fidelity improves monotonically with size, and the conclusion inverts. Dark.Revenant is right about
the textures he had in mind — small, detailed, hue-dense sprites are genuinely mangled — but those
are **under 1% of video memory**. The textures that hold the memory are large, smooth and
photographic, and they round-trip at a median mean Delta-E of **0.80, below the threshold of human
perceptibility**, in the very format he was criticising. BC7, which the hardware supports (a player's
posted context in that era already reported GLSL 4.60 and `GL_ARB_texture_compression_bptc`), has
higher-precision endpoints and per-block partitioning and would do better still.

That points at a **selective policy** rather than a global switch: compress large art, leave small
sprites at full precision, and leave shader maps alone or move them to BC5. On the sample that keeps
roughly 90% of art VRAM at about 6x while touching nothing that measures badly.

`BlockCompressor`'s own tests exist to keep this honest: a weak encoder would produce evidence against
BC that is really evidence against the code. They pin its behaviour on flat blocks, gradients, sharp
two-colour edges and alpha ramps. One residual is the format's and not the encoder's — RGB565
endpoints put red and blue on a 5-bit grid, so neutral greys pick up a slight cast, worst near black
where L* moves fastest. That is a BC1 property BC7 does not share.

Still unmeasured: BC7 itself, and whether any of this survives contact with the runtime.
