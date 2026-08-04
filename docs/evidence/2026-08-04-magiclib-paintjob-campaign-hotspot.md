# MagicLib copies and linearly scans its unlocked paintjobs every campaign frame

## Finding

The campaign portion of `campaign-source-hint-v1-20260804-221805` contains 1,055 main-thread
execution samples. MagicLib's paintjob runner is present on 128 of them (**12.13%**), and
`MagicPaintjobManagerKt.isUnlocked` is present on 101 (**9.57%**).

The leaf and inclusive stacks agree on the mechanism:

| frame | samples |
| --- | ---: |
| `LinkedHashMap.keysToArray` | 73 |
| Kotlin `toList` / `toMutableList` | 73 each, inclusive |
| `ArrayList.indexOfRange` | 57 |
| `ArrayList.contains` | 57, inclusive |
| `MagicPaintjobManagerKt.isUnlocked` | 101, inclusive |
| `MagicPaintjobManager.advance` | 128, inclusive |

The source bundled beside MagicLib 1.5.6 explains the shape exactly:

```kotlin
private val unlockedPaintjobsInner = mutableSetOf<String>()

val unlockedPaintjobIds: List<String>
    get() = unlockedPaintjobsInner.toList()

fun MagicPaintjobSpec.isUnlocked() = MagicPaintjobManager.unlockedPaintjobIds.contains(id)
```

`advance` iterates every paintjob on every campaign frame to find newly unlocked entries. Each
`isUnlocked` call first copies the authoritative `LinkedHashSet` into a new `ArrayList`, then scans
that list linearly. Unlock and notification semantics require neither operation: the set already
answers the same membership question directly.

## Recording window

The game log marks save loading at 56.466s, the final load stage at 66.972s, and the return toward
the save screen at roughly 105s. The one-chunk JFR spans the full 107-second process, but event time
inside the Rosetta JVM advances at about 0.401x wall time, an already documented clock property of
this installation. The campaign slice therefore uses JFR timestamps 22:18:45.750 through
22:19:01.000. It contains 1,055 main-thread `jdk.ExecutionSample` events.

The user's simultaneous web browsing makes this run unsuitable for a frame-time or whole-startup
claim. It does not create the repeated allocation/scan call chain, and 101 samples is large enough
to justify testing the exact seam rather than guessing from one or two stacks.

## Preflight adapter

`MagicLibPaintjobPlan` exact-gates
`org/magiclib/paintjobs/MagicPaintjobManagerKt.isUnlocked(MagicPaintjobSpec)` from MagicLib 1.5.6:

- `MagicLib.jar` SHA-256
  `af028fcd67dd537024eab0082d3e78cac8508355dbd5f8731b6c243c60dae0d5`
- target class SHA-256
  `419d8f9c5688c87273332900584b474fa2a9027156e025001375cdba65cc3b4e`
- exact Java 17 class version, method descriptor, copied-list getter, id getter, and
  `List.contains` call count.

The original static method is retained under a private Preflight name. The wrapper asks
`MagicLibPaintjobRuntime`, which resolves the exact private `Set<String>` and public `getId()` once,
then performs `Set.contains`. Resolution or access failure returns a sentinel and invokes the
preserved original method, so reflection drift loses speed rather than behavior.

Offline coverage proves:

- unlocked and locked answers match while using the authoritative set;
- the copied-list getter and `List.contains` survive only in the preserved fallback;
- a deliberately changed private field type delegates and returns the original answer;
- wrong hash, wrong body, and a second rewrite decline;
- the exact installed MagicLib archive transforms with one runtime lookup and one preserved method.

## First live result

`magiclib-paintjob-v1-20260804-225721` loaded the representative campaign and exited normally:

- adapter health `ACTIVE`, 19 transformations, zero declines and zero contained failures;
- exact MagicLib adapter installed;
- 1,316,681 unlocked checks answered from the authoritative set;
- zero delegated checks and zero runtime failures.

The comparable campaign window contains 1,440 main-thread execution samples. The old
`MagicPaintjobManagerKt.isUnlocked`, `LinkedHashMap.keysToArray`, and Kotlin `toList` / `toMutableList`
frames are all absent. `MagicPaintjobManager.advance` fell from 128/1,055 samples (12.13%) to
40/1,440 (2.78%). These are two short interactive recordings rather than a controlled frame-time
benchmark, so the ratio is corroborating profile evidence, not a claimed 4.4x gameplay speedup.

The 40 surviving manager samples exposed another independent list scan in the same source:

```kotlin
private val completedPaintjobIdsThatUserHasBeenNotifiedFor = mutableListOf<String>()

if (paintjob.isUnlocked() &&
    !completedPaintjobIdsThatUserHasBeenNotifiedFor.contains(paintjob.id)) {
    // notify once
}
```

`ArrayList.indexOfRange` is the leaf on 36 of those 40 samples. The manager class has exactly one
clear and two adds to this private list. `MagicLibPaintjobNotificationPlan` exact-gates that class,
replaces only the reviewed `contains` with a set snapshot, and invalidates the snapshot after all
three reviewed mutations. This handles same-size clear-and-repopulate correctly; it does not rely
on a size heuristic. Snapshot failure invokes the original list's `contains`, while target, class,
archive, loader, mutation count, and call shape drift all decline transformation.

## Second live result

`magiclib-notification-v1-20260804-230724` accepted the second seam with process exit 0 and adapter
health `ACTIVE`: 20 exact transformations, zero declines, and zero contained failures. Runtime
telemetry reported:

- 1,312,748 unlocked-set checks, zero delegation/failure;
- 1,312,748 already-notified checks, zero delegation/failure;
- 438 reviewed notification-list mutations;
- one set-snapshot rebuild for the whole session.

The campaign window contains 1,500 main-thread execution samples. The already-notified
`ArrayList.contains` stack is absent, while the original set-copy stack remains absent.
`MagicPaintjobManager.advance` is present on nine samples (0.60%), down from 40/1,440 (2.78%) after
the first seam and 128/1,055 (12.13%) before either seam. The nine surviving samples are primarily
MagicLib rebuilding `getPaintjobs()`' filtered set; two `ArrayList.contains` leaves are
`MagicPaintjobSpec.isShiny`, not the optimized notification list.

Again, these differently sized interactive windows establish mechanism removal and identify what is
left. They are not a controlled frame-time benchmark and do not support multiplying the sample
ratios into a user-facing speedup claim.
