# Remaining hot-path regex removal

## Finding

The profile after the generic resource-path scanner still contained a compiled-regex stack under
`SpecCacheKey.of`. That helper runs once for every hull, weapon, projectile, and variant prepared
cache lookup. Separately, `JarArchiveIndex.normalizeEntryName` retained the same drive-prefix regex
plus `split`, `ArrayDeque`, `ArrayList`, and `String.join`. Clean JAR names are normalized while an
archive record is created, inserted into its index, serialized, and reconstructed, so that pipeline
also affected native pre-launch preparation even when the classpath profile was already cached.

`SpecCacheKey` now uses an exact drive-prefix character check, including the old regex's line-
terminator behavior for malformed strings. JAR entry names use the already-tested
`ResourceIndex.normalizeRelativePath` scanner because their relative-path grammar is identical.
Clean names return the original `String`; malformed absolute, blank, and traversal paths still fail.
The classpath cache format and identity are unchanged.

## Real-corpus operation benchmark

[`2026-08-06-remaining-path-regex-benchmark.java`](2026-08-06-remaining-path-regex-benchmark.java)
was compiled for Java 17 and run on Starsector's bundled x86-64 JVM. It checked the old and new
results for equality over every input before timing ten alternating passes.

| corpus | operations | previous median | scanner median | relative | saved per pass |
| --- | ---: | ---: | ---: | ---: | ---: |
| cached JAR entry names | 22,128 | 440.09 ns/op | 51.73 ns/op | **8.51x** | 8.594 ms |
| prepared spec cache keys | 12,584 | 350.12 ns/op | 100.51 ns/op | **3.48x** | 3.141 ms |

The JAR result compounds because immutable archive/profile construction validates the same clean
names at several ownership boundaries. This is intentional defense in depth; making validation
allocation-free is safer than removing it.

## Cached classpath-index gate

The packaged native-arm CLI ran ten warm `classpath index build` commands against the same cached
profile on each implementation. Both conditions were adjacent and used the same 182-source profile
and cache state.

| implementation | median | range |
| --- | ---: | ---: |
| previous regex/container normalizer | 371.05 ms | 364.18--384.38 ms |
| retained shared scanner | **337.46 ms** | 332.95--359.68 ms |

The retained result saves **33.59 ms (9.1%)** from this native preparation stage. It is a measured
CPU/allocation reduction, not a claimed whole-game startup shift.

## Rejected adjacent experiment

A schema-scoped projectile-number pretyping candidate was also tested against all 1,263 prepared
projectile trees using the installation's own `json.jar` and bundled JVM. It converted only reviewed
root/engine numeric strings and left arbitrary mod behavior payloads untouched. Numeric results were
identical, but decode plus the corresponding reads regressed from a 4.596 ms median to 5.350 ms.
The candidate and its tests were deleted. The residual `FloatingDecimal` samples are not a useful
interception boundary; do not retry pretyping through a reflective post-decode walk.
