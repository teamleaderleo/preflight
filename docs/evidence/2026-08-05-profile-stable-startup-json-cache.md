# Profile-stable startup JSON cache

Date: 2026-08-05

Profile: 83 enabled mods, macOS on Apple M5, bundled x86-64 Zulu 17 under Rosetta, `--fast`

Result: five accepted warm launches at 29.25--30.16 seconds, median 29.61 seconds

## Finding the remaining startup seam

An opt-in, exact-class startup callback probe separated the broad resource-initialization span from
the callbacks inside it. The first recording put resource initialization at 30.879 seconds and
identified GraphicsLib at 3.90 seconds and AshLib at 2.17 seconds. A second exact call-site probe
localized the work further:

- GraphicsLib `autoGenMissingNormalMaps`: 3.58 seconds.
- Its compact replay: 1.92 seconds across 9,336 calls.
- `autoGenNormalMap`: 1.79 seconds across 6,184 calls.
- The false traversal below it: 1.61 seconds.
- Traversal JSON loading: 1.32 seconds across 5,802 calls; the actual map lookup was only 70ms.
- AshLib render-info JSON loading: 1.86 seconds across 27,294 calls; its variant scan was only
  150--170ms.

These are exact call-site timers, not gaps between log messages. The targets are enabled only by the
startup phase probe and require the installed class and archive fingerprints. Normal `--fast`
launches retain the already-reviewed GraphicsLib replacement unchanged.

An AshLib-local first-level memo was tested first. It served 23,583 of 28,074 calls locally with no
failure, but did not reduce its callback time and was removed rather than shipped.

## Change

The existing persistent single-JSON tree cache was safe but became eligible only at resource-init
completion. That was after the AshLib and GraphicsLib callbacks which needed it most. The cache now
becomes eligible at `ResourceLoaderState.init` entry, when the enabled resource roots and full
profile identity are already fixed.

The safety boundary is unchanged:

- Entries are keyed by the full data-profile identity, including the ordered providers and their
  content hashes.
- Calls carrying either one-shot resolver restriction still bypass the memo and reach vanilla, so
  the resolver consumes its state. The two private checks are now prelinked `MethodHandle`s rather
  than reflective calls on every JSON read.
- Dedicated spec-cache paths remain excluded from this layer.
- Values are persisted as tagged JSON trees and become the ordinary per-process memo value after
  restore, preserving the same sharing behavior as a vanilla parse followed by the memo.
- Missing, corrupt, colliding, unkeyable, or unstorable entries fall through to vanilla.
- New entries publish only after completed startup and again on orderly shutdown.

The first learning launch captured 6,799 of 7,731 eligible misses with zero unstorable values or key
collisions and grew the exact-profile artifact to 9,021 entries (about 15MB). The following warm
probe served 7,356 prepared single-JSON hits with 932 misses and zero failure, capture, write, or
collision. Across the entire merged cache it reconstructed 8,826 tagged trees in 209ms. The mod
callback span fell from 9.45 to 6.07 seconds; AshLib fell from 2.50 to 0.55 seconds and GraphicsLib
from 3.93 to 2.49 seconds. GraphicsLib traversal JSON fell from 1.47 to 0.10 seconds and its false
traversal from 1.75 to 0.34 seconds.

## Controlled launches

The immediately preceding quiet-machine `--fast` pair is retained at
`~/.starsector-preflight/benchmarks/20260805-121826`:

| run | startup |
| --- | ---: |
| fast-1 | 33.22s |
| fast-2 | 32.98s |
| **median** | **33.10s** |

After the learning launch, the five-run warm cohort is retained at
`~/.starsector-preflight/benchmarks/20260805-124523`:

| run | startup |
| --- | ---: |
| fast-1 | 29.30s |
| fast-2 | 29.61s |
| fast-3 | 29.25s |
| fast-4 | 30.16s |
| fast-5 | 29.92s |
| **median** | **29.61s** |

All five warm samples were individually accepted, with a 0.91-second range. A representative run
served 7,356 prepared single-JSON hits, 8,825 total merged-tree hits in 217ms of reconstruction,
2,049/2,050 prepared audio decodes, and 228/228 Janino calls. It applied 30 exact transformations
with zero decline or contained failure. The benchmark summary's campaign-wide acceptance boolean
is false only because this campaign contains one condition and therefore has no comparison pair;
none of the five runs was rejected.

This is a consecutive same-machine cohort rather than shuffled A/B conditions, so the roughly
3.5-second median movement should not be assigned more precision than the retained evidence allows.
The five-sample 29.25--30.16-second cluster, exact callback deltas, cache hit counters, and absence of
failures make the startup result materially stronger than a single lucky launch.

## Next startup target

GraphicsLib's remaining `autoGenNormalMap` path is about 1.7--1.8 seconds across 6,184 calls. The
probe shows JSON acquisition is now cheap, so the next investigation should isolate its normal-map
lookup/generation work and preserve all image and invalidation semantics. The broader core-spec
phase remains roughly six seconds and is the other large startup frontier.
