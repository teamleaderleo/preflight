# Collision-query compact-index candidate: rejected before live launch

**Status:** exact Java 17 layout check falsified the allocation premise; candidate reverted without
launching Starsector

The accepted v2 collision-query set stores candidates in an encounter-order `Object[]`, uses a
second `Object[]` as its open-addressed membership table, and retains insertion hashes in an
`int[]`. A v3 candidate at `a7ffaf78` replaced the membership references with one-based indexes:
one `Object[]` for encounter order, one `int[]` index table, and one `int[]` insertion-hash array.
Exact differential tests and the full Java 17 verification gate passed, but that establishes
semantic compatibility—not an allocation win.

Before spending a Preflight launch on the candidate, the installed game JVM was checked with its
actual 6 GiB heap. The exact JDK 17 executable reported:

- `MaxHeapSize = 6442450944`;
- `UseCompressedOops = true`; and
- `UseCompressedClassPointers = true`.

An exact `Unsafe.arrayIndexScale` check in the same 6 GiB configuration reported four bytes for
both `Object[]` elements and `int[]` elements. The steady payload per capacity slot is therefore:

| Shape | Arrays | Payload per capacity slot |
| --- | --- | ---: |
| accepted v2 | two `Object[]` + one `int[]` | 12 bytes |
| candidate v3 | one `Object[]` + two `int[]` | 12 bytes |

The checked executable was
`/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home/bin/java`, SHA-256
`3b8800dfefc213409646bd569c52c58a305ac8ae1d1e0f9dabf3b27d5c9c6026`. To retrace the result,
run it with `-Xms6144m -Xmx6144m -XX:+PrintFlagsFinal -version`, then run the adjacent `jshell`
with `-J-Xms6144m -J-Xmx6144m` and inspect
`Unsafe.arrayIndexScale(Object[].class)` and `Unsafe.arrayIndexScale(int[].class)`. Recheck the
binary hash and the installed game's heap configuration first; a different JVM is a different
claim.

Array headers and alignment also do not create a useful steady-state advantage: both shapes retain
three arrays. The candidate might reduce GC reference scanning, but it adds an index load on every
membership probe. No evidence showed GC scanning was the dominant collision cost, so a live run
could not validate the candidate's stated allocation hypothesis. The candidate was reverted by
`bb3ebcc3`; the accepted v2 implementation remains current.

## Decision boundary

This rejects only the duplicate-reference-to-index substitution on the current reference JVM. It
does **not** establish that collision queries are exhausted. The accepted v2 family still carried
hundreds of MiB of weighted allocation samples in high-DP combat, and a fresh exact-window cluster
profile remains useful for choosing a different boundary.

Revisit this shape only if a materially different runtime uses wider object references, or if
direct evidence identifies GC reference scanning as a dominant cost. Under the current 6 GiB,
compressed-oops configuration, a new proposal must explain how it changes the equal 12-byte
payload or removes another measured cost.

The bounded machine-readable record is
[`data/2026-08-27-collision-query-compact-index-rejected.json`](data/2026-08-27-collision-query-compact-index-rejected.json).
No game process was created, and no JFR, screenshot, transformed binary, or launch directory was
needed for this rejection.
