# On-demand heap commitment candidate (2026-08-22)

Starsector's current launchers set `-Xms` equal to `-Xmx` and request
`-XX:+AlwaysPreTouch`. HotSpot therefore writes to every page in the configured heap before the
game can start. On the reviewed installation that means touching 6 GB before `main()`.

The earlier isolated JVM measurement found roughly 950 ms of pre-touch work at 6 GB. A fresh
three-round check on the same bundled x86-64 Zulu 17 runtime, while the machine was in normal use,
found:

| round | eager | on demand |
| ---: | ---: | ---: |
| 1 | 0.60 s | 0.04 s |
| 2 | 0.59 s | 0.04 s |
| 3 | 0.58 s | 0.04 s |

The command in each case was the bundled JVM with `-Xms6144m -Xmx6144m`, the selected pre-touch
flag, and `-version`. This isolates JVM heap initialization from game and mod loading. The current
removed phase is about 0.55 seconds on this machine.

Two adjacent full launches from checkout `0ec6d394600297ce51c2f20e265d5eeabedc4361` and JAR
SHA-256 `8528ddd318f42e5c25ae73566aa555ed9a938b685d4124e20833a4393dd4399d` reached the main menu and
stopped cleanly:

| policy | run | wrapper lifecycle | game-log start to menu |
| --- | --- | ---: | ---: |
| on demand | `heap-on-demand-candidate-20260822-20260822-202630` | 25.914 s | 20.02 s |
| eager | `heap-eager-control-20260822-20260822-202731` | 26.103 s | 18.92 s |

The internal loading phases differed by more than the expected pre-main gain and in the opposite
direction, so this pair is a noisy functional check rather than comparative timing evidence. The
isolated JVM rounds establish the removed work.

## Candidate boundary

The Recommended preset requests on-demand commitment by appending `-XX:-AlwaysPreTouch` after the
launcher's options. Conservative and Off preserve the launcher's policy. An explicit
`--eager-heap-commit` switch restores it for comparisons.

The override activates only after a bounded read proves that the selected launcher is a regular
file and contains `-XX:+AlwaysPreTouch`. An unknown, unreadable, symlinked, oversized, or changed
launcher keeps its own policy. Every run records the requested policy, whether it activated, the
reason, and the selected launcher.

## Still required

Pre-touch trades startup work for fewer first-use page faults. The automated campaign smoke reached
`main-menu-ready`, then the standalone macOS driver could not resolve the Java-owned game window and
sealed a skipped result before loading the save. The run was cleaned up. A real campaign and combat
exercise still needs to compare first-use frame tails before this candidate is accepted as the
Recommended default.

The repository-wide Maven gate also currently has four reproducible failures on pristine `main` in
the profile-activation and staged-agent-cleanup tests. The new command-line and policy tests pass;
the base failures need their own repair before this carrier can merge green.
