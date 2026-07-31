# Half an invariant kills the launcher (2026-07-31)

A `--prepared-unpadded` launch died before the operator could click anything:

```
Fatal: Number of remaining buffer elements is 668043, must be at least 1572864.
```

Both numbers name one texture. `graphics/ui/launcher_bg.jpg` is 597x373 RGB:
597 x 373 x 3 = **668,043** bytes at true size, and 1024 x 512 x 3 = **1,572,864**
padded up to the next power of two on each axis. The prepared path supplied the
true-size buffer. The `glTexImage2D` allocation still expected the padded one.

## This was the documented failure, not a new one

`TexturePaddingRuntime` states the invariant plainly: the loader computes padded
dimensions in two places, and removing the padding is safe only while **both** the
fold bypass is installed (so the allocation shrinks) and the prepared path is
serving (so the buffer does too). "Neither half is safe alone."

Only the second half was ever wired. `AdapterTargetRegistry.withTextureTarget`
installs the compatibility target or the prepared-pixel target and nothing else;
no code path anywhere constructs a target carrying `texture-padding-v1`. The
transformation registry knows how to apply `TexturePaddingPlan`, but nothing ever
asks it to. So the property could not have worked on any machine, in any profile.

It was not caught earlier because the failure needs three things at once, and the
first two were arranged only hours before: prepared-pixels reachable from
`--texture-auto`, non-power-of-two textures no longer declined, and the property
set. Until then every non-power-of-two texture fell back and the buffer sizes
never disagreed.

## Why the launcher and not the game

`graphics/ui/launcher_bg.jpg` is loaded by the launcher, which shares its JVM with
the game -- the same reason the adapter's own layout observations recorded
`launcher_bg.jpg`, `launch_button_bg.png` and `play_button0.png`. The first
non-power-of-two texture the process ever touches belongs to the launcher, so the
crash landed before the Play button existed.

## What changed

The invariant is now enforced rather than described. `TexturePaddingRuntime.enabled()`
returns true only when `TexturePaddingPlan.transform` has actually woven the fold
bypass, latched at the point the rewritten class bytes are produced. Every earlier
return in that method -- wrong class, no fold, a fold that is not a pure integer
fold, a class already rewritten -- leaves the gate shut, because each of those
leaves the allocation padded.

With nothing installing the target today, the property is inert: a
`--prepared-unpadded` run now behaves exactly like `--prepared-npot` instead of
killing the process.

## What is still owed

Composing the two plans. Both rewrite `com/fs/graphics/TextureLoader`, and the
transformation registry dispatches one plan per class, so this is not a target-list
change -- the padded-dimension bypass and the prepared-pixel conversion bypass have
to be applied to the same class together. Until that exists, the 1.86 GiB of
never-sampled padding stays on the table.

The conversion bypass does not depend on any of it and remains measurable through
`--prepared-npot`.
