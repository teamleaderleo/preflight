# The encoder writes bytes now, and the driver agrees with them (2026-07-26)

Until this point `BlockCompressor` could say what a BC1 block *would* decode to, but could not
produce the block. Every fidelity number preflight has published about block compression — the
median mean ΔE of 0.80 on large smooth art, the 1.6–2.0× advantage over the driver's own encoder,
the whole [compression probe](../asset-quality-track.md) result — came from a function that took
pixels and returned pixels, with the eight bytes that a GPU actually reads never existing.

A block cache needs those bytes. Writing them turned out to be where the interesting failure lived.

## BC1's endpoint order is a mode bit, and nothing upstream was setting it

A BC1 block stores two RGB565 colours and a two-bit index per pixel. The palette is those two colours
plus two blends — *if* `code0 > code1`. If `code0 <= code1`, the hardware switches modes: entry 2
becomes the midpoint rather than the ⅔ blend, and **entry 3 becomes transparent black**.

Nothing in the encoder had any reason to care which endpoint came first. Endpoints come out of
cluster fit oriented along the principal axis, and the axis is produced by power iteration, whose
sign is arbitrary. So the order was effectively a coin flip per block.

Measured on a 256×256 smooth field with mild noise — the large-texture case that dominates VRAM:

| | mean ΔE | worst pixel |
|---|---|---|
| endpoints ordered | 1.69 | 7.2 |
| order left to the fit (2009 of 4096 blocks flipped) | **18.44** | **154.9** |

This is not a subtle quality regression. It is half the blocks decoding to a different palette with a
transparent entry in it. It would have shipped, because nothing that existed at the time could see
it: the software round-trip never formed a block, so it never had an order to get wrong.

Ordering the codes costs nothing. Swapping the two endpoints exchanges palette entries 2 and 3, so
doing it before indices are assigned leaves the reconstruction identical — every existing fidelity
test passes unchanged, with the same numbers. The bug was never in the measurements; it was in the
serialisation those measurements had not yet reached.

## Checking against the authority instead of ourselves

Fixing this by inspection would have replaced one unverified assumption with another. Comparing
`encode` against `decode` proves nothing either — a layout can be wrong in a way both halves agree
on.

So `probe-kits/gpu-capability/BlockUploadProbe.java` hands real encoded blocks to
`glCompressedTexImage2D`, reads them back decompressed with `glGetTexImage`, and compares against the
software decoder pixel by pixel. The driver is the authority; preflight's decoder is the thing on
trial. The test image deliberately mixes a gradient, per-pixel noise, hard magenta edges and an alpha
ramp, because flat blocks survive almost any misreading.

Apple M5, OpenGL 2.1 Metal 90.5, on the game's own x86_64 JVM under Rosetta:

| format | pixels | exact | mean deviation | worst channel |
|---|---|---|---|---|
| BC1 (DXT1) | 65,536 | **100.00%** | 0.000 | 0 |
| BC3 (DXT5) | 65,536 | **100.00%** | 0.000 | 0 |

Bit-exact, both formats, every pixel.

## The two halves of a BC3 block round differently

The BC3 result was not exact on the first run. Colour matched perfectly; **alpha was low by exactly 1
on exactly 50% of pixels** — the unmistakable signature of truncation against rounding, and a useful
reminder that a clean 50/50 split is a fact about arithmetic, not noise.

The S3TC specification defines the interpolated entries as weighted averages without pinning the
rounding, so this is permitted variance rather than a driver bug. Switching the alpha levels from
`(6a₀ + a₁)/7` to `(6a₀ + a₁ + 3)/7` took BC3 to bit-exact.

What makes this worth recording is the asymmetry. On the same driver, in the same block:

- the **colour** blends truncate — `(2c₀ + c₁)/3`
- the **alpha** blends round — `(6a₀ + a₁ + 3)/7`

There is no principle from which to derive that. Applying rounding to both, which is the obvious
tidy-minded thing to do, would have broken the BC1 agreement that was already exact. It had to be
measured, and it is now pinned by a unit test that checks a case where the two roundings disagree.

This is small — at most 1/255 of alpha — but it is not cosmetic. The encoder chooses each pixel's
alpha index by comparing against these levels, so a level table that is off by one from the hardware
means the encoder is optimising against a reconstruction that will not happen.

## What this changes

- `BlockCompressor.encode` and `.decode` exist, and `roundTrip` is now defined as their composition
  rather than a parallel calculation. Published fidelity numbers therefore describe the file, not
  the palette the encoder had in mind.
- The decoder models the hardware rather than this encoder, punch-through mode included. It has to:
  a decoder that quietly ignored the mode BC1 uses to signal transparency would have hidden the very
  bug above.
- The block-cache pipeline priced in
  [the load decomposition](2026-07-26-texture-load-pipeline-decomposition.md) now has a verified
  encoder underneath it. That was the largest piece of unverified machinery in the 61–74× estimate.

## Checking drivers nobody is sitting at

The result above is one driver, and the rounding quirk is exactly the kind of thing that varies by
vendor. The obvious next move — check NVIDIA — runs into a practical wall: `BlockUploadProbe` needs
LWJGL, LWJGL's `Pbuffer` needs a window system, and a rented GPU is headless.

So the check now also exists in a split form, which removes the constraint rather than working around
it:

- `BlockConformanceVector.java` writes a deterministic vector — the encoded blocks, plus the pixels
  preflight's decoder claims they mean — using **no GPU**.
- `block-conformance-probe.c` reads that vector on a GPU using **no JVM**, through CGL on macOS and
  EGL's device platform on Linux. Neither needs a display.
- `modal-block-conformance.py` runs the second half on rented NVIDIA hardware for a fraction of a
  cent.

Validated end to end on macOS before any of it is pointed at rented hardware: the C probe reaches the
same bit-exact verdict as the in-process Java probe, by an entirely independent route. Two
implementations, one conclusion, which is a stronger statement than either alone.

The secondary benefit may matter more than the rented-GPU one. Checking this used to require a JDK, a
Maven build and a Starsector installation. It now requires 622 KB and one small binary, which is
something a Windows player with a GeForce can actually be asked to run.

## First hosted run: a third implementation, and a lesson about harnesses

The first Modal run reached a healthy Tesla T4 — `nvidia-smi` reported it — and rendered on
**`Mesa llvmpipe`**, the CPU rasteriser. `NVIDIA_DRIVER_CAPABILITIES=all` was not enough: libglvnd
finds drivers by reading ICD manifests from `/usr/share/glvnd/egl_vendor.d/`, NVIDIA's manifest
normally arrives with the driver installer, and Modal injects driver *libraries* into an image that
never ran that installer. So the only device EGL could enumerate was Mesa's software one, and
`eglQueryDevicesEXT` returned it as device 0 of 1.

**The harness then reported it as an NVIDIA result**, because it treated exit status 0 as proof the
GPU had been used. That is a worse defect than the configuration problem: a misconfiguration produces
no data, whereas a harness that launders software output into a hardware claim produces *wrong* data
that looks exactly like the real thing. Fixed in three places — the probe classifies the renderer and
prints `preflight-renderer-class:`, returns a distinct status 3 for "ran, but on a CPU", and the
harness distinguishes that outcome instead of collapsing it into success. The device search now also
prefers a device advertising `EGL_NV_device_cuda` or `EGL_EXT_device_drm` rather than taking the first
one enumerated.

The result itself is worth keeping, correctly labelled. Mesa 23.2.1's software S3TC decoder is a third
independent implementation, and it does **not** agree with Apple:

| implementation | exact | mean dev | worst dev |
|---|---|---|---|
| Apple M5 (Metal) | 100.00% | 0.000 | 0 |
| Mesa llvmpipe 23.2.1 (CPU) | 91.37% | 0.086 | 1 |

Every difference is exactly 1, on 8.63% of pixels, and BC1 and BC3 differ *identically* — which
locates it precisely. The shared component is the colour block, so **Mesa's colour blends round where
Apple's truncate**, while its alpha blends agree with ours. The rounding question is therefore not
hypothetical: two implementations already disagree, and they disagree in the half of the block where
Apple looked like the odd one out.

This does not change the encoder. A worst-case deviation of 1/255 is far below the ΔE thresholds any
of this is judged against, and the layout is confirmed correct by a second independent decoder. What
it does is settle that a level table cannot be assumed portable — which is exactly what the NVIDIA run
was meant to test, and still has not.

## NVIDIA, measured: three implementations, three roundings

With the ICD manifest in place, the second hosted run reached the GPU — `Tesla T4/PCIe/SSE2`, NVIDIA
580.95.05, OpenGL 4.6, `preflight-renderer-class: hardware`.

| implementation | exact | mean dev | worst dev |
|---|---|---|---|
| Apple M5 (Metal) | 100.00% | 0.000 | 0 |
| Mesa llvmpipe 23.2.1 (CPU) | 91.37% | 0.086 | 1 |
| **NVIDIA Tesla T4 (580.95.05)** | **45.31%** | **0.547** | **1** |

Every deviation is exactly 1, and in all three cases BC1 and BC3 disagree *identically* — which
pins the cause to the colour block, the component the two formats share. The alpha blends agree
everywhere. So the picture is now complete and it is the awkward one: **three independent
implementations round the colour blends three different ways**, and Apple — the one preflight
matched exactly — is not the majority.

This settles the portability question rather than leaving it open. There is no level table that
matches every driver, so chasing per-driver tables would mean shipping a different encoder per
machine to fix a disagreement of one part in 255.

### So price it, rather than arguing about it

The right response depends entirely on how big 1/255 is perceptually, which is a measurable
question. Decoding the same blocks with the interpolated entries one level lower — a faithful model
of the NVIDIA disagreement, and it reproduces the observed rate closely at 53.60% of pixels against
NVIDIA's 54.69%:

| quantity | value |
|---|---|
| the disagreement itself | mean ΔE **0.206**, max ΔE **0.439** |
| fidelity under preflight's rules | mean ΔE 3.1291 |
| fidelity under the shifted rules | mean ΔE 3.1428 |
| just-noticeable threshold | 1.00 |

**The worst pixel of the worst case is less than half the just-noticeable threshold**, and the effect
on measured fidelity is +0.4% of the mean with the maximum unchanged. The disagreement is real,
reproducible, and perceptually irrelevant.

**Decision: do nothing.** Keep the single level table matched to Apple. It is bit-exact on one
driver and within half a JND on the others, and the published ΔE figures are accurate to within 0.4%
of themselves on NVIDIA hardware. What would have been wrong is either of the two tempting
alternatives — assuming portability without checking, or building per-driver tables to chase an
invisible difference.

The layout claim, which is what actually matters for the block cache, is now confirmed by **three
independent decoders on two vendors' silicon plus a CPU implementation**. That is a much stronger
position than the single-driver result this document started with.

## Caveats

- **One driver.** Apple M5 via Metal. The rounding behaviour is explicitly permitted to vary, so
  another vendor may need a different level table — the probes above are how to find out. AMD and
  NVIDIA results are wanted, and a *mismatch* would be the more useful outcome: it would mean the
  level tables are Apple-specific and the block cache needs a per-driver decision rather than a
  constant.
- **AMD is still unmeasured**, and is the remaining vendor with meaningful Starsector share. The
  probe kit runs there unchanged; nobody has run it.
- **One NVIDIA GPU, one driver branch.** A Tesla T4 is Turing silicon on a datacentre driver. The
  fixed-function block decoder is not something NVIDIA varies across a driver branch, but a GeForce
  result would still be worth having, and is now something a player can produce in one command.
- **Mesa's result is a CPU result.** A genuine third implementation, but llvmpipe is not a GPU, and
  it is recorded as a software data point rather than as evidence about anyone's silicon.
- The perceptual pricing above models the NVIDIA disagreement as "both interpolated entries one
  level lower". That reproduces the observed rate closely (53.60% against 54.69%) but is a model of
  the effect, not a transcription of NVIDIA's actual arithmetic, which is not documented.
- Bit-exactness is checked on one 256×256 image. It is a deliberately adversarial one, but it is not
  the full corpus; the claim is that the layout is right, not that every possible block has been
  enumerated.
- BC2, BC4 and BC5 are accepted by this driver but preflight does not encode them, so they are
  untested here.
- Still under Rosetta ([why](2026-07-25-macos-rosetta-runtime.md)), which is irrelevant to
  correctness and relevant only to the encode throughput quoted elsewhere.
