# Paused campaign render/allocation profile: presentation dominates time, contrails repeat allocation

**Status:** profile-backed exact-gated candidates implemented, bytecode-verified, and validated in
two Preflight-only live paused-campaign runs; an uplift claim still requires a comparable control

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

A follow-up 20-second wall recording reproduced the contrail site with 12 samples and 14,271,304
bytes of represented weight. More importantly, it made two remaining repeat seams exact:

- `Economy.advanceMarketConditionsWhenPaused` repeatedly reached Preflight's existing
  `marketSnapshotIterator`; its `ArrayList.toArray()` path appeared throughout the recording, and
  the iterator object itself appeared in seven samples. The older adapter had removed a redundant
  `ArrayList` wrapper but still copied the backing list on every pass.
- Nineteen main-thread allocation samples contained vanilla font wrapper
  `com/fs/graphics/A/C.return(FF)V`. Its exact bytecode contains 21 `StringBuilder` allocation
  sites: 18 rebuild three fixed punctuation strings on every call, while three more convert one
  character to a string inside wrapping loops solely to call `String.contains`.

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

### Stable paused-market and location snapshots

The existing campaign-maintenance runtime now caches the `Object[]` produced for exact market and
location call sites. A hit requires the same list object, size, order, and element identities.
Mutation builds a new array while any outer or nested iterator retains its previous stable array.
Non-random-access lists decline hits to avoid turning comparison into quadratic traversal, cache
failures fall back to the original `toArray()`, and the identity cache clears at 512 owners rather
than retaining an unbounded history across campaign churn. The state is agent-static and never
enters a save. For a candidate-only campaign comparison,
`-Dpreflight.campaign.stableSnapshots.disabled=true` restores a fresh `toArray()` on every nonempty
pass without disabling the older `campaign-entity-maintenance-v1` shortcuts. Adapter telemetry
records whether reuse was enabled and counts delegated baseline snapshots, so a captured cohort can
prove which path ran.

### Vanilla font wrapping

The exact font class is pinned to SHA-256
`01638a6e83c4a66eec57db511a903e2a361bb3f3e9b3679224b50b6d500903ea` in the reviewed
`fs.common_obf.jar`. The transform collapses the 18-builder fixed punctuation setup into the same
three string literals. It replaces the three exact
`table.contains(new StringBuilder().append(character).toString())` branches with
`table.indexOf(character) >= 0`. For a UTF-16 `char`, these tests are equivalent, including index
zero; only the temporary builder and string disappear. It adds no field or object state and declines
on any class, method, constructor-chain, literal order, branch shape, loader, or archive drift.

## Live candidate validation

Two fresh Preflight-only campaign launches installed all 64 requested transforms with zero
contained failures. Both used the same deterministic 30-second warm-up and 60-second settled
window. The first run attached a 20-second JFR during the settled interval; the second was a clean
frame-only confirmation.

| run | settled frames | average FPS | median FPS | 1% low | p95 / p99 | frames over 50ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| JFR-attached candidate | 3,430 | 57.24 | 58.82 | 29.85 | 21.4 / 33.5ms | 10 |
| clean candidate | 3,498 | 58.37 | 58.82 | 30.03 | 23.6 / 33.3ms | 0 |

The clean run recorded 1,006,495 stable snapshot hits from 420 rebuilds while executing 969,990
nonempty paused-market passes and 31,297 nonempty paused-location entity passes. LunaLib recorded
120,060 hits from one rebuild. The JFR-attached run's one 394.56ms outlier and ten frames above
50ms are treated as profiler perturbation, not candidate behavior.

The candidate JFR no longer reproduced the reviewed eight-`Vector2f` constructor chain in
`ContrailEngineV2.render`. One 1.4KB represented allocation sample remained below that method from
the unchanged intersection helper's returned vector; that is outside the transient input/destination
scratch replaced by this candidate. The initial large JFR sampling-reservoir event occurred under
LunaLib's current-location `getCustomEntities()` read and is excluded from allocation percentages.
Exact bytecode review showed that LunaLib's two apparent entity lookups have different semantics:
one checks the current location each frame, while the other scans all locations only when its
2--3-second interval elapses. Reusing one for the other would weaken entity-recreation detection,
so no such rewrite was made.

The bounded machine-readable evidence is
[`data/2026-08-27-paused-campaign-allocation-candidate.json`](data/2026-08-27-paused-campaign-allocation-candidate.json).
Raw JFR data remains local and disposable; it is not committed.

## Verification and remaining claim boundary

Focused tests transform the exact installed game and LunaLib classes, run ASM data-flow analysis on
every resulting method, prove the contrail render loop has zero remaining `Vector2f` constructions,
prove the eight new fields are private/synthetic/transient, and preserve the reviewed vector and
intersection call counts. Runtime tests cover Luna list replacement, resizing, caller mutation,
owner identity, and the owner bound. Additional installed-bytecode tests prove the font wrapper has
zero remaining `StringBuilder` constructions in the method, retains the exact combined literals,
uses three `indexOf(int)` calls, adds no fields, and passes ASM data-flow verification. Campaign
snapshot tests cover unchanged hits, mutation rebuilds, stable outer iterators, independent passes,
and the exact installed economy/location transforms.

This is live candidate validation, not an FPS uplift claim. Both runs enabled the candidates; older
paused and mixed campaign cohorts differ in duration, route, thermal state, and instrumentation.
The stable-snapshot candidate-only property and generic adapter kill switch remain available for a
future comparable control without launching vanilla or repeating startup work. The immediate
harness follow-up reads `CampaignEngine.isPaused()` at the exact campaign seam and emits separate
paused/unpaused distributions, excluding the interval that crosses a pause transition.
