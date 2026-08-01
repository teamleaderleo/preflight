# A failed `getEntityById` scans the whole sector, and costs 1.5 ms

**Date:** 2026-08-02
**Install:** Starsector 0.98a-RC8, 77 enabled mods, macOS 15, M5 MacBook Air, no thermal constraint
**Saves measured:** `save_LindseyEulalia_7487418333814238931` (42.7 MB, live) and
`save_ThemisMorse_9156901202859789974` (100 MB, an old late-game save from ~/Downloads)
**Benchmarks:** run on **the game's own JVM** -- `/Applications/Starsector.app/Contents/Home/bin/java`,
Zulu 17.0.10 **x86_64**, i.e. under Rosetta, the same way the game runs
**Status:** mechanism read from bytecode, sizes counted out of the players' own saves, costs measured.

[The previous document](2026-08-02-getentitybyid-is-a-linear-scan.md) said the cost was an O(1) map
lookup validated by an O(n) `List.contains`. **That was the first half of the method.** The second
half is worse, and it is where the time actually goes.

## What the method really does

`BaseLocation.getEntityById` has a fast path *and a fallback*, and the fallback is reached on any
failure of the fast path -- **including a plain map miss**:

```java
public SectorEntityToken getEntityById(String id) {
    if (idToEntity == null) rebuildIDToEntityMap();          // (2) allocates, O(n)
    if (idToEntity.containsKey(id)) {
        ... if (e.getContainingLocation().getAllEntities().contains(e)) return e;   // (1) O(n)
    }
    for (SectorEntityToken e : getObjects().getList(SectorEntityToken.class))       // (3) O(n)
        if (e.getId() != null && e.getId().toLowerCase().equals(id.toLowerCase()))  //     2 allocs
            return e;
    return null;
}
```

Three separate linear costs, and the last one allocates two lowercased `String`s **per entity per
lookup**.

`CampaignEngine.getEntityById` is the same shape and then, on failure, walks outward:

```
CampaignEngine.getEntityById(id)
  -> sector-wide idToEntity + O(n) contains
  -> hyperspace.getEntityById(id)                    full scan of hyperspace on a miss
  -> for each of ~150-180 star systems:              full scan of each on a miss
       location.getEntityById(id)
```

### Both maps are invalidated wrongly, in opposite directions

- **`CampaignEngine.idToEntity` is nulled only in the constructor.** The only other write is
  `rebuildIDToEntityMap`, which runs once when the field is null. Nothing invalidates it when an
  entity is created. **Every entity that comes into existence after the first lookup of a session is
  permanently absent from the sector map** -- every fleet Nexerelin spawns, every derelict, every
  jump-point object added by a mod. Each lookup of one takes the full outward walk, forever.
- **`BaseLocation.idToEntity` is nulled by `BaseLocation.advance` every single frame.** So the
  per-location map is thrown away and rebuilt from scratch on the first lookup of each frame -- a
  fresh `HashMap` and one `put` per entity, per location, per frame.

One is never invalidated when it should be; the other is invalidated every frame when it needn't be.

## The shape of the data, from the saves themselves

Counted by streaming `campaign.xml` and counting the direct children of each `<saved>` block -- the
serialised `ObjectRepository` that backs each location.

| | live save (42.7 MB) | old save (100 MB) |
| --- | ---: | ---: |
| locations | 286 | 293 |
| entities across all locations | 15,903 | **71,216** |
| hyperspace | 3,023 | 2,394 |
| median location | 33 | **185** |
| 95th percentile location | 101 | 681 |

The late save has **4.5x the entities** and a **5.6x larger median location**. The location count
barely moves; the locations get fat. That is the axis the scan is linear in.

## What it costs

Sector rebuilt from those exact size distributions, one frame boundary per frame (so the
per-location rebuild is paid), on the game's own Rosetta JVM. Nanoseconds per lookup:

| case | live save | old save | with a per-location index | with a sector index |
| --- | ---: | ---: | ---: | ---: |
| **A** id in the sector map, still valid | 0.6 us | 0.1 us | ~same | ~same |
| **B** entity exists, created after the map was built | **205 us** | **1,024 us** | 1.9-3.8 us | 0.10-0.29 us |
| **C** no such entity anywhere | **301 us** | **1,486 us** | 3.8-5.8 us | 0.26 us |

**A single failed lookup on the old save costs 1.49 ms.** A 60 fps frame is 16.7 ms. One of these is
9% of the frame; the profile shows them happening in bursts.

Case A is the path everyone assumes is being taken, and it is genuinely cheap. Cases B and C are the
ones the profile is full of, and they are 1,000-15,000x more expensive than A.

### The `contains` on its own

Isolating just the fast path's validation scan, across list sizes (same JVM):

| entities in the location | vanilla `contains` | via the repository's HashSet |
| ---: | ---: | ---: |
| 33 | 11.2 ns | 4.1 ns |
| 213 | 31.3 ns | 4.9 ns |
| 1,000 | 102.1 ns | 6.3 ns |
| 3,023 (hyperspace) | 282.5 ns | 7.3 ns |
| 20,000 | 1,765.8 ns | 8.5 ns |

Textbook linear, ~76 ps per element. Real, but two orders of magnitude smaller than the fallback.

## The fix is already in the codebase, and it is Fractal's

`ObjectRepository` -- the thing behind `getAllEntities()` -- **already carries a `HashSet` called
`forFastContains` with a public `contains(Object)` that answers the same question in O(1)**:

```java
public boolean contains(Object o) { return forFastContains.contains(o); }
```

`add` puts into the set and into the classified lists together; `remove` returns early unless
`contains(o)`, then removes from the set and from every classified list in the same pass. The set
and the lists cannot drift as long as mutation goes through the repository. Neither
`BaseCampaignEntity` nor its supertypes override `equals` or `hashCode`, so the set is an identity
set and the answers are exactly identical.

So `getAllEntities().contains(e)` could be `getObjects().contains(e)` -- same semantics, O(1),
using a field the game already maintains. The fast answer is sitting on the object being asked.

The fallback scan needs one addition rather than a substitution: it is a **case-insensitive** match,
so replacing it means a `Map<lowercasedId, entity>` maintained alongside `idToEntity`. That is the
"per-location index" column above.

## What is not established

- **The benchmark is a model, not the game.** It reproduces the method's control flow, the
  per-frame invalidation, and the measured location sizes, with synthetic ids of uniform length.
  Real ids vary, and `toLowerCase` scales with length; the direction of that error is unknown.
- **Location sizes are repository sizes, not entity-list sizes.** `getAllEntities()` returns the
  subset classified as `SectorEntityToken`. Most objects in a location are entities
  (planets, jump points, terrain, asteroids, fleets), so the over-count is modest, but it is an
  over-count.
- **The mix of cases A, B and C in real play is unmeasured.** The profile establishes that
  `getEntityById` is where the tick's time goes -- 547 of 3,163 `main` samples, 17.3%, and 518 of
  the 549 samples with `Memory.replaceIdsWithEntities` on the stack -- but not the ratio of hits to
  misses that produced it.
- **Whether a mod already patches this was not checked**, and neither was Fast Rendering.
- **Nothing is built.** No splice exists for any of this yet.

## Method note

Full stack depth matters. `jfr print` truncates stacks to 5 frames by default, which is why an
earlier pass at this recording attributed nothing to `replaceIdsWithEntities`:

```
jfr print --stack-depth 64 --events ExecutionSample out.jfr
```

Benchmarks belong on the game's own JVM. `/Applications/Starsector.app/Contents/Home/bin/java` is
Zulu 17.0.10 **x86_64** and runs under Rosetta; the system JDK is aarch64 and reports the same
lookups 10-15% faster, which would have quietly flattered every number here.
