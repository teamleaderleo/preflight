# A cached listing answered a different question than `File.exists()`

**Date:** 2026-08-03
**Install:** Starsector 0.98a-RC8, 83 enabled mods, macOS 15, M5 MacBook Air (10 cores), 24 GB
**Applies to:** `resource-probe-cache-v1`, as merged in #307
**Result:** two mods' files stopped resolving on macOS and Windows. Fixed and pinned by a test.

## What happened

`ResourceProbeRuntime` replaces `File.exists()` inside the game's resource resolver with a lookup
in a remembered directory listing. The first version compared names as strings:

```java
names = Set.of(new File(parent).list());
return names.contains(file.getName());
```

`File.exists()` does not compare names as strings. It asks the filesystem, and macOS (APFS,
case-insensitive by default) and Windows (NTFS) both answer yes when the only difference is case.
So `mod/data/strings/ship_names.json` opens a file stored as `ship_names.JSON` on the two platforms
nearly every player uses -- and the cache said it did not exist.

## How it surfaced

Re-running the merged-resolve benchmark against the rewritten resolver, to price what was left of
the 4.27 s merged walk, the two arms disagreed on the total number of sources found:

```
stock resolver       4.323 s   (sink 14977)
probe cache on       3.109 s   (sink 14976)
```

One source, out of 14,977. Dumping per-path source counts and diffing the two arms named it:

```
< 15	data/strings/ship_names.json
---
> 14	data/strings/ship_names.json
```

A scan of every distinct path in both corpora (15,719 paths x 84 roots) against the two rules found
exactly two files the filesystem finds and an exact listing does not:

| path the game asks for | mod | on disk as |
| --- | --- | --- |
| `data/strings/ship_names.json` | Arma Armatura 3.2.5 | `ship_names.JSON` |
| `data/hulls/MPC_spearHead.ship` | What We Left Behind | `MPC_spearhead.ship` |

Both are case-only. Neither would have thrown; the resolver would have walked past the mod's root
and either found the core file instead or reported the resource missing. Silent.

## The fix

Detecting the filesystem's case sensitivity and switching rules on it would work, but it decides
globally something that is a property of each mount, and it has to be decided before the first
directory is remembered. There is a cheaper answer that needs no detection at all.

Each directory is now remembered twice: by exact name, and by a folded key
(`NFC` normalisation, then `toLowerCase(Locale.ROOT)` -- case-insensitive filesystems tend to be
normalisation-insensitive too). Then:

- **exact hit** -> `true`. Right on every filesystem.
- **folded miss** -> `false`. Also right on every filesystem: a case-insensitive disk can only match
  a name that folds equal, so if nothing folds equal there is nothing to match. This is the answer
  for the overwhelming majority of probes, which is why the speed is unchanged.
- **folded hit, exact miss** -> ask `File.exists()`. Whether these are one file is a property of
  this disk, and the disk is the only authority on it. Two probes out of 1.6 million.

## Verification

Both walks now agree with the stock resolver on every path in the corpus, byte for byte:

| walk | distinct paths | result |
| --- | ---: | --- |
| first-match (`C.Ô00000`), content hash per path | 8,378 | identical |
| merged (`C.Ò00000`), source count per path | 15,719 | identical |

and the cost is where it was:

| merged walk | round 1 | round 2 | round 3 |
| --- | ---: | ---: | ---: |
| stock resolver | 4.764 | 4.460 | 4.323 |
| probe cache, exact names only (wrong) | 3.384 | 2.894 | 3.109 |
| **probe cache, with the fold set** | **3.367** | **3.261** | **3.087** |

## What this cost the earlier measurement

Nothing measurable: two files out of 15,719 paths, neither on a hot path. The 87.52 s -> 40.88 s
result stands. What it cost was correctness, on the default configuration of both major platforms,
and it took a benchmark that happened to print a total to notice.

That is the lesson worth keeping: the arm that is supposed to be equivalent should be **diffed
against the arm it replaces**, not just timed against it. The timing was right the whole way
through. The parity check was the thing that had never been run.

## Follow-up: a failed listing is not an empty directory

On 2026-08-04, `combat-reserves-20260804-055653` aborted while loading
`data/missions/ii_test1/descriptor.json`. Interstellar Imperium's merged mission list referenced the
file, the enabled mod root was in the resolver walk, and the file existed on disk with the exact
requested spelling. The probe cache nevertheless skipped every root and vanilla reported the
resource missing. This was not the game's fast-relaunch failure.

The second ambiguity was `File.list()`: it returns `null` both for a directory that does not exist
and for an I/O or permissions failure while listing a real directory. The cache treated either as a
complete empty listing and retained that answer for the launch. Its report consequently claimed
zero failures even though it had made a real file disappear.

Directory snapshots now use the NIO directory-stream API, which distinguishes a proven missing or
non-directory path from other failures. Proven absence remains cacheable. Any I/O, iteration, or
security failure creates an incomplete listing whose every lookup defers to the vanilla resolver.
The report records these as `listingFailures`, and a regression test pins the rule that an
incomplete listing can never claim a child is absent.

## Follow-up: whole-root absence is disabled

The NIO distinction was necessary but insufficient. The next live launch, recorded as
`dialog-grid-20260804-062621`, failed on a different exactly named file:

```
data/missions/randyforrandis/descriptor.json
```

The file existed in the enabled Everybody Loves KoC mod, its `mission_list.csv` was the source of
the request, and the cache reported no listing failures. It nevertheless skipped 17,484 root opens
wholesale and the vanilla loader exhausted the resolver walk. That proves the whole-root shortcut
has another false-negative mode which its telemetry cannot currently distinguish from a real miss.

`resource-probe-cache-v3` therefore never answers the per-root open itself. It always invokes the
original resolver method, whose internal `File.exists()` calls still use the narrower directory
memo. This gives back the path-construction portion of the optimization, but restores the game's
own resource-selection boundary and removes the failure mode entirely. Restoring wholesale skips
requires an exhaustive parity replay of the actual per-root open contract, not another live-launch
guess.
