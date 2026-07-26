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

| method | calls | live? |
|---|---|---|
| `public Object o00000(Object, String, int, int, int, int, boolean)` | 4 | **yes — the main path** |
| `public Object o00000(BufferedImage, int, int, int, int)` | 2 | public entry, reachable |
| `private ByteBuffer Ò00000(String)` | 2 | **no — dead code** |

`Ò00000(String)` is a separate loading path entirely: it constructs a
`de.matthiasmann.twl.utils.PNGDecoder` over a `BufferedInputStream` and decodes straight to a
`ByteBuffer`, bypassing `BufferedImage`. It looked like a third thing any padding change would have
to cover.

**It is unreachable.** The method is `private`, and no instruction in the class references
`Ò00000:(Ljava/lang/String;)Ljava/nio/ByteBuffer;` — the only `Ò00000` call sites in the class carry
the descriptors `([FF)F`, `()I` and `(String,BufferedImage)Object`, which are different methods that
share an obfuscated name. A private method with no in-class caller cannot be invoked. Two of the
eight `get2Fold` calls are therefore in dead code, and the PNGDecoder path is not a concern for this
work at all.

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

## The route an ordinary texture takes

Traced statically from the public load-by-path entry point, so no launched game was needed:

```
public Object o00000(String)                     load by logical path
  ├─ HashMap lookup, return on hit
  ├─ if void.Õ00000() -> construct a deferred Object(3553, -1, path) and return
  └─ o00000(null, path, 3553, 6408, 9729, 9729, false)
         GL_TEXTURE_2D, GL_RGBA, GL_LINEAR, GL_LINEAR
         │
         ├─ Ô00000(String) -> BufferedImage                    the ImageIO decode
         ├─ o00000(BufferedImage, Object)  ← INLINED get2Fold ×2, sizes the upload ByteBuffer
         ├─ o00000(int) ×2  ← EXTRACTED get2Fold, sizes glTexSubImage2D
         └─ o00000(int) ×2  ← EXTRACTED get2Fold, sizes glTexImage2D
```

**Both implementations run on the same texture, inside the same call.** The inlined pair decides how
big the pixel buffer is; the extracted method decides how big the GL allocation is. They agree today
only because they implement identical arithmetic.

That is the coordination requirement, and it is sharper than "two edits": they are not two
independent sites that happen to both pad, they are two halves of one invariant. Change either alone
and the buffer and the allocation disagree about the same texture in the same frame.

## What this means for the edit

- **Neutering `o00000(int)` alone is not enough** and would be actively dangerous — the GL allocation
  would shrink to the true dimensions while the inlined pair kept handing it a padded buffer. That is
  exactly the `insufficient-original-buffer` failure the prepared-pixel work already hit once
  ([evidence](2026-07-22-prepared-pixel-npot-padding.md)), reached from the opposite direction.
- **The inlined edit is the load-bearing one and the harder one.** Replacing each loop with a direct
  `getWidth()`/`getHeight()` shrinks the code, so every branch offset after it moves.
- **The PNGDecoder path can be ignored**, which is one fewer thing than this document originally
  claimed. See above.
- **The synthetic stub models the right route.** It also calls a power-of-two function and feeds the
  two setters in the same order, so the drift recorded against it is arithmetic only, not structural.

None of this is attempted here. The purpose of this pass was to replace "two implementations
somewhere in the real jar" with an exact inventory and a traced call route, and that is now done.

## Still unestablished

- Whether the hardware needs any of it. The GPU capability probe already found
  `GL_ARB_texture_non_power_of_two` present on the M5
  ([evidence](2026-07-25-macos-gl-capability-probe.md)); `get2Fold` is inherited Slick2D behaviour
  rather than a requirement of the silicon. That is the premise of the whole lever and it is
  measured, but it has been measured on one GPU.
- Whether the offline installed-class contract checker from PR #119 can assert this inventory, which
  would keep it honest across game updates instead of being a one-time reading of one jar.
