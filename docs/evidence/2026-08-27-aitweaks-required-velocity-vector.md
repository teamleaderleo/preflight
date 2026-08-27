# AI Tweaks scaled-velocity allocation audit

Date: 2026-08-27

Install: Starsector 0.98a-RC8, AI Tweaks 2.2.10, current heavily modded profile,
macOS on Apple M5, bundled x86-64 Zulu 17 under Rosetta

Status: rejected as a local vector-fusion candidate

## Why the large leaf is not a throwaway

After the accepted affine-vector v2 extension, the same focusless 30-second combat JFR placed
77.4 MiB of weighted allocation at `Vector2fKt.times` beneath
`Movement.getVelocity-impl`. That made it the largest remaining AI Tweaks `times` leaf, but the
installed bytecode shows a different contract from the accepted affine sites:

1. `ShipAPI.getVelocity()` returns the live velocity reference.
2. `Movement.getTimeMult-impl()` supplies the ship-local time multiplier.
3. `Vector2fKt.times()` creates and returns the scaled velocity.

There is no immediately following operation that consumes the scaled vector and discards it.
`Movement.getLinearMotion-impl`, for example, passes that exact returned vector into
`LinearMotion`'s final `velocity` field. `LinearMotion.getVelocity`, `component2`, `copy`, `minus`,
`equals`, `hashCode`, and `positionAfter` all expose or consume that object-valued representation.
The JAR also contains references to `getVelocity-impl` in 15 class files spanning ship handles,
movement, autofire, threat, vent, and ship-system logic.

Replacing the Kotlin `times` call with `new Vector2f(x * scale, y * scale)` would preserve the
same required allocation and merely rename its JFR leaf. Removing the allocation would instead
require one of two broad semantic changes:

- scalarizing velocity across `LinearMotion` and every dependent class; or
- caching a mutable scaled velocity by ship while correctly invalidating live velocity,
  ship-local time multiplier, frame, and object lifetime.

Neither is an exact local rewrite. The cache would also introduce retained game objects and
cross-frame mutable state, while scalarization would change a public object representation used by
many independently compiled classes. Both exceed the evidence and safety boundary for this pass.

## Exact identity and disposition

The reviewed `Movement` class SHA-256 is
`c788a88395ae60d98b9c3c0a7fec49b30e6098c6dc4de03aeea069e57b9fb7ec`; the AI Tweaks archive
SHA-256 is `9f6179bcd2df2e3ce8cea2da79051c9f1be3c9b71712c6c28d7568b777ecf5b2`.

Do not add a helper solely to make `Vector2fKt.times` disappear from allocation reports. Revisit
this family only if a future CPU profile shows the call overhead itself matters or a complete,
exactly gated scalar data-flow rewrite can be proven across every installed dependent class. The
77.4 MiB observation is weighted sampling evidence, not an exact byte count or FPS estimate.
