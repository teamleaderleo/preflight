# The merged-read cache removes 1.87 seconds at its seam

**Profile:** 83 mods, macOS M5 MacBook Air, `--direct --fast`

**Code:** `perf/merged-read-cache`, working tree based on `166530b`

**Result:** two clean warm launches; the cache seam improved **1,871.5ms** and whole launch time
improved **1.314s** on the paired averages.

## Launch result

Game-log start to main menu:

| condition | run 1 | run 2 | mean |
| --- | ---: | ---: | ---: |
| before merged-read cache | 34.660s | 35.538s | 35.099s |
| warm merged-read cache | 33.423s | 34.147s | 33.785s |
| difference | | | **-1.314s** |

The before runs are `prejvm-20260803-223311` and `prejvm2-20260803-223702`. The warm runs are
`merged-cache-warm1-20260804-000041` and `merged-cache-warm2-20260804-000140`.

This clears the launch noise as a pair, but it does not yet meet the project's 33.0s target. The
faster warm run is 33.423s and the pair's mean is 33.785s.

## Direct measurement

Whole-launch time includes unrelated scheduler movement. The independent timers around the cache
show where its original ~1.9s estimate went:

| work | before mean | warm mean | difference |
| --- | ---: | ---: | ---: |
| vanilla merged reads | 2,171.5ms | 98.5ms | -2,073.0ms |
| tagged-tree rehydration | 0 | 201.5ms | +201.5ms |
| **merged-read seam total** | **2,171.5ms** | **300.0ms** | **-1,871.5ms** |
| SpecStore phase | 9,375.0ms | 7,959.5ms | **-1,415.5ms** |

The warm probe reports only two vanilla calls because cache hits never enter the renamed vanilla
method. Those two calls are the deliberately uncached `data/config/settings.json` reads. The
rehydration number comes from `SeamTimer.rehydrateInsideMillis`, not the 23-second span between the
first and last scattered hit.

## The learning gate found one real lifecycle dependency

The first exploratory launch, `merged-cache-learn-20260803-235704`, found one collision. The game
reads `data/config/settings.json` before mod resource roots are registered and reads it again after
registration. Both calls have the same LoadingUtils path and merge-key set, but the second call
includes all mod overlays and therefore produces a different value. The log proves the first call at
315ms, the second at 509ms, and the mod-root reads immediately after the second.

A request-only key cannot represent that external lifecycle state. `MergedReadKey` now refuses the
relative settings path, leaving both calls vanilla. The original 8.0MB artifact was quarantined in
the exploratory run directory rather than deleted.

The corrected fresh learning run, `merged-cache-relearn-20260803-235943`, reached menu in 34.074s and
reported:

```text
captures=1469  misses=1469  unkeyedReads=2  unstorableReads=0
writes=1       keyCollisions=0
```

Both warm runs reported the same functional result:

```text
preparedEntries=1468  hits=1469  misses=0  captures=0
unkeyedReads=2        unstorableReads=0  writes=0  keyCollisions=0
```

There are 1,468 entries because one safe request occurs twice with an identical value; both calls hit
the same stored entry.

## Storage and cleanup

The artifact is 8.0MB at
`~/.starsector-preflight/cache/spec-store/merged-reads/8f57ff075ae38b02bb312b1d6bd7b97fe1a5bda93fc50e84254743b35ae2c447.spmr`.
At measurement time the whole cache was 6.4GB, the whole spec store 33MB, resource indexes 15MB, and
manifests 15MB. The merged-read artifact is therefore 0.125% of total cache storage.

Every probe-launch helper stopped the game after detecting the menu, and no Starsector JVM remained.
The exact command for each launch was:

```sh
scripts/probe-launch.sh --label <label> -- --fast
```
