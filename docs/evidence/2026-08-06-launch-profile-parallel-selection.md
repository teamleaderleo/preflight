# Launch cache profiles are selected concurrently

**Date:** 2026-08-06

**Runtime:** native arm64 Java 26 launcher on an M5 MacBook Air

**Profile:** Starsector 0.98a-RC8 with 83 enabled mods; 61,695 indexed providers

## Accepted result

Before the game process starts, the native launcher selects nine independent cache contexts from
one immutable launch profile: variant, weapon, projectile, and hull JSON; rules CSV; rule-command
classes; merged reads; Janino bytecode; and prepared audio. They shared memoised provider and
content identities, but the launcher still selected them serially.

Selection now uses at most four daemon workers, adaptively capped by the available processor count.
Every task retains its existing per-cache fail-open behavior, all futures join before the game can
start, and shutdown waits for every cancelled selector before closing the shared identity context.
`-Dpreflight.launch.parallelProfileSelection=false` restores the serial path.

Ten fresh native-JVM pairs alternated serial and parallel `run --fast --direct --dry-run` commands.
Dry-run performs the complete wrapper setup but cannot launch Starsector.

| path | fresh-process observations, seconds | median |
| --- | --- | ---: |
| serial | 1.29, 1.25, 1.24, 1.36, 1.29, 1.35, 1.35, 1.40, 1.34, 1.29 | **1.315** |
| parallel | 1.17, 1.09, 1.14, 1.23, 1.12, 1.19, 1.18, 1.23, 1.28, 1.24 | **1.185** |

The whole native wrapper path improved **130ms / 9.9%** at the median. A final post-verification
pair measured 1.59s serial and 1.23s parallel under ordinary desktop activity; it is a correctness
confirmation, not an addition to the cohort.

Every dependency-profile SHA-256 and selected hit was identical after sorting the intentionally
nondeterministic parallel log order. The final pair also validated the same 2,049 prepared-audio
paths and stopped before process creation. A synthetic seven-corpus test independently compares a
shared concurrent `ProfileIdentityContext` with the serial identities, and the authoritative full
`mvn verify` completed with 1,188 tests and the installed Starsector archive checks green.

This is not a whole-startup timing claim: it removes bounded native wrapper latency before the
existing game-start timestamp. A clean game launch is still required to place it in the startup
cohort, and a browser-contended launch must remain correctness-only evidence.
