# Padding removal does not need instruction surgery (2026-07-26)

The asset track has carried padding removal as "needs two coordinated bytecode edits", and the
[site inventory](2026-07-26-padding-sites-in-the-installed-loader.md) sharpened that into something
that looked worse: the two power-of-two implementations are two halves of one invariant, one of them
is *inlined* at the head of a large method, and replacing an inlined loop with a direct call shrinks
the code and moves every branch offset after it.

That framing assumed the edit has to happen where the padding is computed. It does not.

## The agent never edits instructions in place

`TexturePreparedPixelPlan` works by **renaming the original method and installing a wrapper** under
the original name. The wrapper consults the runtime and, on any doubt, calls the renamed original.
Nothing is patched; the original bytecode survives verbatim under a new name, which is what makes
fail-open structural rather than a policy someone has to remember.

The convert wrapper's shape:

```
isCarrier(image) ? ... : -> ordinary            not ours, delegate
prepare(image)   -> PreparedPixel or null
null             -> preparedFallback            declined, delegate
non-null         -> serve, and never touch the original converter
```

## Which means the inlined loops are not on the path

The inlined power-of-two pair lives inside the *original* conversion method. When the prepared path
serves a texture, that method is not called. **The inlined loops do not execute at all.**

So the inlined copy does not need to be edited, disabled, or even understood beyond knowing it is
skipped. It remains exactly as shipped, and it remains correct, because it only ever runs on the
fallback path where padding is still wanted.

This removes the hard half of the problem. What is left is not surgery.

## What is actually required

Two changes, both of them wrapper-or-runtime work:

1. **`TexturePreparedPixelRuntime.prepare` must stop declining NPOT.** Today it returns null when
   `layout.paddingBytes() > 0 && !carrier.coherentDirect`, so every non-power-of-two texture — which
   is most of the profile — falls through to the original padded path. Padding removal is, on this
   side, the removal of that decline plus supplying a buffer at the *true* dimensions rather than the
   padded ones.

2. **The extracted `o00000(I)I` must return its argument.** This is the half that is not yet wrapped.
   It sizes the `glTexImage2D` allocation, and if it keeps folding while the prepared path supplies
   an unpadded buffer, the allocation and the buffer disagree about the same texture — the
   `insufficient-original-buffer` failure, reached from the opposite direction. It is a two-argument
   method with a fifteen-instruction body, so the same rename-and-wrap pattern applies directly and
   the wrapper is trivial: consult the runtime, return the argument or delegate.

## Most of the machinery already exists

The runtime already has:

- **`expectedUploadDimension`** — its own `get2Fold`, used to predict the layout.
- **`UploadLayout`** with `uploadWidth`, `uploadHeight`, `uploadBytes` and `paddingBytes`, so the
  quantity being removed is already computed per texture.
- **`coherentDirect`** — a working path that *already serves an NPOT texture directly*, replaying the
  dimension setters onto the texture object, gated behind a system property as a diagnostic.

That last one matters more than it looks. The hard part of serving NPOT — getting a direct buffer to
the upload site and getting the texture object's dimensions set consistently — is built and has been
exercised. Padding removal differs from it in *what dimensions are used*, not in mechanism.

## The remaining risk is not where it looked

Not offsets or verification. Two things:

- **The capability gate.** Removing padding is only safe where the driver supports
  `GL_ARB_texture_non_power_of_two`. Confirmed present on the M5
  ([evidence](2026-07-25-macos-gl-capability-probe.md)) and that is one GPU. This has to be a runtime
  capability check with a fail-open default, not a build-time decision.
- ~~Everything downstream that assumes padded dimensions.~~ **Checked, and it is benign — with one
  condition.** See below.

### The texture ratio is derived, not assumed

`com/fs/graphics/Object` stores source and padded dimensions separately, plus two floats. The
dimension setters the converter calls are not plain setters: each one recomputes a ratio.

```
public void Ô00000(int):          public void Ó00000(int):
  this.paddedHeight = arg           this.paddedWidth = arg
  õ00000()                          OO0000()

private void õ00000():            private void OO0000():
  if (paddedHeight != 0)            if (paddedWidth != 0)
    ratioV = (float) sourceHeight     ratioU = (float) sourceWidth
           / (float) paddedHeight            / (float) paddedWidth
```

Those two floats are exposed through public getters and are what callers use to map texture
coordinates over a padded allocation.

This is the best possible shape for this change. The ratio is **computed from the two dimensions**
rather than baked in anywhere, so when padding is removed and padded equals source, both ratios
become exactly `1.0` by construction. Every consumer of the getters follows along without being
touched, and no call-site search is needed, because there is nothing site-specific to find.

**The condition.** The ratio is only recomputed *when a setter is called*. The convert wrapper
currently skips the dimension replay unless `coherentDirect` is set, so a prepared path that serves
unpadded pixels without calling the setters would leave both ratios at whatever they were — the one
way this change could corrupt rendering while every dimension in the report looked right.

So the transform must call both setters with the true dimensions on the serving path. The wrapper
already has `addDimensionSetter` for exactly this; the requirement is that it stops being conditional
on the diagnostic property. That is now a stated precondition rather than something to discover in a
launched game.

## Built (2026-07-26)

Both halves landed the same day this was written, and both are inert.

- **#203** — `TexturePaddingPlan` renames the extracted fold and wraps it. The shape gate establishes
  purity rather than identity, for the reason argued there: a method that folded differently would
  still be safe to bypass, one that mutated state would not.
- **#204** — `prepare()` serves NPOT textures at true size and reports source dimensions, and the
  plan's dimension replay widened to cover the unpadded path. The stale-ratio failure predicted below
  is now a test that fails under mutation with `expected: <3> but was: <4>`.

Three independent things keep it from affecting a launch: the gate defaults off, no target declares
the plan, and neither half does anything useful without the other.

**What is still unproven** is everything this document could not settle by reading: that a real
driver accepts the unpadded upload, that nothing in the rendering path depended on padded allocations
in a way static analysis missed, and that the capability holds on hardware other than one M5. Those
need a launch and a second GPU, in that order.

## What this changes about the plan

| was | is |
|---|---|
| edit an inlined loop, fix branch offsets | leave it untouched; it is skipped |
| two coordinated instruction edits | one wrapper, one runtime decline rule |
| validate by launching | most of it provable against the fixture, which now models both implementations |

The fixture work in #201 was the prerequisite for the second row: the invariant these two changes must
preserve is now something a test can express, and mutating either half fails the build.
