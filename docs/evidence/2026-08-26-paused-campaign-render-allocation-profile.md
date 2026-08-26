# Paused campaign render/allocation profile: presentation dominates time, contrails repeat allocation

**Status:** profile-backed exact-gated candidates implemented and bytecode-verified; live frame A/B
still pending

**System:** Starsector 0.98a-RC8, current 83-mod development profile, M5 MacBook Air, bundled
x86-64 game runtime under Rosetta, 1440×932 windowed, Preflight Recommended

**Observed state:** the same controlled campaign process remained paused. The samplers attached to
that exact PID and sent no game input. No launch or save operation occurred during either sample.

## What the two samplers established

A native `/usr/bin/sample` observation collected 769 five-millisecond samples. The game thread
accounted for 759; 489 of those, 64.4 percent, were in the JVM sleep boundary used by Starsector's
frame limiter. The active remainder was distributed across sprite traversal and the OpenGL/Metal
clear, immediate-mode, display-update, and buffer-swap path. This agrees with the separate frame
phase result: paused campaign time is substantially presentation/limiter time, not one Java method
consuming the whole frame.

Two 30-second wall-time JFR attachments then checked repeat allocation and Java execution without
restarting the game. Main-thread allocation counters rose by 309,160,872 and 301,038,968 bytes.
Because this x86 runtime's JFR timestamp clock compresses under Rosetta, dividing those deltas by
the roughly 12 JFR timestamp seconds would overstate the rate. Normalized to the 30-second wall
recordings, they are approximately 10.3 and 10.0 MB/s. The first recording contained one young GC
pause of 1.765 milliseconds.

The repeated recording attributed 35 sampled `Vector2f` allocations, with 62,027,080 bytes of JFR
represented weight, to `ContrailEngineV2.render`. Exact installed bytecode has up to eight
non-escaping `Vector2f` constructions in that per-point path. LunaLib's renderer aggregation was
also allocation-bearing, but only one 2,094,520-byte represented sample appeared in the repeated
recording. It is a valid paper cut, not the dominant repeat allocator.

JFR represented allocation weight is sampling weight, not literal bytes allocated at the displayed
stack. In particular, a very large first sample at recording start was an accumulated sampling
reservoir and is excluded from the hotspot comparison.

## Candidate boundaries

### Vanilla contrail scratch

The exact `ContrailEngineV2` class is pinned to SHA-256
`deddd2b2f437cc71882c96b7f7442101155f93952cbf07a4223d44381f5d3647` in the reviewed
`starfarer_obf.jar`. The transform:

- adds eight private, synthetic, `transient` vectors to each contrail engine;
- initializes them together on the first render after construction or deserialization;
- replaces the three copy constructors and five destination constructors in the reviewed render
  path with the same `set`, `add`, and `sub` operations over those vectors;
- leaves the intersection helper, fade decisions, draw calls, and persistent contrail points
  unchanged; and
- declines on class hash, Java 17 bytecode version, method shape, constructor order, operation order,
  loader, or source-archive drift.

The exact helper consumes the temporary vectors synchronously. Its returned intersection is used by
the renderer only for a null check, so none of the eight substituted inputs/destinations escape the
iteration. Render execution is single-threaded and the scratch is per engine. `transient` keeps the
new fields outside Java/XStream save state; a loaded object sees null fields and initializes them on
its next render.

### LunaLib renderer snapshots

The LunaLib 2.0.5 script and entity classes are independently pinned to exact hashes. The transform
removes one dead `getRenderers()` copy from `advance` and caches only the entity's two private
combined-list call sites. LunaLib's public fresh, mutable `getRenderers()` contract is unchanged.
Source list identity, size, order, and element identity are rechecked before every hit; any mutation
builds a new stable snapshot so an outer iterator can finish safely. The owner cache is capped at
eight entries to prevent campaign churn from retaining an unbounded history.

## Verification and remaining claim boundary

Focused tests transform the exact installed game and LunaLib classes, run ASM data-flow analysis on
every resulting method, prove the contrail render loop has zero remaining `Vector2f` constructions,
prove the eight new fields are private/synthetic/transient, and preserve the reviewed vector and
intersection call counts. Runtime tests cover Luna list replacement, resizing, caller mutation,
owner identity, and the owner bound.

This is not yet an FPS claim. The current game process predates these bytecode changes and remains
alive for profiling, so a full repository verification and Preflight-only disabled/enabled campaign
A/B belong to the next controlled launch. The generic adapter kill switch provides the control
without launching vanilla or repeating the already-collected A cohort.
