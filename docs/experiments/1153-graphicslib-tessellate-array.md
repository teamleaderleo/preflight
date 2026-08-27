# Issue 1153 GraphicsLib cached-tessellation array experiment

GraphicsLib 1.12.1 already caches GLU tessellation output in `org.dark.graphics.util.Tessellate`. Its cache-hit replay still allocates one `Vector2f` per cached vertex, calls `VectorUtils.rotate` and ship transform accessors inside the vertex loop, and submits every vertex through `GL11.glVertex2f` between `glBegin/glEnd`.

This experiment ports FR's immediate-mode-to-array idea onto that narrow replay path. Cache construction and invalidation remain GraphicsLib's code. Only the reviewed cache-hit immediate replay is replaced.

## Candidate behavior

For one cached polygon the generated helper:

1. reuses one direct `FloatBuffer`, growing it only when a larger polygon appears;
2. reads ship facing/location once;
3. computes sine/cosine once and writes transformed world-space XY pairs into the buffer;
4. preserves client-array state with `GL_CLIENT_VERTEX_ARRAY_BIT`;
5. disables fixed-function client arrays whose current values immediate mode would have used;
6. enables only the vertex array and submits one `glDrawArrays(tessData.glType, 0, vertexCount)`;
7. restores the saved client-array state.

Current color remains white after the draw, matching the old cache-hit branch's `glColor3f(1, 1, 1)` side effect.

## Activation

The experiment is disabled by default:

```
-Dpreflight.graphicsLibTessellateArray=true
-Dpreflight.graphicsLibTessellateArray.report=/absolute/path/graphicslib-tess-array.json
```

Use an external exact/source-bound AdapterTarget for the installed
`org/dark/graphics/util/Tessellate` class with the temporary
`lwjgl-display-frame-time-probe-v1` carrier plan ID. Capture the class SHA, Graphics.jar source hash/path, and loader identity from adapter discovery on the installation. Do not copy an identity from another GraphicsLib archive.

The bytecode rewrite adds another gate. `render(BoundsAPI,float,float,float,ShipAPI)` must contain exactly one reviewed cache-hit region with:

- one `GL11.glBegin` and one `GL11.glEnd`;
- one `GL11.glColor3f` and one `GL11.glVertex2f` inside that region;
- one `VectorUtils.rotate` and one `Vector2f.add`;
- the reviewed `TessData.glType`, `TessData.vertices`, and `VertexDataV2.data` fields;
- one ship-facing and one ship-location read from the same local;
- no outside jump or exception-handler target entering the removed region.

Changed, duplicate, or previously transformed code declines the rewrite.

## Causal telemetry

The optional report records:

- array batches;
- vertices submitted;
- direct-buffer growth count and largest capacity;
- mean vertices per batch;
- estimated immediate vertex calls avoided (`vertices - batches`).

## Gameplay gate

Measure this candidate separately from the frame-sync candidate first. Use the ordinary combat route and symmetric 1,040-DP route from #449/#1152 with identical frame-time probe settings. Compare average FPS, p50/p95/p99 frame time, 1% low, >50 ms frames, >100 ms frames, workload fingerprint, report counters, and visual correctness.
