# Deployment member lookup scans the icon grid on every question

**Date:** 2026-08-04

**Input:** combined gameplay pilot `gameplay-pilot-20260804-033528`
**Status:** bytecode mechanism established; exact positive-only cache built, not yet run in game

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
the cached shape at 0.23-0.32x the original scan across three clean JVM forks. That is directional,
not a game result. The next combined gameplay pilot must establish the actual hit rate and avoided
time before this leaves beta.
