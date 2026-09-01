# AI Tweaks affine-vector candidate

Date: 2026-08-27

Install: Starsector 0.98a-RC8, AI Tweaks 2.2.10, current heavily modded profile,
macOS on Apple M5, bundled x86-64 Zulu 17 under Rosetta

Status: accepted with limit as an opt-in; exact installed-bytecode verification and live combat pass

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

## Live evidence

One Preflight-only `campaign-simulation-combat-1000dp` run applied all three exact transforms with
zero declines and zero contained failures. The driver prepared 24 mirrored ships and 520 DP per
side, enabled 2x simulation speed, zoomed the viewport from 1,800 to 5,744 world units, held the
combat window for 30.004 seconds, and exited through the controller with status 0. Lifecycle
scanning found no fatal; the two ignored OpenAL signatures were the already classified
controller-stop cleanup sequence.

The combat window contained 2,775 classified JFR allocation samples. Direct filters retained 187
samples through `Projectile.targetMotion`, 51 through `Beam.targetLocation`, and 17 through
`LinearMotion.positionAfter`. None of those three transformed stacks allocated through
`Vector2fKt.times` or `Vector2fKt.plus`. The necessary fresh result remained visible through each
owner's `$preflight$affine` helper:

| transformed stack | observed fresh-result leaf | weighted allocation |
| --- | --- | ---: |
| `Projectile.targetMotion` | `Projectile.$preflight$affine` | 58.0 MiB |
| `Beam.targetLocation` | `Beam.$preflight$affine` | 44.0 MiB |
| `LinearMotion.positionAfter` | `LinearMotion.$preflight$affine` | 24.8 MiB |

Other required vector allocations remain in the same broader paths, including position lookup and
division. JFR weights are statistical and this run's battle evolution and sampling scale differ
from the retained attribution profile, so the weighted values are not presented as a byte-for-byte
before/after comparison.

Starsector remained non-frontmost during the route. The frame reporter therefore discarded 2,009
inactive intervals and retained zero combat frames, as designed. This observation makes no FPS,
percentile, or smoothness claim. It proves exact live application, clean execution, and removal of
the reviewed intermediate-allocation leaves. macOS reported no thermal, performance, or CPU-power
warning immediately before or after the run; physical warmth remains ordinary run metadata.

## Verification and admission result

Five focused Java 17 tests pass. They cover disabled behavior, all three exact rewrites, operation
and allocation shape, wrong-hash and changed-shape fallback, second-rewrite fallback, registry
provenance, exact installed classes, and ASM data-flow analysis of every transformed method.

The admission gate passed and the candidate remains opt-in. Exact semantic scope, direct absence of
the reviewed intermediate leaves, clean live execution, and fail-closed tests support acceptance.
A later focused knob-off run can strengthen a performance estimate, but is not required to retain
this narrowly bounded allocation reduction. Compact measurements and hashes are retained in
[`data/2026-08-27-aitweaks-affine-vector.json`](data/2026-08-27-aitweaks-affine-vector.json); the
raw JFR and copied log tail are intentionally pruned after this checkpoint.
