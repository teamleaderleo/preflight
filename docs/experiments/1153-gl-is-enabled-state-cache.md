# Issue #1153: guarded GL11 `glIsEnabled` state cache

Fast Rendering avoids a class of asynchronous pipeline stalls by answering selected OpenGL state getters from client-side tracked state instead of synchronizing with the renderer. Its current `AttribTracker` exposes cached `glIsEnabled` values for six capabilities:

- `GL_STENCIL_TEST`
- `GL_ALPHA_TEST`
- `GL_TEXTURE_2D`
- `GL_BLEND`
- `GL_LIGHTING`
- `GL_SCISSOR_TEST`

This experiment starts with five unit-independent capabilities: stencil, alpha test, blend, lighting, and scissor test.

`GL_TEXTURE_2D` stays on the original native getter path. In OpenGL 2.1 its enable state belongs to the active texture image unit, so caching it safely requires tracking `GL13.glActiveTexture` plus the relevant texture attrib semantics. Fast Rendering can do that because its wider GL bridge already tracks active texture state. This narrow GL11-only candidate deliberately leaves that extra state out. If live telemetry later shows texture-enable queries dominate the remaining getter traffic, active-unit tracking can be evaluated as a separate extension.

## Exact target

Class:

```text
org/lwjgl/opengl/GL11
```

Required methods:

```text
glIsEnabled(I)Z
glEnable(I)V
glDisable(I)V
glPushAttrib(I)V
glPopAttrib()V
glNewList(II)V
glEndList()V
glCallList(I)V
```

The live runner derives `GL11.class` SHA-256 from the installed `lwjgl.jar` and pins the target to Starsector's reviewed archive:

```text
contents/resources/java/lwjgl.jar
SHA-256 527d509f60132e5b2653c7fc0f8cf299d6f698f4a8013342bef47705dc57ed3f
```

## Cache contract

Property:

```text
-Dpreflight.glIsEnabledCache=true
-Dpreflight.glIsEnabledCache.report=<path>
```

The cache begins every context with unknown values.

For one of the five tracked capabilities:

1. an unknown `glIsEnabled` call runs the original LWJGL/native getter;
2. the returned value seeds the client-side cache;
3. later `glEnable` / `glDisable` calls update that known value after the original LWJGL setter returns;
4. a known `glIsEnabled` returns directly from the Java cache.

Every other capability, including `GL_TEXTURE_2D`, always takes the original getter.

### Context changes

Each cache access is keyed by the identity of LWJGL's current `ContextCapabilities` object. A token change discards all known values and the attrib stack before another cached answer is allowed.

### Attribute stack

`glPushAttrib` snapshots the tracked values. `glPopAttrib` restores only the capabilities covered by the pushed mask, following the relevant mask ownership used by FR's `AttribState`:

- `GL_ENABLE_BIT`: all five tracked values;
- `GL_COLOR_BUFFER_BIT`: alpha test + blend;
- `GL_STENCIL_BUFFER_BIT`: stencil test;
- `GL_LIGHTING_BIT`: lighting;
- `GL_SCISSOR_BIT`: scissor test.

An underflow invalidates all tracked values and falls back to native getters.

### Display lists

Display-list compilation can record setters without applying them immediately. The cache therefore invalidates at `glNewList`, suppresses setter certainty while a list is being compiled, invalidates again at `glEndList`, and invalidates after `glCallList` / `glCallLists` execution. The next getter repopulates state from the original native path.

This intentionally trades some hit rate for a tight correctness boundary.

## Causal telemetry

The report includes:

- total `queries`, `hits`, `misses`, `hitPercent`;
- `nativeSeeds` and `unsupportedQueries`;
- `texture2DQueries` separately from `otherUnsupportedQueries`;
- enable/disable updates;
- context changes;
- attrib pushes/pops/underflows;
- display-list compiles/calls;
- invalidation count;
- per-capability queries/hits/native seeds and hit rate for the five cached capabilities.

`texture2DQueries` is the direct evidence gate for any later active-texture-aware extension. A high count says that work may be useful; a low count closes that branch cheaply.

The important live sanity signal is a high cache hit rate with zero visual regressions. A low hit rate means the compatibility guards erase the expected win and the candidate should be dropped.

## Gameplay A/B

Run matching baseline/candidate pairs:

```bash
bash scripts/run-1153-gl-state-cache-pilot.sh \
  --variant baseline \
  --route ordinary \
  --workload-id <same-id>

bash scripts/run-1153-gl-state-cache-pilot.sh \
  --variant candidate \
  --route ordinary \
  --workload-id <same-id>
```

Repeat on `symmetric-1040` only after the ordinary route is visually correct and the candidate report confirms useful cache hits.

Retention requires a measurable p95/p99/1%-low or severe-frame improvement, useful cache coverage, and unchanged rendering across combat, UI overlays that use scissor state, shaders/GraphicsLib, and any display-list-heavy content in the workload.
