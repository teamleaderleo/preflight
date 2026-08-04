# Deployment member lookup scans the icon grid on every question

**Date:** 2026-08-04

**Input:** combined gameplay pilots `gameplay-pilot-20260804-033528` and
`gameplay-pilot-20260804-042009`
**Status:** v1 live-validated; v2 mutation-tracked repair verified offline against the installed
0.98a-RC8 archive and awaiting a coordinated game run

## Stress follow-up: v1 validated 9.44 billion references

The later successful `20260804-125800-054-c273519e` gameplay session exposed a workload far larger
than the first pilot. The 500-member simulator grid produced:

- 9,430,577 cache hits;
- 11,149 delegated scans;
- 5,640 snapshots; and
- **9,441,696,960 identity-reference validations**.

That remained cheaper per element than vanilla's `HashMap.get` plus icon-member call, but it was no
longer a healthy cache shape: every nominal hit still traversed both complete backing sequences.

Version 2 removes that per-hit traversal. An installed-archive call-graph scan of the exact reviewed
class found that every vanilla write routes through four methods owned by the class itself:
`clear()`, both `addIconFor(...)` overloads, and `removeIconFor(...)`. Outside callers use
`getMembers()` for iteration and `getList()` for scrolling or layout; none mutate either returned
collection. The exact-hash-gated transformation now instruments the four authoritative mutation
methods. Add and remove invalidate only the affected member, clear drops the owner's index, and the
next lookup delegates once to the preserved original scan before recording its answer. This
one-delegation rule also preserves vanilla's first-match behavior if duplicate members exist. A hit
is then one identity-map lookup plus the existing cached-icon member identity check.

The report keeps `snapshots` and `validatedReferences` at zero for schema continuity and adds
`validationStrategy=instrumented-owner-mutations`, `additions`, `removals`, `clears`, and
`fallbackRecords`. Focused fixture tests and the opt-in installed `starfarer_obf.jar` transformation
test pass without starting or initializing the game. Live timing and gameplay confirmation remain
deliberately pending until the operator is ready for another launch.

The gameplay recording's second concentrated warm-session hotspot was not rendering. Vanilla
`supersuper.getIconForMember(FleetMember)` iterates the deployment grid's complete item list and,
for every item, calls `Oo00.getData(item)`. That accessor is one `HashMap.get`. The scan then asks
the returned icon for its member and continues until identity matches.

The recording contains 95 leaf samples directly in `getIconForMember`; another 389 samples land in
the inlined `HashMap.hash` shape dominated by this call chain. Full stacks show repeated calls from
deployment-dialog input and advance, plus `Advanced Gunnery Control` asking
`CombatState.getCurrentlySelectedInFleetDeploymentDialog()` during combat advance. The cost is
therefore repeatedly paid while the same deployment grid remains unchanged.

The adapter keeps the original method verbatim and caches only a positive member-to-icon answer.
Before reuse it compares, by identity and in order, both the live member list and grid-item list with
the snapshots attached to that answer. The wrapper also asks the cached icon for its current member.
A size change, same-size replacement, reorder, duplicate insertion/removal, changed icon member,
unknown class/archive/loader, disabled gate, or internal failure executes the preserved scan. Null
answers are never cached.

This deliberately retains linear reference validation. It removes the per-item `HashMap.get` and
icon-member calls that dominate the observed scan without assuming that a mod respects the grid's
mutation methods. A small development microbenchmark with 120 entries and a last-entry hit measured
the cached shape at 0.23-0.32x the original scan across three clean JVM forks.

The follow-up live pilot served 5,197 cached answers and delegated 2,190 calls, taking 1,097
snapshots and validating 5,726,882 cheap identity references. Comparable JFR execution/native
sample scans found 504 events containing the original icon lookup in the first pilot, versus one
event containing the preserved lookup and one containing the cache runtime in the follow-up. The
runs had different lengths and user actions, so these are statistical sample counts rather than
wall-clock timing, but the reduction is far larger than the workload difference. All 15 reviewed
transformations applied with no decline, contained failure, or health mismatch.
