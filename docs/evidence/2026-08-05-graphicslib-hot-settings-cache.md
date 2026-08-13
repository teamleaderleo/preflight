# GraphicsLib rereads three Luna settings in its light renderer

**Date:** 2026-08-05

**Install:** Starsector 0.98a-RC8, GraphicsLib 1.12.1, current mod profile

**Status:** exact adapter and installed-archive gates pass. The first live pilot matched the target
but retained the original because the plan-availability registry entry was missing; that fail-closed
plumbing issue was fixed and covered by regression test. The second live combat pilot passed.

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

The first live combat session exited normally with 29 other transformations and zero contained
failure, but reported `installed=false`. Its exact target evaluation was clean; diagnostics said the
plan was unavailable for the session. The transformation registry had the implementation route but
not the separate `hasPlan` route used before transformation. Adding it plus a target-level assertion
closes that plumbing miss.

The second live pilot `graphicslib-audio-v2-20260805-041804` exited normally with ACTIVE health, 31
transformations, zero fallback, and zero contained failure. The hot-settings cache installed and
served **7,621 hits** after **3 misses** with **1 invalidation**. Three misses after one boundary are
the expected one refresh per cached field.

The prior failed-closed pilot sampled `GraphicsLibSettings.fighterBrightnessScale` and its
`LunaSettings.getFloat` below `LightShader` once in 912 combat samples. The live cached pilot sampled
neither `fighterBrightnessScale` nor any LunaSettings method in 776 combat samples, while
`LightShader.renderInWorldCoords` remained active on 28 samples. This corroborates removal of the
lookup stack. The battles were not identical, so neither LightShader share nor frame-time differences
are a controlled A/B.

## Separate audio observation

The operator heard a short audio pop at both process startup and shutdown. The first pilot then
captured a real, recoverable `AL_INVALID_VALUE` during initial streaming-player creation. Exact
bytecode proves vanilla checks a stale pre-generation error rather than the result of
`alGenSources`; the separately gated repair is documented in
`2026-08-05-openal-stream-source-stale-error.md`. This predates and is independent of the settings
cache, which was not installed in the first run; both exact adapters passed together in the second.
