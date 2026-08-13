# Janino complete maps now share one deduplicated pack

**Date:** 2026-08-05  
**Install:** Starsector 0.98a-RC8, 83 enabled mods, macOS on Apple M5 under Rosetta  
**Result:** 145.96MiB of repeated complete-map bundles collapse to a 1.13MiB pack; the exact warm
Janino seam fell from 1,501ms to 29--38ms

## Why this exists

Janino's exact cache contract returns the complete generated-class map for every requested class.
That made the original persistent cache deliberately simple and safe: each of the 228 requests had
its own checksummed bundle. Inspection of the live context showed that those bundles repeated almost
all of their contents:

- 36,332 class occurrences and 149,732,372 expanded bytecode bytes
- only 280 unique binary-name/content pairs and 1,006,460 unique bytecode bytes
- no binary name with conflicting content
- 148.77x byte-level duplication

The 228 old bundles occupied 145.96MiB and contained between 4 and 280 classes each (median 164).
Reading and rebuilding those exact maps still cost about 1.5--1.7 seconds under Rosetta even though
Janino itself no longer compiled them.

## Implementation and safety

`GeneratedBytecodePack` stores each validated classfile once and represents each request as an
ordered list of class indexes. The complete artifact has an outer SHA-256; every class entry is also
checksummed and revalidated for its declared binary name and bounded shape when decoded. Returned
maps and byte arrays are independent mutable copies, preserving the old bundle contract and Janino's
ability to remove or mutate entries without contaminating another request.

The pack is bound to the existing full Janino context identity, which already hashes ordered mod
archives, loose providers, core/game/Janino inputs, bundled JVM modules, and compiler/loader policy.
It is written transactionally on successful shutdown. A missing, corrupt, mismatched, or incomplete
pack falls through to the existing individual bundle and then vanilla compilation paths. Results
from those paths extend a previously partial pack; unchanged all-hit sessions do not rewrite it.
Conflicting class content rejects publication rather than choosing a winner. All publication errors
are contained and leave the individual bundles active.

Unit coverage exercises deduplication, round-trip encoding, path confinement, checksum rejection,
mutable-result isolation, population, fresh-session hits, and partial-pack expansion. The installed
Janino replay compiles a real outer and nested class, starts a third fresh classloader/session, and
loads both solely from the pack. Full `mvn verify` passes: unit, failsafe integration, and synthetic
cross-process suites are green.

## Live gates

Population run:

- `~/.starsector-preflight/runs/janino-pack-populate-20260805-213106`
- all 228 requests hit the old individual bundles
- exact Janino cache time: **1,501ms**
- one pack written at shutdown, zero errors
- menu marker: 25.54s

Warm run:

- `~/.starsector-preflight/runs/janino-pack-warm-20260805-213157`
- all 228 requests hit the pack; zero miss or fallback
- exact Janino cache time: **29ms** (-1,472ms, -98.1%)
- pack load: 153ms during agent setup
- 280 unique classes, 1,006,460 unique bytes, 149,732,372 expanded bytes
- pack file: 1,183,935 bytes
- menu marker: 25.30s

The adjacent whole-launch pair differs by only 0.24s because startup work overlaps, pack setup is
before the game-log timing anchor, and launch noise is large. It is supporting evidence only; the
1,472ms claim belongs to the exact instrumented seam.

A subsequent ordinary, non-probed five-launch `--fast` cohort is retained at:

- `~/.starsector-preflight/benchmarks/20260805-213450`
- **25.08, 25.58, 25.45, 25.79, 25.80 seconds**
- median **25.58s**, range 0.72s
- all five runs: 228 pack hits, zero misses/fallback/errors/writes
- pack exact time: 31--38ms; load time: 140--172ms
- all five launches exited 0 with zero declined transformation or contained failure

The prior pre-fix fast cohort had a 26.06s median. The new median is 0.48s lower, but both cohorts
show fanless thermal drift and the intervening rule-command composition repair is also present, so
this is not a clean whole-launch attribution. The coolest result is only 80ms above 25 seconds:
repeatable sub-25 startup is plausible, but not yet measured.
