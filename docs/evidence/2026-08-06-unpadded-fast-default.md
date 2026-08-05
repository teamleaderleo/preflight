# True-size textures become the fast default

**Date:** 2026-08-06  
**Install:** Starsector 0.98a-RC8, current 89-mod profile, macOS, M5 MacBook Air  
**Run:** `fast-unpadded-default-clean-20260806-032435`  
**Result:** correctness and resource win accepted; no wall-time win claimed from one launch

`--prepared-unpadded` had already passed a full real load after the allocation/buffer transforms
were composed. This change promotes that accepted path from an explicit option to the `--fast`
preset. The older `--prepared-npot` path remains available as the padded conservative alternative.

The first launch through the changed preset reached the menu and shut down through the probe's
SIGTERM cleanup path:

| counter | result |
| --- | ---: |
| prepared hits | 15,469 |
| source pixel bytes | 2,116,422,119 |
| upload bytes supplied | 2,116,422,119 |
| padded uploads | **0** |
| padding bytes | **0** |
| peak direct bytes | 16,777,216 |
| NPOT / dimension / internal fallbacks | **0 / 0 / 0** |
| active / pending buffers at shutdown | **0 / 0** |
| reviewed transforms | 40, no failures |

The immediately preceding padded clean gate supplied 3,065,798,640 upload bytes for the same
2,116,422,119 source bytes. The default therefore avoids **949,376,521 bytes** of zero padding per
launch on this texture set. It also reduces resident texture allocations by the same amount.

The observed start-to-menu time was 23.75 seconds. That is slower than the current 21.61-second
fastest clean observation, so this document makes no launch-time claim. The byte and counter results
do not depend on timing noise.

The future-update behavior remains fail-closed. The property alone cannot enable true-size uploads:
`TexturePaddingRuntime.enabled()` additionally requires the exact fold-bypass transform to have
installed successfully. If the loader changes and the transform declines, the allocation remains
padded and prepared pixels follow their existing safe path rather than supplying a mismatched
buffer.
