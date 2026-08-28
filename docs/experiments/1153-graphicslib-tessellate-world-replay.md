# Issue #1153: GraphicsLib repeated world-transform replay

This is an incremental experiment on top of the array and primitive-packed GraphicsLib tessellation candidates.

## Why this exists

GraphicsLib 1.12.1 has multiple stencil/light paths that call `Tessellate.render(...)` for ship bounds. Reviewed public source shows one callsite in `ShaderLib` and two callsites in `LightShader`.

Candidate 2b already caches local tessellated X/Y coordinates, but every call still applies the same ship-facing rotation and world-position translation to every vertex. During multiple rendering passes in one frame, the same `TessData`, facing, and location can therefore repeat that CPU loop.

## Candidate 2c

Property:

```text
-Dpreflight.graphicsLibTessellateWorldReplay=true
```

It only activates when both earlier switches are active:

```text
-Dpreflight.graphicsLibTessellateArray=true
-Dpreflight.graphicsLibTessellatePackedReplay=true
```

Each weakly keyed packed `TessData` entry can lazily retain one primitive world-space float array plus the exact float bit patterns of:

- cosine;
- sine;
- ship X;
- ship Y.

If all four inputs match the previous call for that `TessData`, the runtime bulk-copies the retained world vertices into the existing direct vertex buffer and skips the per-vertex transform loop. A single bit change in facing/location inputs recomputes the world array before drawing.

The cache therefore cannot carry coordinates across ship movement or rotation. `TessData` lifetime still follows GraphicsLib's own weak tessellation cache.

When this switch is disabled, Candidate 2b keeps its existing thread-local world scratch behavior; this makes the incremental A/B attributable and avoids paying per-entry world-array memory in the baseline.

## Telemetry

The GraphicsLib candidate report adds:

- `worldReplayEnabled`;
- `worldReplayHits`;
- `worldReplayMisses`;
- `worldReplayFloatsAvoided`;
- `worldCacheAllocations`;
- `worldCacheFloatsAllocated`.

A useful result needs a meaningful hit count. If GraphicsLib's passes do not revisit the same ship transform often enough, this branch should be removed even if it is semantically correct.

## A/B

Run the ordinary route first:

```bash
scripts/run-1153-render-pilot.sh \
  --experiment tess-world \
  --variant baseline \
  --route ordinary \
  --workload-id <same-id>

scripts/run-1153-render-pilot.sh \
  --experiment tess-world \
  --variant candidate \
  --route ordinary \
  --workload-id <same-id>
```

Both legs run tess-array + packed replay. Only the candidate runs repeated world replay.

Repeat on `symmetric-1040` if the ordinary route is visually correct and telemetry shows useful world-replay hits.

Retention requires zero packed failures, unchanged stencil/lighting visuals, meaningful world-replay coverage, and a measurable frame-tail or CPU-time improvement.
