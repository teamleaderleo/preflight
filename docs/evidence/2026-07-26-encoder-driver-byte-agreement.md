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

## Caveats

- **One driver.** Apple M5 via Metal. The rounding behaviour is explicitly permitted to vary, so
  another vendor may need a different level table — the probe is how to find out, and the JSON line
  in its report is the thing to share. AMD and NVIDIA results are wanted.
- Bit-exactness is checked on one 256×256 image. It is a deliberately adversarial one, but it is not
  the full corpus; the claim is that the layout is right, not that every possible block has been
  enumerated.
- BC2, BC4 and BC5 are accepted by this driver but preflight does not encode them, so they are
  untested here.
- Still under Rosetta ([why](2026-07-25-macos-rosetta-runtime.md)), which is irrelevant to
  correctness and relevant only to the encode throughput quoted elsewhere.
