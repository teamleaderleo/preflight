# Allocation-light resource-path normalization

## Finding

After the texture/read caches removed most startup work, Preflight's own
`ResourceIndex.normalizeLogicalPath` remained in eight main-thread samples of the
`20260806-005705` fast profile. The old implementation performed a regex drive-prefix check, a
regex split, an `ArrayDeque` fill, an `ArrayList` copy, and `String.join` for every cache lookup.
Those calls are made from the hull, weapon, projectile, variant, audio, and texture cache paths, so
small per-call overhead accumulates under Starsector's x86 JVM on Rosetta.

The replacement validates and identifies path changes with one character scan. Already-normalized
relative paths return the original string. Only paths containing backslashes, repeated/trailing
separators, or `.` segments take a second pass and allocate a normalized result. Absolute paths,
Windows drive prefixes, blank paths, and `..` traversal remain rejected. Logical-path lowercasing
still uses `Locale.ROOT`; a measured ASCII specialization was no faster and was deleted.

## Equivalence and operation benchmark

The test suite compares the new scanner with the previous implementation across fixed edge cases,
Unicode paths, and 20,000 deterministic generated paths. It also retains the index lookup,
provider-order, containment, and rejection tests.

The same eight-path benchmark was compiled for Java 17 and run in a fresh process on Starsector's
bundled x86-64 JVM. Each of five measured rounds normalized two million paths after a 500,000-call
warmup:

| implementation | median ns/path | measured range | relative |
| --- | ---: | ---: | ---: |
| previous regex/container path | 371.85 | 367.17--383.89 | 1.00x |
| retained scanner and unchanged JDK lowercase | 54.03 | 47.24--71.48 | **6.88x** |

This is an operation benchmark rather than JMH, but the magnitude and live stack evidence are
large enough for the intended conclusion: keep the allocation-light scanner. Do not claim a
whole-launch speedup from these nanosecond numbers.

## Live gate

The unattended sampled run is retained at:

`~/.starsector-preflight/benchmarks/20260806-010507`

It reached the main menu in 23.60s, stopped normally, and reported ACTIVE adapter health with 35
transformations, zero declines, and zero contained failures. The immediately preceding sampled run
was 24.45s; that single-run difference is noise and is not used as attribution.

Main-thread samples containing `ResourceIndex.normalize` fell from 8 to 3. All remaining samples
were solely in the unchanged `String.toLowerCase(Locale.ROOT)` call. Regex match/split,
`ArrayDeque`, `ArrayList`, and `String.join` beneath the normalizer fell to zero. This supports a
CPU/allocation/thermal-headroom win while preserving conservative wall-time claims.
