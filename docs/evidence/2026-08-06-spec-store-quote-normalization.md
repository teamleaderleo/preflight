# SpecStore smart-quote normalization no longer compiles regexes

**Date:** 2026-08-06

**Runtime:** Starsector's bundled Zulu 17.0.10 x86_64 JRE under Rosetta 2

**Target:** exact 0.98a-RC8 `com.fs.starfarer.loading.specs.SpecStore`

**Status:** exact equivalence, installed-class transform, full verification, and two live menu gates pass

## Why this seam was selected

The fresh startup recording at
`~/.starsector-preflight/benchmarks/20260806-013617/runs/fast-profile-1` left 14 main-thread
leaf samples (roughly 140 ms at the recording interval) in `FloatingDecimal` below JSON numeric
access. Two broader numeric-cache experiments had already regressed exact replay and were deleted.
The same recording exposed a narrower, semantics-complete seam in SpecStore's
`Ó00000(String)`: every value passes through two fixed `String.replaceAll` calls that normalize
runs of curly double quotes and runs of curly single quotes/replacement characters.

The adapter is pinned to the reviewed SpecStore class, source archive, method descriptor, exact two
virtual calls, their order, and all four regex/replacement constants. It rewrites only those calls
to the existing regex runtime. The new fast path scans once, returns the original string when no
character matches, and collapses a matching run exactly as Java's `+` quantifier does. Any class,
archive, method, call-count, order, or constant drift leaves vanilla bytecode untouched. The rewrite
composes with the existing prepared-variant cache on the same class.

## Equivalence and exact installed target

The runtime test compared both replacements with `String.replaceAll` over 10,000 deterministic
random strings, or 20,000 replacement results, including interleaved quote runs, U+FFFD, ordinary
Unicode surrogate code units, and no-match inputs. The plan tests cover the exact two-call shape,
constant drift, and double-weave refusal. An opt-in test extracted the installed SpecStore, verified
its SHA-256, transformed it, and found exactly two calls to the runtime bridge.

## Bundled-JVM replay

The source is
`docs/evidence/2026-08-06-spec-store-quote-normalization-benchmark.java`. Each observation processes
one million strings through both replacements after alternating warmup. Every result produced the
same checksum, `6404763838538685481`.

| implementation | seven runs, ms | median |
| --- | --- | ---: |
| Java regex | 1003.090, 1047.463, 1187.978, 1162.118, 1165.893, 1178.558, 1157.291 | 1162.118 ms |
| linear scan | 289.382, 287.987, 287.342, 289.465, 291.961, 288.257, 288.858 | 288.858 ms |

The exact operation is **4.02x faster**, saving **873.260 ms per million normalized strings**.
This is CPU and allocation evidence rather than a whole-launch claim: loader work overlaps and the
real corpus is much smaller.

## Live gates

The final unattended `--fast` gate is
`~/.starsector-preflight/runs/specstore-quote-normalization-telemetry-20260806-014706`. It reached
the main menu in **21.93 seconds**, then stopped normally and left no game JVM. Dedicated telemetry
reported **57,248 fast replacements**, which is exactly 28,624 values passing through the two fixed
operations. Adapter health was ACTIVE: all 40 transformations applied with zero decline, unavailable
plan, shadowed target, or contained failure. The prepared variant cache simultaneously served all
5,573 entries, proving that the two disjoint SpecStore rewrites compose in the real launch.

The preceding gate reached the menu in 22.26 seconds with the same clean health. These are excellent
safety and use results, but adjacent single diagnostics do not establish a new startup median.
