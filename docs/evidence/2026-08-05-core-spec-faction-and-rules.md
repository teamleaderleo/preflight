# Core spec-store follow-up: faction attribution and fixed rules regexes

**Date:** 2026-08-05
**Install:** Starsector 0.98a-RC8, 83 enabled mods, macOS, M5 MacBook Air
**Status:** one diagnostic boundary retained; one small optimization retained; two slower experiments deleted

## Where the remaining six seconds went

The first post-GraphicsLib startup probe still placed **6.15s** in `SpecStore`. Its largest top-level
calls were campaign rules (1.734s), projectile definitions (652ms), faction loading (570ms), weapon
definitions (476ms), variants (349ms), and weapon spreadsheet hydration (347ms). Prepared JSON and
merged-read caches were all active, so the remaining weapon/projectile time is mostly live spec
hydration rather than reading.

`FactionLoaderPhasePlan` splits the previously opaque faction loader at its exact repeated calls.
It is observation-only and is composed only with `--startup-phase-probe`. The first live run
(`faction-breakdown-20260805-133441`) reached the menu in 27.66s with ACTIVE adapter health and
reported:

| faction operation | calls | time |
| --- | ---: | ---: |
| priority/known table expansion | 944 | **334ms** |
| live spec lookup | 683,270 | 39ms |
| JSON retrieval | 122 | 30ms |
| JSON name snapshots | 3,212 | 2ms |
| resource queueing | 4,766 | 1ms |
| factions CSV | 1 | <1ms |

This rules out another read cache and also rules out the surprising 683k lookups: they are already
only 39ms. The table helper is a nested candidate/tag scan over already-built hull, fighter, weapon,
and hullmod registries.

## Two caches that were correctly rejected

A launch-local `(callback class, id, tag) -> boolean` memo saw 4,234,733 calls but 2,242,001 misses,
hit its one-million-entry ceiling, and regressed the block from 334ms to **806ms**. It was deleted.

A result-level cache preserved candidate order and every faction insertion, but the corpus had 377
distinct ordered tag combinations and only 402 repeats. A second version shared individual tag
results, but still found 485 distinct `(category, tag)` domains and performed 1.82 million predicates.
The measured blocks were 381ms and 471ms respectively. Both versions and all production plumbing
were deleted. Vanilla's compact direct loop is better than either generic cache on this corpus.

## The retained rules change

The rules loader makes five fixed `String.replaceAll` calls and five fixed `String.split` calls per
row. The measured profile executes those ten sites **205,686** times. Java's equivalent operation is
a compiled `Pattern`; the exact adapter now reuses one `Pattern` per regex and continues to use
`Matcher.replaceAll` and `Pattern.split(input, 0)`, preserving replacement groups, zero-width rules,
and trailing-empty removal. The shipped `Rules` class hash, method descriptor, source archive,
loader, and the exact five-plus-five call shape are pinned.

The pre-change faction-breakdown run measured `rules-string-regex` at **257ms** and the whole rules
loader at **1.743s**. The live cache run (`rules-regex-cache-20260805-134808`) measured **202ms** and
**1.682s** respectively: 55ms and 61ms lower. The runtime saw 105,295 replacements, 100,391 splits,
five distinct patterns, and zero contained failure. Whole-launch time was 27.71s, but a 60ms change
is below launch noise and is not claimed from that number.

Focused equivalence and bytecode-shape tests pass, as does full `mvn verify`. The live run reached
the main menu, reported ACTIVE health with 33 transformations, and shut down automatically. The
regex rewrite is composed with the rules CSV cache, duplicate index, command-class publisher, and
startup attribution whichever exact target wins selection; it is therefore present in ordinary
adapter/`--fast` launches, not only diagnostic probes.
