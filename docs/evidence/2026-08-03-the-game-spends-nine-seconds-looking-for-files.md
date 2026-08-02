# The game spends nine seconds looking for files it has already found

**Date:** 2026-08-03
**Install:** Starsector 0.98a-RC8, 83 enabled mods, macOS 15, M5 MacBook Air (10 cores), 24 GB
**Benchmarks:** [`resolve`](2026-08-03-resolve-benchmark.java.txt),
[`merged-resolve`](2026-08-03-merged-resolve-benchmark.java.txt),
[`loadjson-split`](2026-08-03-loadjson-split-benchmark.java.txt)
**Probe run:** `~/.starsector-preflight/runs/stock-mods-probe-20260803-032619`
**Result:** **5.25 s + 4.27 s = about 9.5 seconds of a launch is finding files, not reading them.**

## What changed since the last measurement

The previous note in this series priced the mod-facing JSON path at 0.85 s and concluded the
opportunity was ~0.16 s. That measurement was taken against a corpus extracted from logs of an
install **where AshLib's and GraphicsLib's patched jars were already installed** -- so the
redundancy had already been removed before the corpus was recorded. It measured the cost of work
that had been optimised, and reported it as the cost of the work.

Both mods have been reverted to their shipped jars (the patched builds are preserved alongside as
`*.codex-patched-*.backup`). That is not only to fix the measurement: an `AdapterTarget` pins an
exact jar SHA-256, so a plan built against a locally patched jar would decline on every stock
install and help nobody.

On the stock install the same corpus is a different size:

| plain `loadJSON` calls per launch | patched mods | **stock mods** |
| --- | ---: | ---: |
| calls | 12,130 | **39,017** |
| distinct paths | 8,378 | 8,378 |
| repeats | 3,751 (31%) | **30,639 (79%)** |

By extension, on stock: `.ship` is read **9.7x** per distinct file (25,768 calls, 2,660 files),
`.skin` 7.5x, `.variant` 2.6x, `.wpn` 2.0x.

## Where a launch actually goes

One probed direct launch on the stock jars, `--adapter --startup-phase-probe --no-record`, to the
main-menu marker in **84.49 s**:

| block | cost |
| --- | ---: |
| mod callbacks | **23.97 s** |
| spec store | 17.48 s |
| progress 10 -> 25% | 13.39 s |
| progress 50 -> 75% | 9.07 s |
| everything else | ~20.6 s |

and inside the callbacks, out of 76 plugins:

| plugin | cost | share |
| --- | ---: | ---: |
| `org.dark.shaders.ShaderModPlugin` (GraphicsLib) | **10.92 s** | 46% |
| `ashlib.data.plugins.AshLibPlugin` | **8.38 s** | 35% |
| `kaleidoscope.plugins.ModPlugin` | 2.54 s | 11% |
| the other 73 | 2.01 s | 8% |

## The measurement

`LoadingUtils.ô00000(path)` does not open a path. It asks `com.fs.util.C`, which walks an ordered
list of roots -- one per enabled mod, plus the core directory, **84 entries here** -- and returns
the first one that has the file. The whole method is `synchronized`.

Replaying the stock corpus in call order, on the game's own JVM (Zulu 17.0.10, `x86_64` under
Rosetta, which is what the game runs):

| arm | round 1 | round 2 | round 3 |
| --- | ---: | ---: | ---: |
| open by resolved absolute path | 0.490 | 0.484 | 0.485 |
| **open through the game's resolver** | **5.740** | **5.735** | **5.773** |

**The search costs 5.25 seconds.** It performs **1,618,401 filesystem probes for 38,018 calls** --
42.6 per call, because a file belonging to a mod late in the order is not found until every earlier
root has been ruled out.

The merged loader is worse in a different way. It cannot stop at the first hit, because its whole
job is to combine every root that overrides the path, so it pays the **full** 84-root walk on every
call:

| arm | round 1 | round 2 | round 3 |
| --- | ---: | ---: | ---: |
| collect every source, 14,108 merged calls | 4.247 | 4.277 | 4.346 |

**4.27 seconds, and it finds 14,977 sources for 14,108 calls.** Over 13,900 of those calls have
exactly one source. The walk is 94% wasted: it is confirming, 84 directories at a time, that
nobody overrode the file.

## Reconciling it with the read, strip and parse split

The companion benchmark opens the resolved absolute path directly, so it priced everything *except*
the search. On the stock corpus it reports 2.92 s for the whole read-strip-parse path. Adding the
search back:

| | seconds |
| --- | ---: |
| resolve (plain) | 5.25 |
| read | ~0.50 |
| strip | ~0.25 |
| parse | ~1.05 |
| **plain `loadJSON`, total** | **~7.0** |
| resolve (merged), separate | 4.27 |

That is what AshLib's own upstream patch found from the other direction. Memoizing its repeated
`loadJSON` calls removed 7.07-7.44 s from an 8-second callback -- and it did so almost entirely by
not performing the search again, which is why 22 lines of `HashMap` could be worth seven seconds.

## Why this is the right thing for Preflight to own

**Because it is not a JSON problem.** Every earlier framing of this -- memoize the parse, share the
`JSONObject`, replace the comment stripper -- was aimed at the 1.3 s that is genuinely parsing and
reading. The 9.5 s is path resolution, and a resolved path is an immutable string. There is no
mutable-sharing hazard, no deep-copy question, and no reason a cached answer would ever differ from
a computed one while the root list is unchanged.

It also has the property the project keeps looking for: it scales with `calls x mods`, so it hurts
the largest profiles the most, and it serves **every** caller at once -- the game's own loaders and
all 76 mod plugins -- because they all funnel through the same resolver. Preflight already builds a
`ResourceIndex` over exactly this data.

Two things to settle before building:

- `C.Object(path, boolean)` reads and **clears** a one-shot directory restriction set by
  `C.return(String)`, and a one-shot static `super:Z` flag. A memo that skips the walk must still
  consume that state or the next real call will see a stale restriction. This is the correctness
  detail that decides the shape of the plan;
- the root list is mutable -- `Õ00000` appends and `o00000` inserts at the front -- so the memo has
  to be invalidated when it changes, or keyed on it.

## Reproduction

```bash
scripts/probe-launch.sh --label stock
```

The corpora come from the game's own logs: concatenate the rotated files, split into runs wherever
the millisecond timestamp goes backwards, take one run, and separate the `Loading JSON from [...]`
lines by bracket shape -- a bare relative path is a plain load, a `DIRECTORY: root (rel)` line is
one source of a merged load.
