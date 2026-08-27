# AI Tweaks affine-vector candidate

Date: 2026-08-27

Install: Starsector 0.98a-RC8, AI Tweaks 2.2.10, current heavily modded profile,
macOS on Apple M5, bundled x86-64 Zulu 17 under Rosetta

Status: prepared as an opt-in; exact installed-bytecode verification passes, live combat pending

## Ranked opportunity

The retained clean 30-second combat profile placed AI Tweaks on 181 of 504 main-thread execution
samples. `AutofireAI.advance` was inclusive on 61, or 12.10 percent. Within the same window,
`Vector2fKt.plus`, `div`, and `times` carried 54.2 MiB of weighted `Vector2f` allocation samples.
The leading immediate callers included `Projectile.targetMotion` at 20.2 MiB,
`Beam.targetLocation` at 8.0 MiB, and `LinearMotion.positionAfter` at 6.0 MiB. JFR weights are
statistical samples rather than an allocation census or an FPS estimate.

Bytecode review found the same removable shape in those three methods:

```text
base + (delta * scale)
```

The shipped Kotlin extensions allocate the scaled vector and then allocate the required result.
`aitweaks-affine-vector-fusion-v1` replaces each exact adjacent `times`/`plus` pair with a private
synthetic helper in the same owning class. The helper allocates only the required result.

## Semantic and provenance boundary

The helper retains the original Kotlin null-validation order, performs `delta.x * scale` before
adding `base.x` and the same sequence for `y`, and constructs a fresh `Vector2f`. It does not mutate
either input. It adds no field, cache, retained game object, cross-frame state, save data, or
serialization surface.

The opt-in property is `preflight.combat.aiTweaksAffineVectors=true`. All three transforms require
Java 17 class major 61, AI Tweaks' `CoreLoader`, the exact `aitweaks-core.jar` SHA-256
`9f6179bcd2df2e3ce8cea2da79051c9f1be3c9b71712c6c28d7568b777ecf5b2`, the exact method
descriptor, one adjacent instruction pair, and these class hashes:

| class | method | SHA-256 |
| --- | --- | --- |
| `Projectile` | `targetMotion` | `50db98c94a6589e39bdc4d2e39bfb33161e8f50de1f54cabfbca7cca1c065379` |
| `Beam` | `targetLocation` | `afd4434d05988ddeed96fc5821206399cf32067c6c7f1ac54e63c411fd3f8bc2` |
| `LinearMotion` | `positionAfter` | `64889050bc99efaa693fe73e8fb6cf80af319907b77dbd9fdb03a433da67cecc` |

Archive, loader, class, method, hash, or instruction drift preserves original bytecode. A second
rewrite also fails closed.

## Verification and admission gate

Five focused Java 17 tests pass. They cover disabled behavior, all three exact rewrites, operation
and allocation shape, wrong-hash and changed-shape fallback, second-rewrite fallback, registry
provenance, exact installed classes, and ASM data-flow analysis of every transformed method.

The candidate remains off and makes no performance claim. Admission requires a fresh Preflight-only
1,040-DP combat route with all three exact transforms applied, no contained or lifecycle fatal, and
direct allocation evidence for the three transformed method families. Frame pacing remains a
separate supporting measure; differing battle evolution cannot turn a single run into an FPS
percentage claim.
