# AI Tweaks split-arcs list sizing

Date: 2026-08-27

Install: Starsector 0.98a-RC8, AI Tweaks 2.2.10, current heavily modded profile,
macOS on Apple M5, bundled x86-64 Zulu 17 under Rosetta, Preflight fast preset

Status: accepted with limit; exact transform, focused tests, and live 1,040-DP route passed

## Result

`aitweaks-split-arcs-capacity-v1` pre-sizes the two temporary `ArrayList` families in the exact AI
Tweaks 2.2.10 `WeaponGroup.splitArcs(List)` method. The first list receives exactly two limit
objects per input arc, so its capacity is `input.size() * 2`. Each filtered list receives no more
than the input arc count, so its capacity is `input.size()`.

The transformation changes capacity only. It does not change constructed elements, iteration or
sort order, comparisons, floating-point operations, returned objects, persistent fields, game
objects, or save data. Admission pins the exact archive, AI Tweaks custom loader, Java 17 class
version, class SHA-256, method descriptor, and both reviewed zero-argument constructor sites. Any
identity or instruction-shape drift retains original bytecode.

## Live evidence

The opt-in plan was enabled for one Preflight-only run of
`campaign-simulation-combat-1000dp`. The driver prepared 24 mirrored ships and 520 DP per side,
enabled 2x simulation speed, zoomed the viewport from 1,800 to 6,120 world units, measured a clean
30.006-second combat window, and exited successfully. The exact plan applied once with no
evaluation problems. That observation also included the then-current
`aitweaks-select-target-snapshot-v4` plan and recorded 73,917 selection snapshots. A later heavier
run reproduced a null receiver in the original target-selection expression, so that entire shared
select-target boundary was retired rather than masked. The independent split-arcs capacity plan
does not rewrite that method and remains retained.

Within the combat window, the `splitArcs` subtree carried 42 JFR allocation samples with 93.9 MiB
of statistical weight. Ten samples carrying 42.0 MiB were backing `Object[]` arrays allocated
directly by the pre-sized `ArrayList` constructors. No sample contained `ArrayList.grow`. This is
the expected structural result: the necessary list objects and their initial backing arrays remain,
while growth and copy churn is absent from the observed transformed method.

The live frame window contained 649 frames. It averaged 21.87 FPS with a 24.39 FPS median, 7.67
FPS 1% low, 5.97 FPS 0.1% low, 78.4 ms p95, 130.4 ms p99, and 275.11 ms/s stutter burden. Those
figures are a hostile workload observation, not a causal uplift claim. The immediately preceding
active run used the same named route but different battle evolution and thermal history; its
directionally worse frame figures are insufficient to isolate this small allocation change.

The frame hook averaged 18.61 microseconds across 2,160 samples. The machine reported no macOS
thermal or performance warning immediately before the run; physical warmth is still treated as
run metadata, not as evidence of stable thermals.

## Verification and provenance

Five focused Java 17 tests passed: four woven-fixture tests and one integration test against the
exact installed AI Tweaks archive. The live adapter report confirms exact admission and application
of the candidate. Known unrelated startup warnings and the vanilla simulation-dialog warning
remained non-fatal; no candidate-specific exception appeared.

The compact measurements and hashes are retained in
[`data/2026-08-27-aitweaks-split-arcs-capacity.json`](data/2026-08-27-aitweaks-split-arcs-capacity.json).
The raw JFR and copied megabyte log tail are intentionally not committed and are pruned after this
checkpoint.

## Claim boundary

Acceptance rests on exact semantic scope, direct removal of the observed growth path, clean live
execution, and fail-closed tests. One opt-in observation does not establish a universal FPS or
percentile improvement. A later knob-off run may strengthen the performance estimate, but is not
required to preserve this structurally bounded allocation reduction.
