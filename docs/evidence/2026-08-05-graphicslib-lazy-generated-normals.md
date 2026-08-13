# GraphicsLib generated normals no longer upload just to unload

Date: 2026-08-05

Profile: 83 enabled mods, GraphicsLib 1.12.1, macOS on Apple M5, bundled x86-64 Zulu 17
under Rosetta, `--fast`

Result: the exact cached-normal seam fell from 1.97 seconds of PNG decode/GPU upload to 1.13
seconds of integrity validation, with 6,184/6,184 validated lazy hits and zero fallback. The first
successful diagnostic launch reached the main menu in 27.23 seconds.

## Finding

The startup callback probe first localized GraphicsLib's remaining cost to one exact path:

- `ShaderModPlugin.onApplicationLoad`: 2.85 seconds.
- `autoGenMissingNormalMaps`: 2.50 seconds.
- compact replay: 2.12 seconds across 9,336 calls.
- `autoGenNormalMap`: 2.00 seconds across 6,184 calls.
- `SettingsAPI.loadTexture` for the generated-normal cache: 1.97 seconds across 6,184 calls.

Path-cardinality telemetry proved that the 6,184 calls named 6,184 distinct generated PNGs. The
source-sprite and normal-generation branches were never entered. An isolated resource-probe-cache
run served 341,100/341,102 resource probes without a filesystem syscall but left the 1.96-second
seam unchanged, proving that the remaining cost was texture decode/upload rather than resource-root
search.

Installed GraphicsLib bytecode showed the waste. With `preloadAllMaps=false`, each valid cached
normal is loaded into a sprite and uploaded, placed in a `TextureEntry`, and then immediately passed
to `unloadTexture` and `glDeleteTextures`; the entry is left with `sprite=null` and `loaded=false`.
GraphicsLib's existing `unloadAndPreloadTextures` later loads that same `spriteName` when an active
fleet actually needs it, and computes `vramSize` when it was left at zero.

## Change

The reviewed compact GraphicsLib replacement now has a second exact, SHA-pinned transform:

1. Only while GraphicsLib's own cache is current and `preloadAllMaps=false`, resolve the exact
   `shaderLib/cache/*_normal.png` file.
2. Require a contained, non-symlink regular file and validate the complete PNG signature, header,
   chunk lengths, IDAT/IEND structure, and every chunk CRC.
3. Return a marker implementing the game's actual `SpriteAPI`; it owns no GL texture.
4. Let GraphicsLib construct its ordinary entry, then recognize the marker at the exact immediate
   unload block and convert the entry to its native lazy state: null sprite, `loaded=false`, and
   zero VRAM size.
5. When any path, reflection, file, header, or CRC check fails, return null and execute the untouched
   original load/regenerate path.

The transform requires the exact embedded compact-replay SHA and pins its own output SHA. It also
requires the exact `invalidateCache`, cached-return, immediate-unload, and normal-map insertion
shapes. Any GraphicsLib update or bytecode drift disables the whole replacement rather than
partially applying it. `preloadAllMaps=true` always keeps the original eager path.

Unit tests cover valid marker creation, CRC corruption, missing files, path traversal, wrong cache
roots, exact dual-site bytecode injection, changed-class rejection, deterministic output, and the
existing compact-replay adapter contract. Full `mvn verify` passed before the live probe.

## Live diagnostic

Retained run:
`~/.starsector-preflight/runs/gfx-lazy-normal-v2-20260805-131843`

- game-log start to main menu: **27.23 seconds**;
- GraphicsLib callback: **2.17 seconds**;
- `autoGenMissingNormalMaps`: **1.71 seconds**;
- compact replay: **1.28 seconds**;
- `autoGenNormalMap`: **1.15 seconds**;
- validated lazy hits: **6,184/6,184**;
- validated bytes: **215,643,372**;
- validation time: **1,132ms**;
- fallback/root failure: **0/0**;
- wrapper outcome: `COMPLETED`, exit 0;
- adapter health: `ACTIVE`, 33 transformations, zero reported fallback.

No generated-normal load, generation, access, or classloading error appeared. The game was stopped
by the probe and no process survived. The 27.23-second whole-launch number is a diagnostic rather
than a cooled cohort; the exact 1.97s-to-1.13s seam movement is the attribution evidence.

An immediately following profiled gameplay pilot crashed in HotSpot itself at 8.70 seconds, during
projectile-spec loading and before GraphicsLib's application callback or any lazy-normal call. It
produced no adapter report and is excluded as a Rosetta/JVM launch failure, not counted as product
acceptance. That crash artifact changed the exact installation fingerprint, and the next attempt
correctly refused to reuse the stale prepared-texture index. `preflight prepare` rebuilt the current
profile in 9.7 seconds.

## Deferred-load acceptance

Retained run:
`~/.starsector-preflight/runs/gfx-lazy-normal-acceptance-v3-20260805-132355`

After the rebuild, a non-JFR pilot loaded a representative campaign, roamed, opened the combat
simulation with 500 visible opponent variants, entered/exited combat, returned to the campaign, and
quit normally. GraphicsLib executed four `unloadAndPreloadTextures` passes. Its reported VRAM rose
from 450,555 to 674,124 bytes during the simulation load, proving that the native deferred loader
actually loaded needed entries; later passes unloaded them normally.

The wrapper completed with exit 0, no native crash report, and `ACTIVE` adapter health: 50 exact
transformations, zero decline, and zero contained failure. Lazy-normal telemetry again reported
6,184/6,184 validated hits, 215,643,372 bytes checked in 1,139ms, and zero fallback/root failure.
No GraphicsLib texture-load, normal-generation, class-access, or fatal error appeared in the log.
