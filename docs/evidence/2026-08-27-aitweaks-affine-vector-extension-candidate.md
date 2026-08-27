# AI Tweaks affine-vector extension candidate

Date: 2026-08-27

Install: Starsector 0.98a-RC8, AI Tweaks 2.2.10, current heavily modded profile,
macOS on Apple M5, bundled x86-64 Zulu 17 under Rosetta

Status: prepared as opt-in v2; exact installed-bytecode verification passes, live combat pending

## Remaining opportunity after v1

The accepted v1 run proved that three exact affine expressions no longer allocate through
`Vector2fKt.times` followed by `Vector2fKt.plus`. That same 30.004-second combat window still
placed 79.4 MiB of weighted allocation at `Vector2fKt.times` and 88.0 MiB at
`Vector2fKt.plus`. The broader `Projectile.interceptArc` subtree carried 207.3 MiB, while
`Beam.projectileMotionInTargetFoR` was an immediate caller for 48.0 MiB. These JFR weights overlap
and are statistical prioritization evidence, not an exact byte count or FPS estimate.

Installed-bytecode review found four more copies of the proven adjacent expression inside the
shared `projectileMotionInTargetFoR` method: two in `Projectile` and two in `Beam`. Each computes
one required affine result by first allocating a throwaway scaled vector. Together with v1's
`Projectile.targetMotion`, `Beam.targetLocation`, and `LinearMotion.positionAfter`, v2 covers seven
exact affine expressions in five methods across the same three classes.

Projectile's shared method also contains one separate `div` followed by `plus`. V2 explicitly
requires and preserves that unpaired addition; it does not broaden the helper into division or
rewrite every vector operation in the method.

## Semantic and admission boundary

`aitweaks-affine-vector-fusion-v2` keeps the existing opt-in property
`preflight.combat.aiTweaksAffineVectors=true`. It retains the same Kotlin null-check order,
floating-point multiply/add order, and fresh `Vector2f` result. It adds no field, cache, retained
game object, mutable cross-frame state, save data, or serialization surface.

The two shared-method rewrites are atomic with each class's existing v1 rewrite. Before changing
anything, the transformer requires every reviewed method and exactly the expected adjacent-pair
count; Projectile must also retain exactly one unrelated `plus`. It then replaces all reviewed
pairs and injects one private synthetic helper. Archive, custom loader, Java 17 class version,
class hash, method descriptor, call count, adjacency, or second-rewrite drift preserves the entire
original class.

The installed archive and class identities remain:

| class | SHA-256 |
| --- | --- |
| `Projectile` | `50db98c94a6589e39bdc4d2e39bfb33161e8f50de1f54cabfbca7cca1c065379` |
| `Beam` | `afd4434d05988ddeed96fc5821206399cf32067c6c7f1ac54e63c411fd3f8bc2` |
| `LinearMotion` | `64889050bc99efaa693fe73e8fb6cf80af319907b77dbd9fdb03a433da67cecc` |

The AI Tweaks archive SHA-256 is
`9f6179bcd2df2e3ce8cea2da79051c9f1be3c9b71712c6c28d7568b777ecf5b2`.

## Verification and admission gate

Five focused Java 17 tests pass. Four woven-fixture tests cover disabled behavior, atomic
multi-method replacement, exact pair and unrelated-plus counts, helper arithmetic, wrong-hash and
changed-shape fallback, second-rewrite fallback, and registry provenance. The exact installed-JAR
test transforms all three shipped classes, requires seven replacements and the preserved unrelated
addition, and ASM data-flow-analyzes every resulting method.

The candidate remains off by default and makes no performance claim. Admission requires the same
Preflight-only 1,040-DP route, all three exact class transforms, no contained or lifecycle fatal,
and direct allocation evidence showing that the two shared methods no longer allocate through
their reviewed `times` leaves. A focusless run can establish the structural result but cannot
establish FPS or percentile uplift.
