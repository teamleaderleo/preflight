# AI Tweaks affine-vector extension candidate

Date: 2026-08-27

Install: Starsector 0.98a-RC8, AI Tweaks 2.2.10, current heavily modded profile,
macOS on Apple M5, bundled x86-64 Zulu 17 under Rosetta

Status: accepted with limit as opt-in v2

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

## Verification and admission

Five focused Java 17 tests pass. Four woven-fixture tests cover disabled behavior, atomic
multi-method replacement, exact pair and unrelated-plus counts, helper arithmetic, wrong-hash and
changed-shape fallback, second-rewrite fallback, and registry provenance. The exact installed-JAR
test transforms all three shipped classes, requires seven replacements and the preserved unrelated
addition, and ASM data-flow-analyzes every resulting method.

The final Java 17 `mvn verify` gate passed all five reactor modules in 40.764 seconds.

The Preflight-only live route completed all 34 scenario steps. It constructed 24 mirrored ships
and 520 DP per side, enabled 2x combat speed, widened the viewport from 1,800 to 6,120 units, and
held the steady-state combat window for 30.003 seconds. All three exact class transforms applied,
with zero transformation declines and zero contained failures. The host reported no macOS thermal,
performance, or CPU-power warning before or after the run.

The JFR contained 2,661 classified combat-allocation samples. Filtering to
`Projectile.projectileMotionInTargetFoR` retained five samples: 2.0 MiB each at the required
`Vector2fKt.div`, the injected affine helper, and `Vector2fKt.minus`, plus 1.4 KiB at the required
iterator. No `Vector2fKt.times` or paired `plus` leaf remained. The broader
`Beam.projectileMotionInTargetFoR` family retained 97 samples, including 2.0 MiB each at the
helper and `minus`, and likewise no reviewed `times` or paired `plus` leaf. These are weighted JFR
samples and structural evidence, not exact allocation totals or a lockstep delta against v1.

The scenario and live candidate execution were clean, but the wrapper originally recorded
`FATAL_LOG_EVIDENCE` and exit 6 after the successful capture. The old lifecycle classifier treated
`AL10.nalGetSourcei` during the exact controller-requested OpenAL teardown as a game fatal even
though it followed `CombatMain - Error cleaning up`; the launcher itself returned zero and the
exact controller-stop receipt matched the process identity. Commit `790b6c8e` now recognizes only
the reviewed OpenAL native calls when all of that teardown context is present. Its negative test
keeps the same native error fatal when the cleanup context is absent. The recorded run is not
retroactively relabeled; the compact evidence preserves both its original outcome and the
post-run classification.

The candidate remains off by default. The run was not frontmost: focus filtering dropped 2,054
inactive intervals and retained zero eligible combat frames. Acceptance therefore establishes
exact application, clean candidate execution, and removal of the four additional throwaway scaled
vectors, but makes no FPS, percentile, or thermal-uplift claim. The compact observation is
[`data/2026-08-27-aitweaks-affine-vector-extension.json`](data/2026-08-27-aitweaks-affine-vector-extension.json).
