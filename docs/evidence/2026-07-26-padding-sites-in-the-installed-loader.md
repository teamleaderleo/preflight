# Where the 1.86 GB of padding is actually computed (2026-07-26)

Padding removal has been the top lever on paper for a while and blocked in practice, with the
blocker recorded as "the real `TextureLoader` has two independent power-of-two implementations" and
"needs the real installation". The installation is now available, so this is the reconnaissance.

Source: `com/fs/graphics/TextureLoader.class` in
`/Applications/Starsector.app/Contents/Resources/Java/fs.common_obf.jar` — 10,709 bytes, dated
2025-04-18. Read with `javap -p -c`. Nothing was copied into the repository: `fs.common_obf.jar` is
not redistributable, and what follows is a description of its shape, not its bytes.

## Implementation one: the extracted method

```
private int o00000(int);
   0: iconst_2
   1: istore_2
   2: goto          9
   5: iload_2
   6: iconst_2
   7: imul
   8: istore_2
   9: iload_2
  10: iload_1
  11: if_icmplt     5
  14: iload_2
  15: ireturn
```

This is Slick's `get2Fold` exactly: seed at 2, double while `ret < target`, return. It **cannot
return less than 2**, which is why a one-pixel edge allocates two. `GpuTextureFootprint.uploadDimension`
and its `MINIMUM_UPLOAD_DIMENSION = 2` are confirmed correct against the shipped class.

It has **three** call sites, not one:

| method | calls |
|---|---|
| `public Object o00000(BufferedImage, int, int, int, int)` | 2 |
| `public Object o00000(Object, String, int, int, int, int, boolean)` | 4 |
| `private ByteBuffer Ò00000(String)` | 2 |

`Ò00000(String)` is a separate loading path entirely — it constructs a
`de.matthiasmann.twl.utils.PNGDecoder` over a `BufferedInputStream` and decodes straight to a
`ByteBuffer`, bypassing `BufferedImage`. Any padding change has to cover it, and it is not the path
the synthetic stub models.

## Implementation two: inlined, twice, in the conversion method

`private ByteBuffer o00000(BufferedImage, Object)` — the raster-walk path — does **not** call
`o00000(int)`. It opens with the same algorithm written out by hand, once per axis:

```
 2: iconst_2 / istore 6              width  = 2
 5: iconst_2 / istore 7              height = 2
11: iload 6; iconst_2; imul          width *= 2
17: iload 6; getWidth;  if_icmplt 11    while (width  < image.getWidth())
29: iload 7; iconst_2; imul          height *= 2
35: iload 7; getHeight; if_icmplt 29    while (height < image.getHeight())
44: aload_2; iload 7 -> Object.Ô00000(I)V      store padded height on the texture
50: aload_2; iload 6 -> Object.Ó00000(I)V      store padded width  on the texture
```

Two loops, no method call, feeding the texture's own width/height setters. This is the
implementation that decides what the upload buffer is sized to.

Incidentally this confirms the synthetic stub models the right *shape* — it also sets height then
width through the same two setters — while differing in the arithmetic, which is the drift already
recorded as an open question.

## What this means for the edit

The blocker was described as "two coordinated bytecode edits". The call graph is wider than that:
padding is computed in **four methods** through **two implementations**, across **two decode paths**
(`BufferedImage` and `PNGDecoder`) that share no code.

Consequences for any attempt:

- **Neutering `o00000(int)` alone is not enough** and would be actively dangerous. It would stop the
  three call sites padding while the inlined pair in the conversion method kept sizing the upload
  buffer to padded dimensions. The two would disagree about the same texture, which is exactly the
  `insufficient-original-buffer` failure the prepared-pixel work already hit once
  ([evidence](2026-07-22-prepared-pixel-npot-padding.md)).
- **The inlined edit is the load-bearing one**, and it is the harder one: replacing each loop with a
  direct `getWidth()`/`getHeight()` shrinks the code, so the branch offsets around it move.
- **`Ò00000(String)` must be checked separately**, because the PNGDecoder path is not modelled by the
  synthetic stub at all. Whether it is reached for ordinary mod art is not yet established.

None of this is attempted here. The purpose of this pass was to replace "two implementations
somewhere in the real jar" with an exact inventory, and that is now done.

## Still unestablished

- Which of the four methods actually carries the profile's 23,738 textures. The 1.86 GB figure comes
  from `GpuTextureFootprint`, which models the *rule*, not from observing which code path applied it.
- Whether the hardware needs any of it. The GPU capability probe already found
  `GL_ARB_texture_non_power_of_two` present on the M5
  ([evidence](2026-07-25-macos-gl-capability-probe.md)); `get2Fold` is inherited Slick2D behaviour
  rather than a requirement of the silicon. That is the premise of the whole lever and it is
  measured, but it has been measured on one GPU.
- Whether the offline installed-class contract checker from PR #119 can assert this inventory, which
  would keep it honest across game updates instead of being a one-time reading of one jar.
