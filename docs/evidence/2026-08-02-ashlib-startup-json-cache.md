# AshLib startup JSON cache live diagnostic

**Date:** 2026-08-02  
**Install:** Starsector 0.98a-RC8, 89 enabled mods, macOS 15  
**Protocol:** direct launch, prepared pixels with coherent NPOT carriers, no JFR recording  
**Status:** one warm live diagnostic; correctness and attribution evidence, not a benchmark claim

The exact post-100 phase probe attributed 9.778 seconds of the preceding prepared-pixel run to
`AshLibPlugin.onApplicationLoad()`. AshLib 2.2.3 repeatedly called `SettingsAPI.loadJSON()` for the
same hull and variant files while constructing `ShipRenderInfo`: once for dimensions, again for
built-in slots, again for the central module, and additional times for modular hulls.

A test build made two application-lifetime caches inside `ShipRenderInfo`:

- resolved ship JSON by hull id, preserving the existing skin/base-hull fallback;
- merged JSON by normalized path, shared by ship and variant reads.

No work was moved to another thread. Starsector settings and texture calls remain on the original
thread; the patch only removes duplicate parsing.

## Live result

| exact phase | preceding prepared run | AshLib cache run | diagnostic delta |
| --- | ---: | ---: | ---: |
| resource loop to exact 100% | 35.168s | 33.996s | -1.172s |
| AshLib callback | 9.778s | **2.712s** | **-7.066s** |
| GraphicsLib callback | 9.827s | 8.659s | -1.168s |
| all mod callbacks | 22.337s | 14.069s | -8.268s |
| `ResourceLoaderState.init` entry to exit | 59.375s | **49.777s** | **-9.598s** |
| game log start to GraphicsLib preload marker | not collected by that probe | **54.262s** | n/a |

The run used Preflight's new `--direct` path, so there was no launcher and no operator click. The
game's own log marker is the timing boundary used by the unattended benchmark harness.

## Runtime health

- wrapper outcome `COMPLETED`, launcher exit `0`, effective exit `0`;
- lifecycle inspection found no fatal evidence;
- prepared-pixel bridge: 21,668 hits, 21,668 conversions bypassed, zero prepared-pixel fallbacks,
  zero dimension/NPOT/internal errors;
- zero active or pending prepared buffers at shutdown;
- exact original AshLib JAR preserved as
  `ashlib.jar.preflight-original-634a0542.backup` before the test build was installed.

The run was deliberately stopped after the main-menu preload marker. It was ordered after other
live work and had no thermal cooldown, so the seconds are diagnostic. A shuffled cooled campaign is
still required before treating the 54.262-second result as a stable performance claim.
