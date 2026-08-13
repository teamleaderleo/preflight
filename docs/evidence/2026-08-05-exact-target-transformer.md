# Ordinary launches no longer inventory every game class

**Date:** 2026-08-05

**Install:** Starsector 0.98a-RC8, 83 enabled mods, macOS on Apple M5 under Rosetta

## Finding

The adapter's enabled mode still parsed every class under `com/fs/starfarer/` and
`com/fs/graphics/`, even though ordinary launches already know every exact target from the adapter
registry. In the preceding startup JFR, 14 of the 151 main-thread samples below the spec-store load
passed through the agent's transformation stack. Its adapter report recorded 2,612 parsed classes,
only 38 exact matches, and a 480 KB inventory report.

The broad inventory remains useful in `--adapter-probe`: it is how a maintainer discovers that an
upstream patch renamed or reshaped a possible target. It is not needed in ordinary enabled mode.
Exact targets, specialized compatibility observers, archive identity, class hash, and bytecode
shape checks remain unchanged.

## Change and failure boundary

Enabled mode now supplies no broad candidate prefixes. The transformer still observes every exact
registry target and every class requested by a specialized compatibility report. Probe mode retains
the two broad prefixes and its previous discovery behavior.

If a future game or mod patch changes an exact target, the existing exact gate declines the adapter
and reports the drift; vanilla continues. A maintainer can then run probe mode to inventory renamed
candidates. This change removes ordinary-launch discovery work, not any compatibility or fallback
boundary.

## Live gate and cohort

The first unattended live gate is
`~/.starsector-preflight/runs/exact-target-transformer-20260805-225928`:

- 38 classes observed and parsed instead of 2,612 (**98.5% fewer**);
- 38 exact matches and 38 transformations applied;
- zero decline, contained failure, or shadowed target;
- adapter report reduced from 480 KB to 197 KB;
- main menu reached in 25.72 seconds and the game stopped automatically.

A following thermally spaced ordinary cohort is
`~/.starsector-preflight/benchmarks/20260805-230249`:

- **24.41, 24.12, 23.93, 24.43, and 23.98 seconds**;
- **24.12-second median**, 0.50-second full range;
- each run observed and parsed 33 exact targets, applied all 33, and reported zero decline or
  contained failure;
- each game stopped automatically after the main-menu marker.

The immediately preceding prepared-audio cohort had a 24.76-second median, but it was not a
shuffled same-session A/B and used different thermal spacing. The defensible result for this change
is the 98.5% reduction in class parsing and reduced CPU work. The five-run cohort independently
establishes current repeatable startup at 24.12 seconds.

Focused adapter tests and full `mvn verify` pass.
