# `loadJSON` is not where GraphicsLib spends its time

**Date:** 2026-08-03
**Install:** Starsector 0.98a-RC8, 83 enabled mods, macOS 15, M5 MacBook Air (10 cores), 24 GB
**Benchmark:** [`2026-08-03-loadjson-split-benchmark.java.txt`](2026-08-03-loadjson-split-benchmark.java.txt)
**Corpus:** the real call sequence from one whole run of the game's own log
**Result:** the entire opportunity is about 0.2 seconds. The plan it was meant to justify is dropped.

## The hypothesis

GraphicsLib's `onApplicationLoad()` is the largest single remaining item in the startup profile.
Every mod that reads game data reaches it the same way: `Global.getSettings().loadJSON(path)`, which
is `StarfarerSettings$1.loadJSON(String)` calling `LoadingUtils.ô00000(String)`.

Preflight already caches five JSON corpora, and **not one of them can serve a mod.** Every one of
those plans pins a game *loader* class -- `HullJsonCachePlan` targets
`ShipHullLoaderPhasePlan.TARGET_CLASS`, `WeaponJsonCachePlan` targets
`WeaponLoaderPhasePlan.TARGET_CLASS`, `VariantJsonCachePlan` targets `SpecStorePhasePlan.TARGET_CLASS`
-- and a mod callback never goes through any of them. So the hypothesis was that a memo at
`LoadingUtils.ô00000` would serve every mod at once, out of caches Preflight has already built and
already has on disk.

## What the method actually does

```
ô00000(String path):
    log("Loading JSON from [" + path + "]")
    String text = super(path)            // resolve through com.fs.util.C, open, read, decode
    return Ö00000(path, text)            // strip # comments, then new JSONObject(...)
```

`Ö00000` is a character loop over the whole text into a `StringBuffer`: a `#` outside quotes starts
a comment that runs to end of line, `\r` is dropped, a newline clears both comment and quote state.
Then one `new JSONObject`.

So there are three costs to separate -- read, strip, parse -- and which one dominates decides where
a memo would have to sit, and whether it can be a safe one.

## The corpus

The game's log records every one of these calls, because `ô00000` logs its raw argument while the
two-argument `super(String, InputStream)` used by the game's own loaders logs a resolved location
description instead. That distinction makes the two separable in the log by shape alone.

The installed logs held ten complete runs. Per run:

| | `vanilla` | `full` |
| --- | ---: | ---: |
| all logged JSON loads | 26,916 | 15,192 |
| of those, `ô00000(String)` -- the mod-reachable ones | **12,130** | **12,129** |

**Preflight removes 11,724 JSON loads per run and not one of them is a `ô00000` call.** The
mod-side path is untouched, exactly as predicted. That part of the hypothesis was right.

Within one run's 12,130 calls there are 8,379 distinct paths, so 3,751 repeats (31%). By extension:

| ext | calls | distinct | repeats |
| --- | ---: | ---: | ---: |
| `.ship` | 5,308 | 2,660 | 2,648 |
| `.wpn` | 3,064 | 3,064 | 0 |
| `.skin` | 1,104 | 368 | 736 |
| `.variant` | 1,072 | 1,045 | 27 |
| `.paintjob` | 874 | 874 | 0 |
| `.proj` | 256 | 181 | 75 |
| `.json` | 206 | 104 | 102 |
| `.version` | 148 | 74 | 74 |
| `.default` | 88 | 1 | 87 |

The redundancy is not spread evenly: `.wpn` and `.paintjob` are each read exactly once, while every
`.ship` is read twice and every `.skin` three times. `data/config/hull_styles.json` is read 85 times
per run and `data/config/LunaSettingsDefault.default` 88 times. Those are the repeated whole-corpus
passes; the rest of the corpus is already read once and only once.

## The measurement

11,197 of the 12,130 calls resolve to a file on disk (the remainder live inside mod jars). Replayed
in call order with their repeats, against the real files, on the game's own JVM -- Zulu 17.0.10,
`x86_64` under Rosetta, which is what the game itself runs:

| arm | round 1 | round 2 | round 3 |
| --- | ---: | ---: | ---: |
| read only | 0.498 | 0.646 | 0.518 |
| read + strip | 0.595 | 0.571 | 0.562 |
| **read + strip + parse** | **0.866** | **0.841** | **0.832** |
| the game's own `Ö00000` for strip+parse | 0.865 | 0.855 | 0.857 |
| read + faster strip + parse | 0.784 | 0.769 | 0.769 |
| memoized text, still strips and parses | 0.705 | 0.692 | 0.677 |
| memoized `JSONObject`, shared instance | 0.742 | 0.609 | 0.609 |

The transcribed strip was checked against the game's own `Ö00000` on all 7,446 distinct files with
**0 disagreements**, and the two arms time within 25 ms of each other, so the split below is a
decomposition of the real method rather than of a lookalike.

**The whole mod-side `loadJSON` path costs about 0.85 seconds.** It splits roughly 0.52s read,
0.05s strip, 0.27s parse.

## What a memo could remove

| approach | saves | safe? |
| --- | ---: | --- |
| memoize the text, keep stripping and parsing | **~0.16s** | yes -- `String` is immutable |
| memoize the `JSONObject` | **~0.22s** | no -- hands out a shared mutable object |
| replace the `StringBuffer` strip with a bulk one | ~0.07s | yes, but not worth a pinned rewrite |

The ceiling on the entire idea is **0.22 seconds**, and the only version that is safe to ship is
**0.16 seconds**. Against a callback measured at 8.3 seconds, that is noise.

Worth recording separately, because it was the interesting half of the design question: the unsafe
variant is barely better than the safe one. Sharing a parsed `JSONObject` between callers buys 60 ms
over sharing the text, which is nowhere near enough to justify handing mods a mutable object that
another mod may already have written to. The read is the expensive part, the parse is second, and
the strip -- the part that looked worst in the bytecode, a synchronized per-character append -- is
the cheapest thing in the method.

## Consequence

**The `LoadingUtils` memo is dropped.** It was the wrong target, and the reason it looked like the
right one is worth stating plainly: 12,130 calls is a big number, the redundancy is real, and the
mechanism -- caches Preflight already has, unreachable from the code that needs them -- is a true
description of the system. None of that made it expensive. The log counts work, not time.

GraphicsLib's remaining seconds are therefore somewhere else. The probe run covering its callback
recorded 7,759 JSON-load lines against **6,191 texture-buffer cleanup lines**, and this measurement
prices the first of those two at well under a second. The texture-map traversal is what is left, and
that is where the next measurement goes.

## Reproduction

```bash
javac --release 17 -encoding UTF-8 -cp "$J/starfarer_obf.jar:$J/fs.common_obf.jar:$J/starfarer.api.jar:$J/json.jar:$J/log4j-1.2.9.jar" -d classes LoadJsonBench.java
```

The corpus is built from the game's own logs: concatenate the rotated files in order, split them
into runs wherever the millisecond timestamp goes backwards, take one run, keep the
`Loading JSON from [...]` lines whose bracket contents are a bare relative path, and resolve each
against the enabled mod roots then the core directory.
