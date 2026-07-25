# macOS / Apple Silicon GPU texture capability probe (2026-07-25)

Captured with [`probe-kits/gpu-capability`](../../probe-kits/gpu-capability/README.txt) on the
reviewed machine. Read-only: Starsector was not launched.

| | |
|---|---|
| machine | Apple M5, 10-core GPU, MacBook Air |
| OS | macOS 26.5.1 (25F80) |
| driver | `2.1 Metal - 90.5` (legacy) / `4.1 Metal - 90.5` (core), vendor `Apple` |
| `GL_MAX_TEXTURE_SIZE` | 16384 |

The legacy profile is the one that governs: Starsector ships LWJGL 2
(`Contents/Resources/Java/lwjgl.jar`, `native/macosx/liblwjgl.jnilib`) and creates a
compatibility-profile context. Core profiles were probed only to separate "the driver cannot"
from "the old profile does not expose it".

## What the driver accepts

Uploads were performed and read back, not inferred from the extension string. `0x0500` is
`GL_INVALID_ENUM` — the driver does not implement that format.

| format | `glTexImage2D` (driver compresses) | `glCompressedTexImage2D` (pre-encoded) | resident vs `GL_RGBA` |
|---|---|---|---|
| BC1 `DXT1` | accepted | accepted | 8× smaller |
| BC1a `DXT1` alpha | accepted | accepted | 8× smaller |
| BC2 `DXT3` | accepted | accepted | 4× smaller |
| BC3 `DXT5` | accepted | accepted | 4× smaller |
| BC4 `RGTC1` | accepted | accepted | 8× smaller |
| BC5 `RGTC2` | accepted | accepted | 4× smaller |
| **BC7 `BPTC`** | **`0x0500`** | **`0x0500`** | **unavailable** |

Baseline confirmed: `internalformat=GL_RGBA` stores `0x8058` (`GL_RGBA8`), four bytes per pixel —
matching what `GpuTextureFootprint` models.

## Four findings

### 1. BC7 does not exist on this platform, and this corrects an earlier claim

[asset-quality-track.md](../asset-quality-track.md) previously said BC7 "has higher-precision
endpoints and per-block partitioning and would do better still". That is true of the format and
**not available here**. Apple's OpenGL tops out at 4.1; BPTC is core in 4.2 and Apple never shipped
the ARB extension. The M5 hardware supports BC7 through Metal — this is an API ceiling, not a
hardware limit — but nothing reachable from Starsector's GL context can use it.

Consequence: on macOS the format choice is **BC1/BC3, or nothing**. The measurement in
[the compression probe](../asset-quality-track.md#measured-what-block-compression-actually-costs-on-this-art-2026-07-25)
was made in exactly the format that is available, so its conclusion stands unweakened; what it loses
is the escape hatch of "BC7 would fix the small sprites". On macOS that hatch is closed, which makes
the selective policy load-bearing rather than a convenience.

### 2. Non-power-of-two textures upload natively

A 597×373 RGBA upload — the real case observed against the running engine in
[2026-07-22-prepared-pixel-npot-padding.md](2026-07-22-prepared-pixel-npot-padding.md) — is stored as
**597×373**, no error. `GL_ARB_texture_non_power_of_two` is present in the legacy profile.

So the loader's `get2Fold` padding to 1024×512 is **not required by this driver**. It is inherited
Slick2D behaviour written for hardware that stopped being current around 2004, and it costs
**1.86 GiB (27%)** of resident video memory on the reviewed ~70-mod profile for nothing.

This does not make removal easy. `TextureLoader` contains two independent power-of-two
implementations — a shared helper feeding `glTexImage2D`, and a second loop inlined in the buffer
builder that also sets the texture's declared width/height — and sprite texture coordinates are
computed against the padded size. Changing one without the other shears every sprite. What the probe
establishes is only that the *hardware and driver* are not the obstacle.

### 3. The engine's own upload site could compress with one changed constant

`TextureLoader` hardcodes `GL_RGBA` (`sipush 6408`) as the internal format. The probe shows the
driver honours a compressed internal format at the same call, with the same uncompressed pixel
pointer: `glTexImage2D(…, 0x83F3, …)` returns `GL_TEXTURE_COMPRESSED = 1` and a quarter of the
bytes. No new file format, no asset pipeline, no encoder, no cache — one constant at one call site,
and every texture in the game becomes 4× cheaper in video memory.

That is by a wide margin the smallest intervention with a large effect that this project has found.
Its cost is measured in finding 4.

### 4. The driver's encoder is about half as good as ours

Real core-game textures were dumped to raw ARGB, uploaded as BC3, read back with `glGetTexImage`,
and scored against the original with `TextureFidelity` — the same CIELAB ΔE metric used by the
compression probe — alongside `BlockCompressor` on identical input.

| texture | size | driver mean ΔE | driver p99 | ours mean ΔE | ours p99 |
|---|---|---|---|---|---|
| `planets/aurorae2.png` | 1024×1024 | 1.27 | 1.55 | **0.84** | 1.10 |
| `fx/slipstream_layer0.png` | 1024×512 | 2.26 | 4.40 | **1.20** | 3.15 |
| `terrain/deep_hyperspace.png` | 512×512 | 2.49 | 4.70 | **1.28** | 2.80 |
| `planets/bread.png` | 1024×512 | 2.37 | 6.50 | **1.55** | 4.65 |
| `misc/characterSheet00.png` | 1536×1536 | 4.19 | 21.50 | **2.39** | 12.45 |
| `ships/onslaught/onslaught_base.png` | 288×384 | 6.80 | 24.00 | **3.83** | 11.65 |

`BlockCompressor` wins on every texture, by 1.6–2.0× on the mean. Apple's driver encoder is a
realtime path optimised for throughput; ours does prioritised endpoint fitting and hill-climbs on
the quantised 5:6:5 grid. The gap straddles the perceptibility threshold where it matters: on
`aurorae2` the driver lands at 1.27 (visible on close inspection) and ours at 0.84 (imperceptible).

These numbers also independently reproduce the compression probe's central finding on a completely
different sample — core game art rather than the mod profile. Large and smooth round-trips near or
below the threshold; the 288×384 Onslaught hull, the exact sprite Dark.Revenant used in his 2019
VRAM arithmetic, is the worst result in the table at mean ΔE 3.83.

## The trade this sets up

Two viable paths, and the probe prices both:

| | one changed constant | offline encoder + `glCompressedTexImage2D` |
|---|---|---|
| VRAM | 4× (BC3) / 8× (BC1) | same |
| quality | driver's encoder | ~1.7–2× better, and selectable per texture |
| selective policy | no — all or nothing | yes |
| load time | unchanged; still decodes PNG, then compresses | strictly less work: no decode at all |
| engine change | one constant, one site | upload call swapped, plus an asset format |

The second row is why the first path cannot simply be taken: applied globally at the driver's
quality, the small detailed sprites that measure worst get compressed too, and those are exactly the
ones the community objection was about. The selective policy the compression probe recommends
requires per-texture control, which requires the offline path.

The load-time row is the one worth dwelling on, because it is the project's actual subject. Today
each texture is read as PNG, zlib-inflated, decoded to RGBA, padded, and uploaded at 4 B/px. A
pre-encoded block texture is read and uploaded — **no decode stage at all**, at an eighth of the
bytes. That collapses the speed track and the footprint track into one change rather than trading
them off, which is not how this looked before the probe.

## Reproducing

```bash
./probe-kits/gpu-capability/run-gpu-capability-probe-macos.command
```

The fidelity comparison in finding 4 is not packaged; it was a scratch harness pairing
`glGetTexImage` readback with `TextureFidelity` and `BlockCompressor`. Promoting it to
`assets compression-probe --driver` would make the driver-versus-offline comparison repeatable on
any machine, and is worth doing before either path is chosen.
