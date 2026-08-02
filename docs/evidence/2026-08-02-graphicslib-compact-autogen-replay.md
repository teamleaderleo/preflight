# GraphicsLib compact auto-generation replay live diagnostic

**Date:** 2026-08-02  
**Install:** Starsector 0.98a-RC8, 89 enabled mods, GraphicsLib 1.12.1  
**Protocol:** direct launch, prepared pixels with coherent NPOT carriers, no JFR recording  
**Status:** warm implementation diagnostics; correctness and attribution evidence, not a benchmark claim

GraphicsLib's generated-normal cache avoids regenerating images when the enabled mod/version hash
matches, but startup still traversed every hull, fighter, weapon, shipsystem weapon, and MIRV twice.
That second traversal is not wholly redundant: the first pass must finish linking manual maps and
identical sprites before missing normals may be generated or loaded from cache.

The test patch preserves that ordering while changing the representation of the second pass:

1. the first traversal performs the original manual/identical-sprite linking;
2. only unresolved, auto-generation-eligible requests are retained, deduplicated by GraphicsLib's
   own texture-data key;
3. after linking completes, that compact request set is replayed through the original
   `mapSpriteToMNSWithAutoGen` method;
4. immutable traversal JSON results and misses are retained so fallback weapon/MIRV paths are not
   reparsed.

No texture, sprite, OpenGL, or Starsector settings work moved to another thread. Cache hash
invalidation, opt-out and override tags, force-load decisions, unload behavior, and generated file
paths are unchanged.

## Live result

| exact phase | AshLib-only repeat | JSON-only Graphics test | compact replay | replay vs starting point |
| --- | ---: | ---: | ---: | ---: |
| GraphicsLib callback | 8.503s | 7.520s | **5.465s** | **-3.038s** |
| AshLib callback | 2.343s | 2.471s | 1.999s | -0.344s |
| all mod callbacks | 13.531s | 12.647s | **9.896s** | **-3.635s** |
| `ResourceLoaderState.init` entry to exit | 49.923s | 48.137s | **44.953s** | **-4.970s** |
| game-log start to GraphicsLib preload | 54.236s | 52.811s | **49.415s** | **-4.821s** |

The table is an ordered warm sequence and contains background/audio variation, so the whole-run
deltas are diagnostic. The exact callback entry/exit measurements are the direct attribution.

## Runtime health

- wrapper outcome `COMPLETED`, launcher/effective exit `0`, no fatal lifecycle evidence;
- GraphicsLib reached its normal `VRAM after unload/preload: 450555 bytes` marker;
- prepared-pixel bridge: 21,671 hits, zero prepared-pixel fallbacks, zero dimension/NPOT/internal
  errors, and zero active or pending buffers at shutdown;
- no GraphicsLib errors, normal-map buffer failures, or shader-creation errors were introduced;
- exact original GraphicsLib JAR preserved as
  `Graphics.jar.preflight-original-83206401.backup` before installing the test build.

The installed source is exactly Bitbucket commit `2e715f4` for `TextureData.java`. The same change
was cherry-picked onto current upstream master and compiled as Java 17 against GraphicsLib 1.12.1,
LazyLib, LunaLib, and the installed Starsector API.
