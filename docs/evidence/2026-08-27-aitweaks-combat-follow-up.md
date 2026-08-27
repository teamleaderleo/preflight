# AI Tweaks combat follow-up: rank structural churn before scalar vector fusion

Date: 2026-08-27

Install: Starsector 0.98a-RC8, AI Tweaks 2.2.10, current heavily modded profile,
macOS on Apple M5, bundled x86-64 Zulu 17 under Rosetta, Preflight fast preset

Status: attribution and exact-bytecode review complete; the first ranked candidate is accepted

## Why this remains a combat lane

This report covers only the clean `combat-sample-2x` step from the retained deterministic
simulation run. It does not include startup, Continue, campaign, deployment, camera movement, or
inactive-focus intervals. The separate startup report owns the recurring six-second launch hitch.

The 30.006-second combat window contained 504 main-thread execution samples. AI Tweaks appeared on
181 stacks. `ExtendedShipAI.advance` was inclusive on 108 of all 504 combat samples (21.43%), while
`AutofireAI.advance` was inclusive on 61 (12.10%). These are statistical samples; the inclusive
owners overlap and do not mean that all work below either frame belongs to mod-authored code.

## Allocation ranking

Filtering the same window on AI Tweaks' `Vector2fKt` found 45 JFR allocation samples carrying
54.2 MiB of weighted `Vector2f` allocation:

| allocating leaf | weighted allocation |
| --- | ---: |
| `Vector2fKt.plus` | 38.2 MiB |
| `Vector2fKt.div` | 12.0 MiB |
| `Vector2fKt.times` | 4.0 MiB |

The leading immediate callers were `Projectile.targetMotion` (20.2 MiB),
`Projectile.interceptArc` (16.0 MiB), `Beam.targetLocation` (8.0 MiB), and
`LinearMotion.positionAfter` (6.0 MiB). A fused affine operation can remove intermediate vectors
while still returning a fresh result, but this is not the first follow-up candidate: the sampled
opportunity is fragmented across multiple exact classes and some final vectors are required
results rather than removable intermediates.

Filtering on `ExtendedShipAI.controlFacing` found 35 samples carrying 41.3 MiB. The largest coherent
subtree was `WeaponGroup.splitArcs` at 17.1 MiB. Within that slice, `ArrayList.grow` carried 6.9 MiB
and `Arrays.copyOf` carried 6.0 MiB. The exact method already knows both relevant bounds:

- its first temporary limit list receives exactly two entries per input arc;
- each per-segment filtered list can contain at most the input arc count.

Pre-sizing those two list families can remove growth and copy churn without changing element
construction, comparison, sort stability, iteration order, floating-point work, or returned
objects. That makes it the next narrow candidate after the collision-capacity experiment is
accepted or rejected. A later affine-vector pass remains worthwhile, but should not be mixed into
the same live run.

That candidate has now passed its exact installed-JAR tests and the deterministic 1,040-DP live
route. The accepted result and its claim boundary are recorded in
[the split-arcs capacity report](2026-08-27-aitweaks-split-arcs-capacity.md). The next profiling
pass should rank the larger weapon-range listener and vector-allocation families rather than add
more changes to this method.

## Exact identity and safety boundary

The retained JFR SHA-256 is
`2499e518f9e29d1f1770bf703cceebafc264a21040c840611ddddc316b6067ac` and its frame report is
`ef8db46ce8de8c88e70dcff64ccb1db639098c6037e1001839f5e1d2ec22c3f7`. The installed AI Tweaks
archive SHA-256 is
`9f6179bcd2df2e3ce8cea2da79051c9f1be3c9b71712c6c28d7568b777ecf5b2`; exact reviewed class hashes
are:

| class | SHA-256 |
| --- | --- |
| `WeaponGroup` | `788cbde04b454753673e4500ebdecc06735b56e175b05ff75de4f7847c076476` |
| `LinearMotion` | `64889050bc99efaa693fe73e8fb6cf80af319907b77dbd9fdb03a433da67cecc` |
| `Beam` | `afd4434d05988ddeed96fc5821206399cf32067c6c7f1ac54e63c411fd3f8bc2` |
| `Projectile` | `50db98c94a6589e39bdc4d2e39bfb33161e8f50de1f54cabfbca7cca1c065379` |
| `Vector2fKt` | `140de3c5cfdaf182abee0f598f7059ce8a662ab0319086f4302fd4ffe8229f16` |

Any implementation must retain the existing exact archive, custom loader, Java 17 class version,
class hash, method descriptor, and instruction-shape gates. Drift must preserve original bytecode.
The list-capacity candidate must not retain game objects, cross frames, alter saves, or change
simulation state. No FPS claim follows from this attribution report; live frame pacing and direct
allocation telemetry remain required before acceptance.
