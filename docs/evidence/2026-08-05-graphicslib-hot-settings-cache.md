# GraphicsLib rereads three Luna settings in its light renderer

**Date:** 2026-08-05

**Install:** Starsector 0.98a-RC8, GraphicsLib 1.12.1, current mod profile

**Status:** exact adapter and installed-archive gates pass; live combat pilot pending.

## Runtime lead

The mixed campaign/combat recording
`~/.starsector-preflight/runs/commodity-event-entry-v3-20260805-035020/startup.jfr`
contains 1,010 combat game-thread samples. GraphicsLib's `LightShader` is present on 48 samples
(4.75%). Inspection of the exact installed bytecode found that `renderInWorldCoords` calls these
three getters on every render:

- `GraphicsLibSettings.fighterBrightnessScale()`;
- `GraphicsLibSettings.weaponFlashHeight()`;
- `GraphicsLibSettings.weaponLightHeight()`.

Each getter calls `LunaSettings.getFloat("shaderLib", ...)`, writes the result into GraphicsLib's
existing static field, and returns it. These values are settings, not frame state. GraphicsLib
already funnels initial loading and its Luna settings-change listener through `load()` and
`applyChanges()`.

## Exact cache boundary

`graphicslib-hot-settings-cache-v1` transforms only the exact reviewed
`org.dark.shaders.util.GraphicsLibSettings` class from the exact GraphicsLib 1.12.1 archive and mod
URL classloader. It retains each original getter, adds a validity bit for each existing float field,
and returns the cached field after the first read. Entering either `load()` or `applyChanges()`
invalidates all three bits, so the next caller observes the newly loaded Luna value.

The transform additionally reviews the getter bodies before changing them: each must contain exactly
one Luna `getFloat` call, one write and one read of its matching field, and the reviewed two-return
shape. Class, archive, JVM bytecode version, loader, method, or instruction drift retains the
original bytes. The shutdown report records installation, hits, misses, and invalidations.

## Verification before launch

- the executable woven fixture proves one Luna read, a cache hit after the backing value changes,
  and refreshed values after both `applyChanges()` and `load()`;
- changed hashes, changed getter bodies, and a second rewrite fail closed;
- the installed-archive integration test transforms the real GraphicsLib class and confirms all
  three original getters plus both invalidation hooks;
- full `mvn verify`, including the exact Starsector and GraphicsLib archives, passes.

The remaining gate is a live combat session. It must show the exact transformation applied, nonzero
hits, plausible misses relative to invalidations, normal live setting behavior, and no adapter
failure. JFR can then establish whether the Luna lookup stack disappeared; frame-time differences
from a non-identical battle remain directional rather than a controlled A/B.

## Separate audio observation

The operator heard a short audio pop at both process startup and shutdown during the preceding
pilot. The retained log shows ordinary music-player creation, playback, and cleanup, with no OpenAL,
decoder, or device error. This is therefore an unresolved boundary transient, not evidence against
the settings cache, which had not yet been installed in that run.
