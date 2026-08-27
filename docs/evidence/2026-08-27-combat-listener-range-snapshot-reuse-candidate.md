# Combat listener range snapshot reuse candidate

Date: 2026-08-27

Install boundary: Starsector 0.98a-RC8, exact `CombatListenerUtil` and `starfarer.api.jar`,
Java 17

Status: opt-in candidate prepared; focused and exact installed-archive tests pass; live combat
pending

## Why this is the next larger target

The accepted array-walk transform removed the redundant `ArrayList` wrapper and iterator from six
weapon-range listener queries. Its clean 30.003-second 1,040-DP follow-up still attributed all 313
filtered allocation samples, carrying 644.3 MiB of statistical weight, to the required `Object[]`
snapshots. That is materially larger than the remaining fragmented AI Tweaks vector families.

The snapshot cannot simply be deleted. Listener callbacks may mutate the live list, and
`CombatListenerManagerAPI.getListeners(Class)` exposes that mutable list to callers. Invalidating
only `addListener` and `removeListener` would therefore miss direct list edits and is rejected.

## Candidate boundary

`preflight.combat.listenerRangeSnapshotReuse=true` implies the accepted array-walk transform and
enables reuse behind its invariant runtime helper. The helper returns a cached private array only
after proving that the source is the exact JDK `ArrayList` used by the reviewed repository and that
its current size, order, and every element identity match the prior snapshot. Size, order, element
replacement, or direct mutation rebuilds immediately. A callback can mutate the source while an
outer query continues to walk its prior array; a reentrant query validates and receives the new
snapshot without altering the outer traversal.

Unknown list implementations always receive a fresh `toArray()`. Any non-fatal cache exception
also falls back to a fresh snapshot; `ThreadDeath` and `VirtualMachineError` remain fatal. Empty
queries share one private zero-length array because transformed code only reads its length and
elements. The identity table is capped at 512 source lists and clears at the bound. It is reset at
agent-session start, adds no fields to game objects, crosses no serialization path, and touches no
save state.

The transformed class always calls the same helper whether reuse is enabled or disabled. This
keeps bytecode invariant across the sub-knob so a transformation-cache entry cannot replay the
wrong mode. With reuse disabled, the helper delegates directly to `List.toArray()` without
retaining the list.

## Prepared verification

Twelve focused Java 17 tests pass: six plan/shape cases, five runtime cases, and one transform of
the exact installed archive. Coverage includes opt-in implication, invariant helper wiring,
byte-for-byte cache-mode invariance, wrong-hash and second-rewrite fallback, exact archive
provenance, stable reuse, size and identity mutation, in-progress snapshot isolation,
non-`ArrayList` fallback, and the strict 512-owner bound.

Live acceptance requires a fresh Preflight-only 1,040-DP route with the reuse property enabled,
zero runtime failures, a high validated hit rate, bounded owners, exact transform application, a
normal scenario exit, and disappearance or substantial reduction of the 644.3-MiB sampled array
family. Frame telemetry remains a separate claim: a focusless run with zero eligible combat frames
can validate structure and behavior but cannot claim an FPS or smoothness uplift.
