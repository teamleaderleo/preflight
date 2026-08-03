# Four seconds happen before the JVM logs anything

**Date:** 2026-08-03
**Install:** Starsector 0.98a-RC8, 83 enabled mods, macOS 26.5, M5 MacBook Air (10 cores), 24 GB
**Run:** `~/.starsector-preflight/runs/abs-warm-20260803-221541`
**Status:** measured, not yet addressed

## The gap between what is reported and what is felt

Every number this project reports is "game log start to main menu". On the reviewed run that is
**34.52 s**. The wrapper process ran for **40.07 s**, and Preflight's own preparation happened before
even that clock started: `run.json`'s `started` is captured immediately before the child process is
spawned, so **every second of preparation is outside every number the harness records.**

| | seconds |
| --- | ---: |
| Preflight preparation, before the game is spawned | **3.02** |
| spawn to the game's first log line (JVM boot) | 4.15 |
| the game's own launcher preamble | 0.41 |
| game log start to main menu -- *the reported number* | 34.52 |
| after the menu | ~1.0 |

A user waits for all of it.

## What is in the preparation

Timing the same command with `--dry-run`, which does every piece of preparation up to the launch,
twice: **2.17 s and 2.07 s**. The profile census that runs after the plan is printed adds a measured
**854 ms** on top of that.

Preflight's preparation, measured in place:

| | ms |
| --- | ---: |
| build the current-profile resource index | **966.7** |
| read the launch profile (61,693 providers) | 117.9 |
| variant JSON identity | 122.8 |
| weapon JSON identity | 58.1 |
| hull JSON identity | 49.3 |
| projectile JSON identity | 28.2 |
| rule command class identity | 16.7 |
| rules CSV identity | 4.7 |
| **measured, before the launch plan is printed** | **1,364.4** |
| validate the index that was just built, against the same files | **513.0** |
| walk the whole profile again for `profile.json` | **854.0** |
| **measured total** | **2,731.4** |

The rest is the CLI's own JVM starting.

Three of those rows are the same walk. `ResourceIndexBuilder.build` enumerates all 61,693 providers
with a `toRealPath` and a `readAttributes` each; `ResourceIndexValidator.validate` then asks the
filesystem the same questions about the same files; and `ProfileCensus.scan` walks the tree a third
time to write a report nothing in the launch reads.

## Why this is the cheapest second on the board

Not one of those answers is needed when it is computed. They are handed to the agent as launch
arguments, and the first of them is read when a cache configures at premain -- but the first *use*
is the variant loader, inside `SpecStore`, which begins after `resource-init-enter` at **4.19 s into
the game's own run**. So every one of these digests is produced between four and eight seconds before
anything asks for it, serially, on a machine with ten idle cores.

Overlapping them with the game's JVM boot would take roughly **1.4 s off what a user waits**, and it
requires no game bytecode, no new artifact, and no equivalence argument -- the work is unchanged and
only its schedule moves. Compare that with the general merged-read cache, which is worth 1.90 s and
needs a new artifact format, a new identity, and a capture/publish path.

## The one thing that decides the design

The work cannot simply move into the agent. The CLI runs on a native arm64 JVM; the agent runs
inside the game, which is x86_64 under Rosetta, where SHA-256 costs **10x**
([the Rosetta table](2026-08-03-the-texture-block-was-not-the-graphics-driver.md)). Hashing 12,797
files is 293 ms on the CLI's JVM and would be seconds inside the game's.

So the shape has to be: start the game process first, keep the hashing in the CLI's own JVM, and
have the agent's caches wait for the answer at first use rather than at premain. A cache that does
not have its artifact by then must fail open to vanilla, which is what every one of them already
does when an identity misses.

## What was done

| | before | after |
| --- | ---: | ---: |
| `--dry-run`, everything up to the launch | 2.17 s / 2.07 s | **1.33 / 1.18 / 1.19 s** |
| profile census, on the critical path | 854 ms | **beside the game** |
| **preparation before the game is spawned** | **3.02 s** | **1.19 s** |

Three changes, none of which computes anything different:

1. **The root walk runs the roots at once.** Each root is walked into its own scan, recording the
   exact bytes the fingerprint digest would have been fed rather than digesting them; the scans are
   then folded in root order, so the digest still sees one root after another in the original order
   and provider lists still append in resolution order. Every identity on the reviewed profile is
   byte-identical afterwards -- `a4a4d144…` for the texture profile and all six spec-store digests --
   which is the strongest available evidence, because a single reordered byte anywhere in the walk
   would change that fingerprint and orphan 5 GB of prepared blobs rather than fail visibly. The test
   compares an 8-worker build against a 1-worker build rather than a recorded constant.
2. **The second validation pass is gone.** `CurrentTextureCache` builds the current index, compares
   it to the stored one, and then used to validate the stored one against disk. The comparison
   already proves they hold the same roots and the same providers -- same relative path, same size,
   same modification time, same order -- so a file that vanished, changed, was touched, stopped being
   a regular file, or escaped its root cannot survive it. `ResourceIndexValidator` is untouched and
   still used where there is no freshly built index to compare against.
3. **The census runs beside the game.** Nothing in the launch reads `profile.json`. It is written on
   a daemon thread and collected when the run is written up; a census that fails or does not finish
   costs the run its report and nothing else.

## Reproduction

```bash
java -jar preflight-cli/target/preflight.jar run --game /Applications/Starsector.app \
  --launcher "$(java -jar preflight-cli/target/preflight.jar doctor --game /Applications/Starsector.app | awk '/^Selected: /{print substr($0, 11); exit}')" \
  --direct --adapter --startup-phase-probe --no-record --fast --dry-run
```

The four-second window is `run.json`'s `started` against `menu.json`'s `gameStartInstant`, minus
`gameStartLogMillis`.
