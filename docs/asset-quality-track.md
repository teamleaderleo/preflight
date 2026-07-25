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
| ≤64 px | 513 | 4.2 MiB | 0.9 MiB | 6.19 | 21.40 | 0.9% |
| ≤256 px | 382 | 39.7 MiB | 8.8 MiB | 4.13 | 15.55 | 8.9% |
| ≤1024 px | 158 | 156.3 MiB | 31.7 MiB | 2.37 | 10.15 | 34.9% |
| **>1024 px** | 17 | 248.0 MiB | 36.0 MiB | **0.76** | 2.40 | **55.3%** |

*(Delta-E figures refreshed after the encoder work below. The original run measured 6.25 / 4.26 / 2.47
/ **0.80**; every bucket's mean improved slightly and no conclusion changed.)*

Fidelity improves monotonically with size, and the conclusion inverts. Dark.Revenant is right about
the textures he had in mind — small, detailed, hue-dense sprites are genuinely mangled — but those
are **under 1% of video memory**. The textures that hold the memory are large, smooth and
photographic, and they round-trip at a median mean Delta-E of **0.80, below the threshold of human
perceptibility**, in the very format he was criticising.

**Corrected the same day.** This section originally continued "BC7 … would do better still", citing a
player's posted context from that era reporting `GL_ARB_texture_compression_bptc`. That is true of PC
hardware and false on the reviewed machine: Apple's OpenGL stops at 4.1, BPTC is core in 4.2, and a
BC7 upload returns `GL_INVALID_ENUM` in both the driver-compression and pre-encoded paths — see
[the GPU capability probe](evidence/2026-07-25-macos-gl-capability-probe.md). The M5 supports BC7
through Metal; nothing reachable from Starsector's GL context can use it. On macOS the choice is
**BC1/BC3 or nothing**, which is exactly what was measured above, so the table stands — but the
escape hatch of "BC7 will rescue the small sprites" is closed, and the selective policy below becomes
load-bearing rather than merely tidy.

That points at a **selective policy** rather than a global switch: compress large art, leave small
sprites at full precision, and leave shader maps alone or move them to BC5. On the sample that keeps
roughly 90% of art VRAM at about 6x while touching nothing that measures badly.

`BlockCompressor`'s own tests exist to keep this honest: a weak encoder would produce evidence against
BC that is really evidence against the code. They pin its behaviour on flat blocks, gradients, sharp
two-colour edges and alpha ramps. One residual is the format's and not the encoder's — RGB565
endpoints put red and blue on a 5-bit grid, so neutral greys pick up a slight cast, worst near black
where L* moves fastest. That is a BC1 property BC7 does not share — and, per the correction above, one macOS cannot escape.

## What the driver actually allows (2026-07-25)

The compression probe measured what a format *costs*. It did not establish that any of it can be
uploaded. [`probe-kits/gpu-capability`](../probe-kits/gpu-capability/README.txt) asks the driver
directly, by performing uploads and reading back what was stored; the full record is in
[2026-07-25-macos-gl-capability-probe.md](evidence/2026-07-25-macos-gl-capability-probe.md).

On the reviewed machine (Apple M5, macOS 26.5.1, driver `2.1 Metal - 90.5`):

- **BC1/BC2/BC3/BC4/BC5 all upload**, by both routes — the driver compressing an ordinary RGBA
  upload, and pre-encoded blocks via `glCompressedTexImage2D`. BC5 working confirms the shader-map
  recommendation above is actionable and not just correct in principle.
- **BC7 does not exist here.** `GL_INVALID_ENUM` on both routes.
- **Non-power-of-two textures upload natively.** 597×373 is stored as 597×373. The loader's
  `get2Fold` padding is inherited Slick2D behaviour, not a requirement of this hardware, and it costs
  **1.86 GiB (27%)** of resident VRAM on the reviewed profile for nothing. Removal is still hard —
  two independent power-of-two implementations in `TextureLoader`, and sprite texture coordinates
  computed against the padded size — but the driver is not the obstacle.
- **The engine's upload site could compress with one changed constant.** `TextureLoader` hardcodes
  `GL_RGBA`; substituting a compressed internal format at that same call yields
  `GL_TEXTURE_COMPRESSED = 1` and a quarter of the bytes, with no asset pipeline, no encoder and no
  new file format.
- **The driver's own encoder is 1.6–2.0× worse than `BlockCompressor`** on real core art, measured by
  reading textures back with `glGetTexImage` and scoring them with `TextureFidelity`. On
  `planets/aurorae2.png` the driver lands at mean ΔE 1.27 and ours at 0.84 — straddling the
  perceptibility threshold.

**None of this is portable, and that is now a design constraint rather than a footnote.** Apple's GL
is capped at 4.1; BPTC is core in 4.2. Windows and Linux drivers are not capped, so BC7 is expected
to be available there on any GPU from roughly 2012 onward — unverified, which is why the probe was
rewritten to run on Windows and Linux too, on the game's own LWJGL and bundled JVM. Starsector ships
for all three platforms. The consequence is that **the best available format differs by machine**, so
any shipped pipeline has to select per machine rather than pick one format globally — which is
precisely the problem Basis Universal/KTX2 exists to solve, and moves it from "overkill" to
"probably the right architecture" the moment this targets more than macOS.

The last two together define the trade. The one-constant change is global and therefore applies the
driver's quality to the small detailed sprites that measure worst — precisely the ones the community
objection is about. A selective policy needs per-texture control, which needs the offline path.

**And the offline path is not only a footprint lever.** Today a texture is read as PNG,
zlib-inflated, decoded to RGBA, padded, and uploaded at 4 B/px. A pre-encoded block texture is read
and uploaded — *no decode stage at all*, at an eighth of the bytes. That puts block compression on
the speed track, not opposite it, which is a different proposition from the one this document opened
with.

**Measured 2026-07-26, and the claim holds by a wider margin than expected.** Decomposing a real
load on the 78-mod profile
([evidence](evidence/2026-07-26-texture-load-pipeline-decomposition.md)): ImageIO decode is
**67–70%** of texture load, the raster walk and power-of-two padding another **25–28%**, the GPU
upload **under 3%**, and disk about **3%**. CPU work is **94.6%** of it. So a change that removes
bytes uploaded is nearly irrelevant to load time, and a change that removes the decode is almost all
of it. Priced against each other, a block cache runs **61–74× faster than vanilla** and **~4× faster
than preflight's existing prepared-pixels cache**, while reading a quarter of the bytes and leaving a
quarter of the VRAM resident — it dominates the incumbent on every axis at once, with no trade to
weigh. The padding also turns out to cost load time and not only memory, since the raster stage
allocates and fills the padded buffer.

## Research frontier: what changed between 2019 and 2026 (surveyed 2026-07-25)

The 2019 forum discussion is the standing state of community knowledge. Surveying what has appeared
since, most of it does not apply here, and the reasons are worth recording so the same ground is not
re-covered.

**Applies.**

- **RDO block encoders.** `bc7enc_rdo` (Geldreich) is the current state of the art for BC1–7, and its
  BC1 encoder is among the best available; it introduced "prioritised cluster fit", 3–4× faster than
  traditional cluster fit at equal or better quality. Separately, its *rate-distortion* mode biases
  block choices so the encoded texture compresses 10–50% better under a following LZ pass, at
  slightly higher distortion. Both are directly relevant: the first is a quality ceiling to measure
  `BlockCompressor` against, the second shrinks the on-disk cache, which is load time.
  It is C++ under MIT/public domain — usable as reference for a Java port, not as a dependency.
- **Basis Universal / KTX2.** Store once, transcode at load to whatever the machine supports. Its
  2.5 transcoder reads and transcodes LDR `.DDS` in BC1–7. This is the principled answer to "which
  format do I ship" when the answer differs per machine — and the probe just proved it *does* differ
  per machine. Worth revisiting if this ever ships beyond one platform; overkill while the answer on
  macOS is "BC1/BC3, always".

**Raised and rejected.**

- **BC7 / BPTC.** Rejected on this platform by direct measurement, not by reasoning. See above.
- **ASTC.** `GL_KHR_texture_compression_astc_ldr` absent from every profile probed. The M5 supports
  ASTC natively through Metal; Apple's OpenGL does not expose it. Same API ceiling as BC7.
- **Neural texture compression.** NVIDIA's RTX NTC reaches far higher ratios by compressing a whole
  PBR material set jointly and decoding through a small MLP in the pixel shader. It requires
  cooperative-vector extensions (`VK_NV_cooperative_vector`, D3D12) for the matrix inference to be
  fast enough, needs shader-side decode integration, and is not reachable from OpenGL 2.1 at all.
  It also assumes multi-channel PBR material sets, which Starsector's 2D art largely is not. Not
  applicable now and not applicable on this hardware; noted so it is not mistaken for an oversight.
- **Runtime frame generation / upscaling (DLSS, FSR, MetalFX).** Out of scope by the same argument as
  the FPS section: this project does not touch the render loop.

**Changed underneath us, and relevant.**

- **Starsector 0.98a (2025-03-27) moved the game to Java 17**, which is why this project targets JDK
  17 and why AppCDS is available at all. 0.98.5a is in development as of April 2026.
- **The OpenJ9 JRE thread (May 2025) turns out not to overlap this project**, and to contain a lever
  rejected on the wrong benchmark. Its numbers are steady-state frame rate and resident memory
  (42.5 vs 32.5 fps; 1205 vs 1313 MB) — nothing in it measures startup. Its install notes disable
  OpenJ9's **shared class cache**, the counterpart to preflight's AppCDS archive, on the grounds of
  "questionable performance improvements" and 300 MB of disk. A class cache does its work during
  class loading, before the first frame, so an FPS benchmark cannot detect it by construction. Read
  in full at [2026-07-25-macos-rosetta-runtime.md](evidence/2026-07-25-macos-rosetta-runtime.md).
- **On Apple Silicon the game runs under Rosetta 2.** The macOS build ships an x86_64 JVM and x86_64
  LWJGL 2 natives with no arm64 slice. Every CPU-side measurement this project has taken on macOS was
  taken through binary translation, and a native arm64 runtime — blocked only by LWJGL 2 predating
  Apple Silicon — is likely a larger lever on that hardware than anything preflight does. Same
  evidence document.

Sources for this survey, in case anyone needs to check the reasoning:

- bc7enc_rdo: <https://github.com/richgel999/bc7enc_rdo>
- bc7enc_rdo usage and RDO examples: <http://richg42.blogspot.com/2021/02/how-to-use-bc7encrdo.html>
- Basis Universal: <https://github.com/BinomialLLC/basis_universal>
- KTX2 support details: <https://github.com/BinomialLLC/basis_universal/wiki/KTX2-File-Format-Support-Technical-Details>
- RTX Neural Texture Compression SDK: <https://github.com/NVIDIA-RTX/RTXNTC>
- Variable-rate texture compression with JPEG (2025): <https://arxiv.org/pdf/2510.08166>
- Starsector 0.98a release (Java 17): <https://fractalsoftworks.com/2025/03/27/starsector-0-98a-release/>
- OpenJ9 JRE performance thread: <https://fractalsoftworks.com/forum/index.php?topic=32926.0>

## How much of the loss was the encoder rather than the format? (2026-07-25)

Everything above rests on one assumption: that `BlockCompressor` is good enough that its results are
about BC1 rather than about our code. The tests pin its behaviour on synthetic cases, but they cannot
answer *how far from optimal* it is. Three hypotheses were tested against a fixed corpus of 293 real
core textures, each measured before and after.

**Hypothesis 1 — the objective is wrong. Mostly false; worth ~1.5%.** The encoder minimised squared
error in gamma RGB with fixed `2:4:1` channel weights, while the whole project grades results in
perceptual Delta-E. Those are different objectives, and the mismatch is worst in dark regions, where
lightness goes as roughly the cube root of luminance so a fixed RGB step is far more visible. Moving
endpoint and index selection into **Oklab** (perceptually uniform, better-behaved than CIELAB for
blues and for blends between distant colours, and cheaper) improved the mean by 1.7% on large
textures. Real, consistent, and much smaller than expected.

**Hypothesis 2 — the endpoint search is too shallow. False; worth 0.6%.** Widening the 5:6:5 grid
hill-climb from ±1/2 rounds to ±2/6 rounds bought 0.6% for 37% more time. The endpoints were not what
was stuck.

**Hypothesis 3 — the *index assignment* is structurally stuck. True, and the real one.** Bounding-box
fit plus iterative refit alternates between picking each pixel's palette entry greedily and refitting
endpoints to that pick. It converges, but never to a solution whose assignment differs from what
greedy selection produces. **Cluster fit** searches assignments directly: because the palette is four
evenly spaced points on a line, an optimal assignment is contiguous once pixels are sorted along that
line, so the whole space is the ways of splitting 16 sorted pixels into 4 ordered runs — 969 of them,
each scorable in constant time from prefix sums.

That search is done in gamma RGB, not Oklab, because the hardware blends stored endpoints linearly in
exactly that space — it is where the "palette is a line" premise is actually true. Perceptual distance
then decides the quantised endpoints and final indices, where no linearity is assumed.

| | mean ΔE | p99 ΔE | throughput |
|---|---|---|---|
| bounding box + weighted RGB (original) | 4.6622 | 18.398 | 5.65 Mpx/s |
| + Oklab objective | 4.6383 | 18.475 | 3.88 Mpx/s |
| + cluster fit | **4.5395** | **18.203** | 1.44 Mpx/s |

**The finding is the size of the number: 2.6%, for four times the encode cost.** The encoder was
already close to the format's ceiling, and BC1's limits are BC1's. That is worth more than the 2.6%,
because it is what licenses the measurements above: the compression probe's conclusions are not
artifacts of a weak encoder, and a better encoder does not rescue the small detailed sprites — nothing
will, short of a format macOS cannot reach.

Two honest caveats. The cost is real: 4× slower, though it is an offline one-time bake and trivially
parallel. And there is exactly one case that trades rather than wins — a synthetic full-range neutral
grey ramp, BC1's acknowledged worst case, where the mean improves (1.144 → 1.071) and the single worst
pixel degrades (3.66 → 4.09). On real art there is no trade; both mean and p99 improved. The test for
that case documents the trade rather than hiding it.

Still not measured: how `BlockCompressor` compares against `bc7enc_rdo`'s BC1 encoder, which remains
the external reference for "how good can this get".

## The encoder now writes bytes, and the driver agrees with them (2026-07-26)

Everything above was measured with an encoder that never produced a block. `roundTrip` took pixels and
returned pixels; the eight bytes a GPU reads did not exist. A block cache needs them, and writing them
exposed a real defect and then confirmed the rest.

**BC1 reads its endpoint order as a mode bit.** `code0 > code1` gives the four-colour palette every
fidelity number above assumes; `code0 <= code1` gives a three-colour palette whose fourth entry is
transparent black. Nothing upstream constrained the order — cluster fit orients endpoints along a
principal axis whose sign is arbitrary — so it was a coin flip per block. Left unordered, mean ΔE on a
smooth 256×256 field goes **1.69 → 18.44** and the worst pixel **7.2 → 154.9**, on roughly half the
blocks. Ordering the codes before indices are assigned costs nothing: it swaps palette entries 2 and 3,
and every fidelity test above passes with identical numbers. The measurements were never wrong; the
serialisation they had not yet reached was.

**The driver was then asked to arbitrate**, since `encode` agreeing with `decode` proves nothing about
a shared misreading. `BlockUploadProbe` uploads real blocks, reads them back decompressed, and compares
against the software decoder. On Apple M5: **BC1 and BC3 both bit-exact, 65,536 pixels each, zero
deviation.**

That took one fix, and the fix is the interesting part. BC3 alpha initially came back low by exactly 1
on exactly 50% of pixels — truncation against rounding. On the same driver, in the same block, **the
colour blends truncate and the alpha blends round.** No principle predicts that; applying rounding to
both, the tidy thing to do, would have broken the BC1 agreement that was already exact.

**Then the same vector was run on rented NVIDIA hardware**, and the picture completed awkwardly:

| implementation | exact | worst deviation |
|---|---|---|
| Apple M5 (Metal) | 100.00% | 0 |
| Mesa llvmpipe (CPU) | 91.37% | 1 |
| NVIDIA Tesla T4 | 45.31% | 1 |

Three implementations, three different colour-blend roundings, all differing by exactly 1, with the
alpha blends agreeing everywhere. There is no portable level table, and preflight matches the vendor
with the smallest share of players.

That is not the problem it looks like, because it was priced rather than argued about: the
disagreement is **mean ΔE 0.206, max 0.439** against a just-noticeable threshold of 1.00, and it moves
measured fidelity by 0.4% with the maximum unchanged. **Decision: keep one level table and do
nothing.** Both tempting shortcuts — assuming portability, or shipping per-driver tables — would have
been wrong, in opposite directions.

The layout claim that the block cache actually rests on is now confirmed by three independent decoders
across two vendors' silicon plus a CPU implementation.

Full result and method: [2026-07-26-encoder-driver-byte-agreement.md](evidence/2026-07-26-encoder-driver-byte-agreement.md).

## The block cache has a format and a bake path (2026-07-26)

With the encoder's bytes confirmed against two vendors' hardware, the blocks needed somewhere to live.
`BlockTexture` / `BlockTextureIO` (magic `SPFB`) and `BlockTextureBaker` are that.

**It is a separate type from `PreparedTexture`, deliberately.** The prepared-pixel cache is a promise:
its pixels are exactly what the game's loader would have produced, so a consumer may substitute it
without further thought. Block data breaks that promise by design. Sharing one type would reduce the
difference to a codec field that any consumer could forget to check, and the failure mode — a silently
degraded game — has no exception, no log line and no test that would catch it. Separate magic means the
two caches cannot be read into each other even if a path is wrong.

Three things ride along with the blocks, each because the alternative is a cache nobody can reason
about after the fact:

- **The encoder's identity** (`BlockCompressor.CODEC_VERSION`). A blob written by last month's encoder
  is not corrupt, is internally consistent, and is indistinguishable from a current one by inspection.
  Nothing but a declared version can tell them apart. This encoder has already changed its output twice
  — endpoint ordering, alpha rounding — and *both changes were improvements*, which is exactly the case
  where forgetting to invalidate is easiest and the resulting mixed cache hardest to notice.
- **The GL internal format**, so the upload passes a constant decided when someone looked at the pixels
  rather than re-derived from a channel count at load time.
- **The fidelity report measured at bake time**, required rather than optional. It should not be
  possible to have put a lossy texture into a cache without having measured the loss, and keeping the
  number in the artifact means a complaint about one specific sprite can be answered without
  re-encoding anything.

Format choice is per texture: BC1 for fully opaque images, BC3 for anything with alpha, at twice the
cost. That split is worth making because on this art the memory sits in large opaque backgrounds while
the alpha lives in comparatively small sprites. BC1's punch-through mode would encode one-bit cutout
alpha at BC1 prices, which would suit a lot of sprites — `BlockCompressor` never emits it, so that
saving is named here and not claimed.

### The mip filter found a defect in the shipped one

Baking a mip chain needs a downsampler, and writing one surfaced a real bug in `AssetLabCommand.halve`,
which `assets shrink` already ships. Both were a 2×2 box filter; a 2×2 box is only correct when the
dimension is even. OpenGL's next level is `max(1, size >> 1)`, so a 5-pixel row becomes 2 — and a 2×2
box reads source pixels 0–1 and 2–3 while **the fifth is never read at all.** Content silently deleted,
and worse at every subsequent level.

Both now use an area average: each destination pixel averages exactly the source interval it covers,
with fractional weights at the ends. It reduces to the plain 2×2 box whenever the dimension is even, so
**nothing changes for power-of-two art** — including everything a released `assets shrink` has already
written — and it stays correct when the dimension is odd. The filter definition lives once, in
`ImageResampler`; `assets shrink` keeps its own row-streaming traversal (it runs over hundreds of
textures and the largest are tens of megabytes as a `BufferedImage`) but takes the weights from core,
and a test pins the two to identical output so they cannot drift.

The old behaviour had been pinned by a test named `dropsTheTrailingRowAndColumnOfAnOddSizedImage`,
which justified the drop as "matching the census projection". That reasoning was wrong in a specific
way worth naming: the projection constrains the output *dimensions*, and the area average produces the
same `max(1, dim >> 1)` — so it was never the projection that required discarding the column.

There is a second cost beyond the lost pixels, smaller but in the same direction. The output no longer
represents the whole source extent — a 1023-wide row halved by a 2×2 box represents 1022 of its
columns — so the surviving image is very slightly stretched and offset relative to the original
framing, and `assets shrink` halves repeatedly. Three halvings of 1023 misrepresent about 0.3% of the
extent. That is small enough that nobody would have found it by looking, which is the argument for
fixing it while the filter is already open rather than deciding whether 0.3% matters to the
gameplay-coordinate mapping this document flags elsewhere for hull sprites.

Both filters do get the more important thing right: colour is averaged **premultiplied by alpha**. A
straight RGB average weights a fully transparent pixel's colour as heavily as an opaque one, and since
transparent pixels in real art are stored as black, the symptom is a dark halo creeping around every
sprite edge, a little worse at each level.

## A profile can now be baked into a block cache (2026-07-26)

`preflight assets bake-blocks --out-dir <cache-dir>` walks the override-resolved profile, encodes each
texture, and writes `BlockTexture` blobs plus a `BlockCacheManifest`.

**The interesting part is the refusal to encode.** Every texture is baked, measured, and then kept only
if p99 ΔE came in under a stated gate — default `1.0`, the just-noticeable threshold. A texture with no
manifest entry is not a failure; it keeps the ordinary decode path. This per-texture policy is the
whole reason an offline encoder beats flipping the engine's internal-format constant, which would
compress everything at whatever quality the driver felt like.

Three outcomes, each reported with its reason:

| outcome | why |
|---|---|
| cached | measured loss under the gate |
| over the fidelity gate | keeps the ordinary decode path — a normal result, not an error |
| shader map | a normal or material map stores vectors, not colour; ΔE does not describe it, and reconstructing a unit vector from two interpolated endpoints is the failure BC5 exists to fix |

Blobs are content-addressed by source hash, so several mods shipping byte-identical art share one blob
and it is encoded once — encoding is by far the slowest step and duplicated art is common in this
ecosystem. On a four-texture fixture with one duplicate, the run reports `cachedTextures: 2,
distinctBlobs: 1`.

**Nothing reads this cache yet.** It is inert until a runtime adapter exists, which is deliberate — the
cache can be baked, inspected and argued about before anything is wired into a loading game. In the
meantime the report is the deliverable: how much of a profile clears the gate, what the cache costs,
and what it saves.

### A synthetic gradient is harder for BC1 than real art

Worth recording because it inverts the obvious intuition and would mislead anyone testing with
generated images. Measured on 64×64 tiles:

| image | mean ΔE | p99 ΔE |
|---|---|---|
| flat fill | 0.000 | 0.000 |
| soft low-contrast noise | 0.938 | 1.700 |
| diagonal colour gradient | 1.015 | 3.100 |
| grey ramp | 1.295 | 4.050 |

A smooth gradient looks like the easy case — it *is* linear, which is exactly what a two-endpoint
interpolation represents. But when a 4×4 block spans only a couple of levels, the error is no longer
about the fit at all: it is the **RGB565 quantisation of the endpoints themselves**, 8/255 steps in red
and blue. Real photographic art varies more within a block, so the endpoints land on colours worth
having, which is why the real-profile measurements come in near ΔE 0.80 while a synthetic ramp measures
4.

## What can be tested without launching Starsector (2026-07-26)

Worth stating plainly, because the answer changed when the block cache landed and the boundary is not
where it looks.

**Already automated, no game involved.** There is a synthetic Starsector in the test tree —
`com.fs.graphics.TextureLoader` carrying the real obfuscated method names (`Ô00000`, `o00000`), plus
`com.fs.graphics.L` and `com.fs.starfarer.loading.A`, driven by `SyntheticTextureLauncher` **in a child
JVM** so the agent is genuinely injected. `preflight-synthetic-startup` is a further module of
cross-process startup workloads. Agent injection, bytecode transformation, cache hit/miss and fail-open
fallback are all covered.

**The gap the block cache opened.** Every one of those harnesses stops at the byte level — the stub
loader counts calls and returns arrays, and never calls `glCompressedTexImage2D`. For the prepared-pixel
cache that was the whole contract. For blocks it is not: a wrong internal-format constant, a wrong
mip-level order or a wrong row order passes every existing test and fails only on a player's machine.

**How it was closed.** `preflight assets cache-conformance` exports a sample of a baked cache in the
same `SPFV` format the driver probe already reads, so `block-conformance-probe` — unchanged, and already
verified on two vendors — arbitrates the real cache. Confirmed end to end on this machine:

```
graphics/ships/hull.png  pixels=65536  exact=100.00%  mean dev=0.000  worst dev=0
VERDICT: this driver reads the blocks preflight writes.     [Apple M5, hardware]
```

The synthetic vector keeps its own job: it is deterministic, so it answers questions *about a driver*
where two machines' results must be comparable. The cache vector answers whether *this cache* survives
*a real driver*. Different questions, same probe.

**What stays out of reach.** `starfarer_obf.jar` is not redistributable, so the synthetic is a model of
the engine rather than the engine. Two known risks it structurally cannot catch: the real
`TextureLoader` has two independent power-of-two implementations (already the blocker on padding
removal), and Starsector's asynchronous image-preloader handoff has timing the stub does not reproduce.
Both need the real installation — though not necessarily a launched game, since the offline
installed-class contract checker pattern from PR #119 reads real class bytes without starting anything.

**Where the rest of the reasoning lives.** [verification-strategy.md](verification-strategy.md) carries
the full tier map, why the VPS and the Lima VM cannot host the driver check while Modal can, what was
found when the neighbouring `renderprove` and `smolrunner` repositories were surveyed for reusable
parts, and the 2026 survey of visual-regression and agent-harness tooling. Its main conclusion for this
track: the shader-map classifier is a filename heuristic whose misfires are invisible to ΔE in both
directions, which is the strongest case for building `assets contact-sheet`.

## Where this leaves the footprint program

Ordered by ratio of effect to risk, with everything now measured rather than assumed:

| lever | resident VRAM | load time | quality cost | status |
|---|---|---|---|---|
| today | 6.91 GiB | baseline | — | — |
| `assets shrink` cap to 1024 | 4.56 GiB | unchanged | resolution loss, visible | shipped |
| snap-to-POT in `assets shrink` | recovers part of 1.86 GiB | unchanged | resolution loss on near-boundary textures only | next |
| stop padding in-engine | −1.86 GiB | unchanged | **none, lossless** | needs two coordinated bytecode edits |
| one-constant BC3 at the upload site | ÷4 | slightly worse (decode *then* compress) | driver-encoder quality, global | probed, unbuilt |
| offline BC + `glCompressedTexImage2D` | ÷4 to ÷8 | **better — no decode stage** | selectable per texture; ΔE 0.80 on the art that holds the memory | encoder driver-verified; blob format and baker landed; no manifest, no runtime consumer |

Still unmeasured: whether any of this survives contact with the runtime, and how
`BlockCompressor` compares against `bc7enc_rdo`'s BC1 encoder as a quality ceiling.
