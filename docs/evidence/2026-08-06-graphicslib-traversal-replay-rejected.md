# GraphicsLib public-call traversal replay was rejected

**Date:** 2026-08-06
**Install:** Starsector 0.98a-RC8, GraphicsLib 1.12.1, current 83-mod profile
**Runs:** `graphicslib-traversal-learn-20260806-044556`,
`graphicslib-traversal-replay-20260806-044654`

An exact-profile experiment recorded the ordered arguments to GraphicsLib's public
`mapSpriteToMNSWithAutoGen` method and the traversal-owned auxiliary state. The artifact was
bounded, checksummed, atomic, and restored the exact string maps, string sets, flags, counters, and
47,339 mapping invocations. Replay used GraphicsLib's own public mapping method so it would not
serialize or reconstruct private `TextureEntry` objects.

The learning launch reached the main menu cleanly in 19.80s. GraphicsLib took 1.03s; its ordinary
traversal took 0.76s, and the existing generated-normal journal hit all 6,184 paths with no fallback.
The 4.19MiB artifact was written once.

The adjacent replay launch also reached the main menu cleanly, but took 19.55s and made GraphicsLib
materially slower:

- GraphicsLib callback: 1.03s to 1.70s;
- replay: 1.42s for 47,339 calls;
- generated-normal path: 0.25s to 1.20s;
- generated-normal lookups: 6,184 exact journal hits plus 146 ordinary fallbacks.

The reflection boundary destroyed the original tight-loop behavior, while the whole-run time was
within ordinary launch noise and showed no attributable gain. The implementation was deleted and
its sole artifact moved to Trash. The 146 ordinary fallbacks had also written exactly 146 generated
normal PNGs at 04:47; that exact batch was moved to a separate recoverable Trash folder, restoring
the established texture-profile fingerprint. Do not retry a public-call replay. A future attempt would need a compact
representation of GraphicsLib's final private state or a direct in-class loop, with exact
equivalence proof; the remaining theoretical saving from the current traversal is under one second.
