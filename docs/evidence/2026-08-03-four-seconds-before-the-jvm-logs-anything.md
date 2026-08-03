# Four seconds happen before the JVM logs anything

**Date:** 2026-08-03
**Install:** Starsector 0.98a-RC8, 83 enabled mods, macOS 26.5, M5 MacBook Air (10 cores), 24 GB
**Run:** `~/.starsector-preflight/runs/abs-warm-20260803-221541`
**Status:** measured, not yet addressed

## The gap between what is reported and what is felt

Every number this project reports is "game log start to main menu". On the reviewed run that is
**34.52 s**. The wrapper process ran for **40.07 s**.

| | seconds |
| --- | ---: |
| wrapper start to the game's first log line | **4.15** |
| the game's own launcher preamble | 0.41 |
| game log start to main menu | 34.52 |
| after the menu (detector quiet period, cleanup) | ~1.0 |

**The headline number has never included the first four seconds**, and a user waits for all of them.

## What is in those four seconds

Timing the same command with `--dry-run`, which does every piece of preparation and then does not
launch, twice: **2.17 s and 2.07 s**. So Preflight owns about half the window and the game's own JVM
boot owns the rest.

Preflight's half, from the run's own report:

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
| **measured work** | **1,364.4** |

The rest is the CLI's own JVM starting and the mod profile scan.

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

## Reproduction

```bash
java -jar preflight-cli/target/preflight.jar run --game /Applications/Starsector.app \
  --launcher "$(java -jar preflight-cli/target/preflight.jar doctor --game /Applications/Starsector.app | awk '/^Selected: /{print substr($0, 11); exit}')" \
  --direct --adapter --startup-phase-probe --no-record --fast --dry-run
```

The four-second window is `run.json`'s `started` against `menu.json`'s `gameStartInstant`, minus
`gameStartLogMillis`.
