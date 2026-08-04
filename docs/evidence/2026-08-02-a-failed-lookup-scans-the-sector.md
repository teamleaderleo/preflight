# A failed `getEntityById` scans the whole sector, and costs 1.5 ms

**Date:** 2026-08-02
**Install:** Starsector 0.98a-RC8, 77 enabled mods, macOS 15, M5 MacBook Air, no thermal constraint
**Saves measured:** `save_LindseyEulalia_7487418333814238931` (42.7 MB, live) and
`save_ThemisMorse_9156901202859789974` (100 MB, an old late-game save from ~/Downloads)
**Benchmarks:** run on **the game's own JVM** -- `/Applications/Starsector.app/Contents/Home/bin/java`,
Zulu 17.0.10 **x86_64**, i.e. under Rosetta, the same way the game runs
**Status:** mechanism read from bytecode, sizes counted out of the players' own saves, costs measured;
first live adapter pilot completed 2026-08-04; mutation-tracked v3 verified offline against the
installed 0.98a-RC8 archives and installed cleanly in a controlled menu probe. Campaign activity
then exposed a second exact repository allocation; the two-allocation fix is offline verified and
awaits one live save recheck.

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

## What the first live pilot added

The 2026-08-04 combined gameplay pilot reached a campaign and combat, exited cleanly, and recorded
14 applied transformations with no declines, failures, or corruption. The first positive-only
entity index served 11,886 lookups but declined 228,053 misses. The recording put 474 main-thread
leaf samples in the preserved `BaseLocation.getEntityById`, 11.2% of all main-thread execution
samples. Of the 499 stacks containing that fallback, 472 came from:

```
Memory.get
  -> Memory.replaceIdsWithEntities
    -> CampaignEngine.getEntityById
      -> BaseLocation.getEntityById
```

Those samples form one contiguous campaign-entry window: about 16.1 real seconds after correcting
the Starsector JVM's 0.401x JFR clock. `Memory` replaces saved `enRef_...` strings on first access and
then sets its `restored` flag, explaining the observed "slow at first, then gets better" campaign
map. This is platform-independent single-threaded work; Rosetta can magnify it but does not create
it.

The follow-up implementation returns a negative answer only after comparing the live entity
sequence and every live id with the index snapshot. This deliberately retains O(n) validation for
misses, but replaces the shipped fallback's repeated locale lowercasing and allocation. It also
repairs two correctness gaps in the first pilot: same-size `List.set` replacement and entity-id
mutation now invalidate the snapshot, and exact candidates use the shipped containing-location
validity split instead of repository membership. A small development microbenchmark measured the
new missing path at roughly 0.63-0.66x the shipped fallback's time.

## What the second live pilot added

The follow-up pilot served 7,679 positive answers and 229,789 snapshot-proven negative answers. It
delegated 8,888 calls while taking 1,002 snapshots and indexing 185,401 entities in total. All 15
reviewed transformations applied, with no decline, contained failure, or health mismatch.

Using the same JFR execution/native event scan, the first pilot had 500 sampled events whose stacks
contained `BaseLocation.getEntityById`. The follow-up had 20 such events plus 40 containing
`EntityLookupRuntime`: 60 combined. The runs had different lengths and user actions, so this is not
wall-clock attribution, but an 88% stack-sample reduction is much larger than the recording-length
difference and confirms that the cache removed the observed campaign-entry concentration.

## Version 3 removes the validation scan too

The second pilot also exposed the remaining multiplier: 225,061 answers still compared every live
entity and reflectively fetched every live id before using the index. That was necessary for direct
same-size list edits and `setId()` calls, but it meant the cache was still linear.

Version 3 exact-gates three cooperating transforms. `ObjectRepository.getList(Class)` retains an
ordinary `ArrayList` for every classification except `SectorEntityToken`; that one receives an
ArrayList-compatible mutation generation covering direct list, iterator, and sub-list edits as well
as the repository's own add/remove path. The reviewed `BaseCampaignEntity.setId(String)` advances a
separate id generation. `BaseLocation.getEntityById` accepts a cached answer only when both
generations still match. Any generation change rebuilds before answering. A custom entity that
overrides the reviewed setter is detected while building the index and retains the complete
identity/id validation path, so unknown mod behavior loses speed rather than correctness. The gate
does not enable unless all three exact transformations install.

A five-round development microbenchmark on Starsector's own JVM performed 100,000 repeated missing
lookups through a 185-entity location, the measured late-save median:

| validation | range | relative |
| --- | ---: | ---: |
| v2 identity/id snapshot | 59.117–62.196ms | 1.0x |
| v3 mutation generations | **1.524–1.662ms** | **36.5–40.8x faster** |

Focused answer-equivalence and mutation tests, exact installed-archive transforms for all three
classes, and full `mvn verify` pass. Loading a campaign is still required to confirm that the live
entity population takes the fast generation path and to count actual rebuilds, list/id mutations,
custom setter fallbacks, and eliminated reference validations.

The controlled warm probe at commit `1452d42` reached the main menu in **31.90s**, stopped through
the JVM shutdown hooks, and reported adapter health ACTIVE with all 16 exact transformations applied
and zero declines or contained failures. Campaign index v3 reported `installed=true` and
`enabled=true`. Its activity counters were correctly zero because no save was loaded, so this proves
the live bytecode gate but not yet the live campaign population's validation path. Retained run:
`~/.starsector-preflight/runs/controlled-warm-v3-20260804-215553`.

The first save-and-combat pilot then caught a missing creation seam instead of falsely reporting a
win. It answered 8,358 hits and 219,447 misses correctly, but all 227,805 validations were deep and
walked 79,131,653 references; `fastValidations=0`. The repository transform had installed, yet its
tracked-list factory only replaced the allocation in `ObjectRepository.getList(Class)`. Exact
installed bytecode shows `ObjectRepository.add(Object)` has a second classified-list allocation.
Save deserialization reconstructs a repository through `add`, so the `SectorEntityToken` list was
already a vanilla `ArrayList` when `getList` returned it. This is why offline factory tests passed
while a loaded save remained on the safe v2 path.

The follow-up exact-hash transform now replaces both allocations. Its installed-archive test asserts
one factory call in each method, and telemetry separately reports tracked lists created and
untracked-list validations so this cannot silently recur. The original campaign pilot itself was
otherwise clean: exit 0, adapter health ACTIVE, 18 exact transforms, zero declines or contained
failures. Retained run:
`~/.starsector-preflight/runs/campaign-combat-v3-20260804-215818`.

## What is not established

- **The benchmark is a model, not the game.** It reproduces the method's control flow, the
  per-frame invalidation, and the measured location sizes, with synthetic ids of uniform length.
  Real ids vary, and `toLowerCase` scales with length; the direction of that error is unknown.
- **Location sizes are repository sizes, not entity-list sizes.** `getAllEntities()` returns the
  subset classified as `SectorEntityToken`. Most objects in a location are entities
  (planets, jump points, terrain, asteroids, fleets), so the over-count is modest, but it is an
  over-count.
- **The exact hit/miss split inside shipped lookups remains unmeasured.** The first wrapper observed
  11,886 indexed hits and 228,053 index misses, but a miss meant delegation; it did not record
  whether the preserved method subsequently found a case-folded entity.
- **Whether a mod already patches this was not checked**, and neither was Fast Rendering.
- **A live pilot establishes activation and a strong sample-count reduction, not exact saved
  wall-clock time.** Unit tests cover direct same-size replacement, id mutation, duplicate
  precedence, gate-off delegation, and fail-open behavior; longer beta use remains the compatibility
  check for mod behavior not represented in those tests.

## Method note

Full stack depth matters. `jfr print` truncates stacks to 5 frames by default, which is why an
earlier pass at this recording attributed nothing to `replaceIdsWithEntities`:

```
jfr print --stack-depth 64 --events ExecutionSample out.jfr
```

Benchmarks belong on the game's own JVM. `/Applications/Starsector.app/Contents/Home/bin/java` is
Zulu 17.0.10 **x86_64** and runs under Rosetta; the system JDK is aarch64 and reports the same
lookups 10-15% faster, which would have quietly flattered every number here.
