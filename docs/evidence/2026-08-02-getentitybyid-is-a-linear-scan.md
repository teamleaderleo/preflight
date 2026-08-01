# `getEntityById` is a linear scan, and it is 92% of the campaign tick

**Date:** 2026-08-02
**Install:** Starsector 0.98a-RC8, 77 enabled mods, macOS 15, M5 MacBook Air, no thermal constraint
**Save:** `save_LindseyEulalia_7487418333814238931`, 42.7 MB, Cycle 206
**Recording:** `jcmd <pid> JFR.dump` taken while the game was live, after the save had loaded
**Status:** measured with real execution samples, and the mechanism confirmed from bytecode.
**Superseded in part** -- see the correction below.

> ## Correction, same day
>
> This document identifies the right method and the right profile, and then names the wrong cost.
> It says the expense is the O(1) map lookup validated by an O(n) `List.contains`. **That is only
> the first half of the method.** `getEntityById` also has a *fallback*, reached on any failure of
> the fast path including a plain map miss, which linearly scans every entity in the location and
> allocates two lowercased `String`s per entity -- and `CampaignEngine.getEntityById` runs that
> fallback over hyperspace and then over every star system in turn. A failed lookup scans the whole
> sector. Measured at **1.49 ms** on a 100 MB save.
>
> The `contains` scan described below is real and was measured at 282 ns on hyperspace, but it is
> two orders of magnitude smaller than the fallback it sits in front of. The mechanism, the sizes
> counted from the saves, and the costs are in
> [a failed lookup scans the sector](2026-08-02-a-failed-lookup-scans-the-sector.md).
>
> Cause of the error: the disassembly was read to the first `areturn` rather than to the end of the
> method.

This is the first thing this project has measured that is **not** load time. It was found while
trying to profile a save load, and it is a bigger result than the thing it was looking for.

## The frame

Of 3,163 `main` execution samples in the dump, **557 are inside
`BaseLocation.advanceEvenIfPaused`** — the campaign's per-frame update, which runs whether or not
the game is paused. Within those:

| leaf frame | samples | share of tick |
| --- | ---: | ---: |
| **`com.fs.starfarer.campaign.BaseLocation.getEntityById`** | **511** | **91.7%** |
| `java.lang.StringLatin1.toLowerCase` | 24 | 4.3% |
| `BaseLocation.advanceEvenIfPaused` itself | 11 | 2.0% |
| everything else | 11 | 2.0% |

The call path is the same in 511 of 519 samples:

```
BaseLocation.advanceEvenIfPaused
  -> campaign.rules.Memory.get
    -> campaign.rules.Memory.replaceIdsWithEntities
      -> CampaignEngine.getEntityById
        -> BaseLocation.getEntityById        <-- 92% of the tick
```

## Why it is slow, from the bytecode

`BaseLocation.getEntityById` is not obfuscated, so it disassembles cleanly:

```java
public SectorEntityToken getEntityById(String id) {
    if (idToEntity == null) rebuildIDToEntityMap();
    if (idToEntity.containsKey(id)) {
        if (idToEntity.get(id) instanceof BaseCampaignEntity) {
            BaseCampaignEntity e = (BaseCampaignEntity) idToEntity.get(id);
            if (e.getContainingLocation() != null) {
                if (e.getContainingLocation().getAllEntities().contains(e))   // <-- O(n)
                    return e;
            }
        }
        ...
```

The map lookup is O(1) and then **the result is validated with `List.contains`, a linear scan over
every entity in the containing location.** A star system in a modded late campaign holds a great
many entities, and this runs per lookup, per `Memory.get`, per frame.

Corroboration from the same recording: `java.util.ArrayList.indexOfRange` — the body of
`ArrayList.contains` — appears as its own leaf frame at 1.2% of `main`, and the JIT will have
inlined most of the rest into `getEntityById`, which is why the scan shows up under the caller's
name.

There are also three redundant `Map.get` calls on the same key in the hot path, and a
`String.toLowerCase` beneath the lookup accounting for another 4.3% of the tick.

## Why this matters

**It is not load time.** Every previous result in this repository is about getting to the main menu
or getting a save open. This is the cost of the campaign *running*, and it is paid every frame for
as long as the player is in the campaign map. It is the first measured answer to the "general
snappiness in game" question that has been open since the roadmap gained a runtime axis.

**It scales with system size, not with mod count.** A late-game save with more entities per location
makes the scan longer, which matches the folklore that big saves feel worse to play and not only
slower to load.

**The seam is unusually good.** `BaseLocation` and `getEntityById` are *not* obfuscated — the same
happy accident as `CampaignGameManager`. A wrapper that memoised the membership check, or replaced
the `List.contains` with a set, would be a single-method splice of exactly the kind
`TexturePaddingPlan` already does, on a class whose name is stable across platforms.

## What is not established

- **One recording, one save, one session.** 557 samples is enough to make 91.7% solid *within this
  dump*, and not enough to characterise the game.
- **No wall-clock claim.** Sample share is not seconds. What fraction of a frame this costs, and
  whether it is visible as frame-rate, is unmeasured.
- **The fix is unbuilt and unvalidated.** `getAllEntities().contains(e)` presumably exists to reject
  stale map entries whose entity has since moved or been removed. Any replacement has to preserve
  that rejection, and this document does not establish what depends on it.
- Whether Fast Rendering already addresses this was not checked.

## Note on the save load it was looking for

The same dump contains **192 `main` samples inside `CampaignGameManager`** — the save load itself,
spanning about 9 seconds of wall clock. Those samples are diffuse: the largest leaf frame is
`getEntityById` again at 14.6%, then `StringLatin1.hashCode` at 6.8%, with nothing else above 4%.

Two things follow. The load is **not** dominated by one hot frame, and 192 on-CPU samples across
~9 seconds of wall clock means most of a save load is **not on-CPU at all** — it is file I/O and
native work, consistent with the log attribution that put 63% of it in `LoadingUtils`.

Also visible, and worth owning: three samples land in
`dev.starsector.preflight.agent.AdapterCandidateScorer.scoreMethod`. **Our own agent costs
something during a save load**, as classes it has never seen get loaded and scored.

## Method note: how to get a recording that covers anything

`dumpOnExit` is not enough. Both earlier recordings stopped emitting every event type partway
through — the campaign's `profile-1` covers 33 of its 81 seconds, and the first save-load attempt
covered 101 of 251. The fix that worked is to take the dump from outside the process while it is
still alive:

```
jcmd <pid> JFR.check                       # recordings are numbered
jcmd <pid> JFR.dump name=1 filename=out.jfr
```

`name="Starsector Preflight startup"` does **not** work — `jcmd` splits its arguments on whitespace
and rejects the recording's name. Use the ordinal.

> **Corrected.** "Every `--profile` run has been silently losing its tail" was too strong. The events
> are all present; what breaks is cross-chunk *timestamps*, and only on recordings that rotated.
> This recording is a single chunk, so nothing here is affected. See
> [what the profiler was not telling us](2026-08-02-what-the-profiler-was-not-telling-us.md).
