# Startup resource reprioritization is no longer quadratic

**Date:** 2026-08-05

**Install:** Starsector 0.98a-RC8, 83 enabled mods, macOS on Apple M5 under Rosetta

## Finding

The current startup JFR placed 12 of 438 main-thread execution samples in
`ArrayList.indexOfRange`, all below `ArrayList.removeAll` in `ResourceLoaderState.init`. Exact
installed bytecode shows why: vanilla first walks the complete resource list and collects three
resource types into a smaller ordered list. It then calls `resources.removeAll(prioritized)` before
prepending that same ordered list. `ArrayList.removeAll` asks the smaller list to linearly scan for
every element in the larger list.

On this profile the exact live operands contain **55,359 resources** and **4,479 prioritized
entries**. This is list ordering work, not file reading, texture decoding, or a cache miss.

## Change and correctness boundary

An exact `ResourceLoaderState` adapter replaces only that one reviewed `List.removeAll` invocation
with a helper that builds a `HashSet` from the prioritized list and then performs the same removal.
The original prioritized list is untouched and is still passed to vanilla's subsequent
`resources.addAll(0, prioritized)`, preserving its iteration order and duplicates. The source list's
relative order, removal semantics, boolean result, and every surrounding instruction remain the
same.

The target pins the game class, archive, loader, method, and the exact remove-then-prepend bytecode
shape. Shape or build drift declines the adapter and leaves vanilla active. The rewrite composes
with both the full startup phase probe and the lightweight runtime-startup marker that already share
this class.

Tests cover randomized lists with duplicates and nulls, missing-prepend drift, double weaving, the
exact installed class, and full `mvn verify`.

## Same-launch control

The diagnostic property `-Dpreflight.resourcePriority.compare=true` copies the live source list,
runs and times vanilla removal on that copy, performs the indexed removal on the real list, then
compares the complete ordered results. It is off in ordinary launches.

`~/.starsector-preflight/runs/resource-priority-control-20260805-232118` measured:

| operation | exact live time |
| --- | ---: |
| vanilla `ArrayList.removeAll` | **558.257 ms** |
| indexed removal | **4.148 ms** |
| reduction | **554.109 ms (99.3%)** |

The ordered outputs matched exactly. The launch reached the menu in 24.94 seconds despite paying
for both operations, applied all 38 exact transformations with zero decline/failure, retained the
startup phase probe, and stopped automatically.

## Ordinary cohort

The following one-minute-cooled cohort is
`~/.starsector-preflight/benchmarks/20260805-232303`:

- **23.39, 23.63, 23.93, 24.35, and 23.68 seconds**;
- **23.68-second median**, 0.96-second full range;
- indexed removal took 2.019--2.184 ms on every run;
- every run saw the same 55,359/4,479 cardinality, applied all 33 exact transformations, reported
  zero decline/failure, and stopped automatically.

The immediately preceding one-minute-cooled cohort measured 24.12 seconds median. The 0.44-second
median shift is consistent with the exact 0.554-second critical-path reduction, though the sessions
were adjacent rather than a shuffled paired A/B. The seam measurement is the attribution; the new
cohort independently establishes repeatable sub-24-second startup.
