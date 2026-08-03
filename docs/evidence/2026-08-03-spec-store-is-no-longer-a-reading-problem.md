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

**The 1.50s is the surprise.** `variant-json-merge-parse` still costs 508ms across 5,138 *hits* --
99µs each, spent rebuilding a fresh `JSONObject` from stored text because the value handed to the
game has to be one it may mutate. The caches removed the reading and kept the parsing. A
representation that rehydrates cheaper is worth up to 1.5s and touches no game behaviour, which
makes it the least risky item on this list.

## What this rules out

A sixth pinned loader. The largest single uncached loader is 0.83s and its merged reads are 0.15s of
that; six more bespoke caches would chase 1.90s through six identities, six artifacts, and six
digest profiles. The same 1.90s is reachable from one place.

## Reproduction

```bash
scripts/probe-launch.sh --label merged-reads -- --fast
```

`adapter-startup-phases.json` gains a `mergedReads` array, one row per `(kind, group)`, where a
named spreadsheet is its own group and a directory of spec files is one group. The probe is on
whenever `--startup-phase-probe` is, and an installed probe that is off costs one branch.
