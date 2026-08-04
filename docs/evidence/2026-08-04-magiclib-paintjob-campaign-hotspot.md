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

A live campaign pilot is still required to prove the installed mod loader/source gate, zero
delegation, and removal of the sampled allocation/scan chain. No percentage speedup is claimed yet.
