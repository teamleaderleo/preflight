# SpecStore is no longer a reading problem

**Date:** 2026-08-03
**Install:** Starsector 0.98a-RC8, 83 enabled mods, macOS 26.5, M5 MacBook Air (10 cores), 24 GB
**Run:** `~/.starsector-preflight/runs/merged-reads-20260803-215048`, 36.12s to the main menu
**Status:** the whole remaining merged-read surface measured for the first time, in one number

## The question

Five caches took `SpecStore` from 19.8s to 9.8s. Each of them pinned one loader and cached that
loader's own merged reads:

| cache | what it removed |
| --- | ---: |
| variant JSON | 3.58s -> 0.51s |
| weapon JSON | 2.76s -> 0.36s |
| projectile JSON | 1.62s -> 0.30s |
| hull JSON | 2.06s -> 0.34s |
| rules CSV | 0.83s -> 0.17s |

That shape answers *how expensive is the hull loader*. It cannot answer *how much merged reading is
left anywhere*, and that is the question that decides whether the next cache should be a sixth
pinned loader or one general one.

## The instrument

`LoadingUtils` has ten public read entry points and exactly two of them merge:

- `super(List, String, boolean, boolean) -> JSONArray` builds one array from one CSV per enabled
  root;
- `super(String, Set) -> JSONObject` overlays one JSON file per enabled root.

Every other CSV or merged-JSON overload delegates into that pair, so wrapping the pair counts each
merged read exactly once regardless of which overload the caller used -- including the reads inside
mod callbacks, which no per-loader probe can see. `MergedReadProbePlan` renames both and gives their
names to delegators that time the call and hand it straight on.

## The answer: 2.86 seconds

Over the whole launch -- `SpecStore`, the progress phases, all 76 mod callbacks:

| | calls | seconds |
| --- | ---: | ---: |
| merged CSV | 37 | 0.82 |
| merged JSON | 2,329 | 2.04 |
| **everything** | **2,366** | **2.86** |

2,366 calls. Before the five caches the same launch made about 15,000. **The reading is mostly
gone**, and what is left splits into two piles that want different fixes. The split is visible in
the path itself: a cache fallback re-enters through the absolute install path, everything else
arrives relative.

| | calls | seconds |
| --- | ---: | ---: |
| relative -- loaders with no cache in front of them | 1,361 | **1.90** |
| absolute -- reads that re-entered after a cache declined | 1,005 | **0.95** |

### Loaders nobody cached -- 1.90s

| | seconds |
| --- | ---: |
| `data/shipsystems/*` (666 files) | 0.26 |
| `data/hullmods/hull_mods.csv` | 0.18 |
| `data/strings/descriptions.csv` | 0.16 |
| `data/world/factions/*` (110 files) | 0.15 |
| `data/weapons/weapon_data.csv` | 0.13 |
| `data/hulls/skins/*` (302 files) | 0.12 |
| `data/hulls/ship_data.csv` | 0.10 |
| `data/config/settings.json`, `strings.json`, `ship_names.json` | 0.26 |
| `data/characters/skills/*` (200 files) | 0.06 |
| everything else | 0.67 |

These are the seven untouched loaders named below, and they are the case for **one general cache at
these two methods** rather than a sixth pinned loader. One identity, one artifact, every loader.

(The `data/hulls/skins/*` and `data/shipsystems/*` groups appear on both sides of the split, because
some of those files are read once by a cached loader and again by an uncached one.)

### Fallbacks inside the caches that already exist -- most of 0.95s

895 of those 1,005 absolute-path reads are the calls the four JSON caches could not serve and handed
back to vanilla. The probe's counts agree with what the caches report about themselves, and the two
instruments share no code:

| cache | fallbacks it reports | reads the probe saw |
| --- | ---: | ---: |
| variant JSON | 435 | 435 |
| hull JSON | 200 | 200 |
| weapon JSON | 156 | 155 |
| projectile JSON | 104 | 99 |

That is a sixth independent agreement between the offline model, the caches, and the running game.
**Those calls cost most of 0.95s, near 1ms each**, because each one is a real read with a real
overlay -- roughly ten times what a cache hit costs. Closing the fallback gap is a smaller change
than a new cache, and it is worth a large fraction of a second.

## What the untouched loaders are

Disassembling the seven largest uncached top-level loaders names them, which nothing had done:

| loader | seconds | what it reads |
| --- | ---: | --- |
| `SpecStore.oo0000` | 0.83 | factions: `economy.json`, `factions.csv`, 110 `.faction` files |
| `SpecStore.new.super` | 0.53 | ship systems: `ship_systems.csv` and 666 `.system` files |
| `WeaponSpreadsheetLoader` | 0.53 | `weapon_data.csv` |
| `SpecStore.null` | 0.33 | hull mods: `hull_mods.csv`, plus one script class per mod |
| `SpecStore.float()` | 0.33 | `descriptions.csv` |
| `ShipHullSpreadsheetLoader` | 0.26 | `ship_data.csv` |
| `SpecStore.class` | 0.19 | missions: `mission_list.csv`, per-mission descriptors |

## What SpecStore now actually is

9.75s of top-level loader time, of which merged reading is about 2s. The rest is not reading:

| | seconds |
| --- | ---: |
| rules expression constructor (tokenize, command class, regex) | 1.37 |
| the four JSON caches' own rehydration | 1.50 |
| merged reads, cached and uncached | ~2.0 |
| live object construction and everything else | ~4.9 |

**That 1.50s is not what it looks like, and the correction is the rest of this document.**

## The 1.50s was 0.39s of parsing and 0.95s of reading

`variant-json-merge-parse` costs 508ms across 5,138 hits, which reads as 99µs of `JSONObject`
construction each. Replaying these exact artifacts offline on the game's own JVM and json.jar parses
all 11,689 of them in **0.155s**. Ten times apart, which is the same shape as the texture block: the
label names one thing and measures another.

A `SeamTimer` inside the four caches, around the `newInstance` call and nothing else, settles it:

| | the loader's subphase | rehydration inside it |
| --- | ---: | ---: |
| variant | 417 ms | 76 ms |
| weapon | 268 ms | 74 ms |
| hull | 351 ms | 168 ms |
| projectile | 307 ms | 76 ms |
| **total** | **1,343 ms** | **394 ms** |

1,343 − 394 = **949 ms**, and the merged-read probe independently attributed **953 ms** to cache
fallbacks. Two instruments that share no code, four milliseconds apart. The subphase was never
mostly parsing; it was mostly the calls the cache could not answer.

(The 0.155s offline against 0.394s in the game is a 2.5x gap, which is JIT warm-up and a heap that
already holds the game's data. That is a believable difference. Ten times was not.)

## Why 895 calls could never be answered

Every one of them is a core-game spec, and **a core-game spec reaches the loader as an absolute
path** through the install directory. The caches keyed on `ResourceIndex.normalizeLogicalPath`,
which refuses an absolute path outright; the runtimes caught the exception and returned null. So
those calls missed, and the capture that follows a miss normalized the same path and dropped it too.
They were invisible in both directions, launch after launch, which is why the fallback counts were
identical to the unit across every run ever recorded: 435 variants, 200 hulls, 156 weapons, 104
projectiles.

The fix is not to canonicalize the absolute path onto the relative one. **They are different
requests**: reading `data/variants/wolf.variant` merges every root that provides it, while reading
`<install>/data/variants/wolf.variant` names one file, and folding them together would serve a mod's
overlay where vanilla returned the core file alone. `SpecCacheKey` gives an absolute path its own
key, marked `abs:`, with the install prefix dropped so an artifact survives the install moving.

Dropping the prefix means two absolute paths could in principle claim one key. Nothing observed
does, so rather than trusting that, a repeat capture is compared against what is already held and
the key is refused outright when the two disagree -- a collision that is never served cannot serve
the wrong spec. Zero collisions on this profile.

### Measured

One learning launch captured 435 / 156 / 200 / 104 and published all four artifacts. The launch
after it:

| | before | after |
| --- | ---: | ---: |
| cache fallbacks | 895 | **0** |
| the four merge-parse subphases | 1,343 ms | **591 ms** |
| merged reads, whole launch | 2,366 calls / 2,857 ms | **1,471 calls / 2,142 ms** |
| merged reads on an absolute path | 1,005 calls / 953 ms | 110 calls / 72 ms |

**-752 ms** by the subphase and **-715 ms** by the merged-read probe: the same change, measured
twice, 37 ms apart. Hits went 12,584 -> 13,584 with zero misses, zero collisions, and no change to
what the caches serve for a relative path.

The main menu came up at 34.76s, 35.12s, and 34.52s across the three launches, which says nothing:
run-to-run variance on this profile is about ±1.4s, so a 0.75s change is not visible at that level
and the subphase numbers are the claim.

## What is left of the rehydration idea

394 ms, not 1.50 s. An artifact that stores the shape instead of the text -- capture walks the object
vanilla produced and writes a tagged tree; a hit rebuilds it with `put()` calls, scanning no
characters and inferring no types -- was measured offline against these artifacts at **6.3x**, with
all 11,689 entries round-tripping identically. That is worth roughly 0.33 s of the 0.39 s and needs
a format change. It is now the smallest of the remaining items rather than the largest.

## What this rules out

A sixth pinned loader. The largest single uncached loader is 0.83s and its merged reads are 0.15s of
that; six more bespoke caches would chase 1.90s through six identities, six artifacts, and six
digest profiles. The same 1.90s is reachable from one place.

## Reproduction

```bash
scripts/probe-launch.sh --label merged-reads -- --fast
```

Runs: `merged-reads-20260803-215048` (the survey), `rehydrate-20260803-220721` (the split),
`abs-learn2-20260803-221219` (learning), `abs-warm-20260803-221541` (all four caches complete).
The offline parse comparison is `2026-08-03-rehydrate-benchmark.java.txt`.

`adapter-startup-phases.json` gains a `mergedReads` array, one row per `(kind, group)`, where a
named spreadsheet is its own group and a directory of spec files is one group. The probe is on
whenever `--startup-phase-probe` is, and an installed probe that is off costs one branch.
