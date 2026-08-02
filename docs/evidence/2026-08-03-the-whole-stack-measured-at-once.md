# The whole stack, measured at once

**Date:** 2026-08-03
**Install:** Starsector 0.98a-RC8, 83 enabled mods, macOS 15, M5 MacBook Air (10 cores), 24 GB
**Session:** `~/.starsector-preflight/benchmarks/20260803-021319`
**Repository head:** `6d85820`
**Protocol:** direct (unattended), 45s cooldown before every launch, no settling launch, 5 rounds x 3 conditions, seed 14624
**Status:** 15 of 15 runs accepted, no exclusions

Until now the project's headline was arithmetic: component savings measured one at a time and
added together, producing a predicted floor rather than a measured one. This is the first campaign
that turns on everything that has landed and times it.

## The result

| condition | n | median | min | max | range |
| --- | --- | ---: | ---: | ---: | ---: |
| `vanilla` (no Preflight) | 5 | 80.09s | 79.04 | 80.19 | 1.15 |
| `fast` (compatibility textures, no rule caches) | 5 | 47.08s | 46.02 | 47.81 | 1.79 |
| **`full`** (everything landed) | 5 | **42.36s** | 41.56 | 42.78 | 1.23 |

| comparison | delta | isolates |
| --- | ---: | --- |
| **`full` vs `vanilla`** | **+37.74s (47.1%), 1.89x** | the whole project |
| `fast` vs `vanilla` | +33.01s (41.2%), 1.70x | everything except the pixel path and the rule caches |
| `full` vs `fast` | +4.72s (10.0%) | the prepared-pixel path plus the two rule caches |

Paired within rounds:

| round | full | fast | vanilla | full vs vanilla |
| --- | ---: | ---: | ---: | ---: |
| 1 | 42.36 | 46.61 | 79.04 | -36.68s |
| 2 | 42.33 | 47.81 | 80.19 | -37.86s |
| 3 | 42.78 | 46.02 | 80.11 | -37.32s |
| 4 | 42.55 | 47.21 | 79.86 | -37.31s |
| 5 | 41.56 | 47.08 | 80.09 | -38.54s |

Every round agrees to within 1.9 seconds on a 37-second effect.

## The harness could not previously ask for this

`fast` is `--adapter --texture-auto --no-record`. It does **not** include the prepared-pixel texture
path, and it does not pass `--rule-token-cache` or `--rule-command-cache`. So every `fast` number
this project has published understates what had landed, and no condition existed that turned
everything on at once.

`full` is that condition:

```
--adapter --texture-auto --texture-mode prepared-pixels --prepared-npot
--rule-token-cache --rule-command-cache --no-record
```

It inherits the `prepared` acceptance gate, which excludes a run unless it both served prepared
textures and bypassed pixel conversions. The adapter is fail-open by design, so a launch where it
silently served nothing looks exactly like an ordinary launch; without that gate a campaign can time
the baseline twice and report the difference as a win.

The 4.72s between `fast` and `full` is the size of what was going unmeasured.

## The stacked estimate predicted the floor but not the baseline

The scorecard before this campaign claimed 47.63-48.00s removed and 2.18-2.20x, from an 88.13s
baseline down to a predicted 40.13-40.50s floor.

Measured: 37.74s removed and 1.89x, from 80.09s down to 42.36s.

Splitting the difference into its two halves is more useful than the single discrepancy:

- **the predicted floor was close.** 40.13-40.50s predicted against 42.36s measured, about 2s apart.
  Component savings measured in isolation composed better than they had any right to;
- **the baseline was not.** The old campaign's `vanilla` was 88.13s and this one's is 80.09s.

`vanilla` runs the game's own launcher with `JAVA_TOOL_OPTIONS` cleared. Nothing Preflight does can
touch it, and Preflight never writes to the installation, so the 8-second move is not our work
appearing where it should not.

What it is has not been established. The obvious candidate points the wrong way: the profile grew
from 77 mods to 83, which should be slower rather than faster. So does cooldown, since the earlier
campaign idled 240s before each launch against this one's 45s, and a cooler machine is a faster one.
The measurement boundary is identical in both -- `gameLogStartToMainMenuMs` is the recorded key in
both sessions' `results.jsonl`, so this is not a metric change.

**The operational conclusion stands regardless of the cause: a `vanilla` median measured three days
earlier is not a valid baseline.** It moved 9% while the thing it measures did not change. Every
speedup claim should divide by the `vanilla` measured in its own session, interleaved with the
condition it is being compared against, which is what the harness already shuffles for and what this
campaign does.

That is also the cleanest explanation for why the stacked scorecard drifted: it subtracted a growing
stack of same-session component savings from a baseline captured in a different session.

## What the number is and is not

**1.89x, 37.74s removed, 47.1% of a modded startup, on one machine and one profile.**

It is a controlled measurement: unattended, direct-launch, interleaved, quiet machine, warm caches,
no settling launch needed because the installation had already stopped changing. That is the number
to quote when someone asks what Preflight does.

It is not the number a person experiences on a bad day. The lived range on this installation before
any of this work was 90-100+ seconds, which includes a cold page cache, a thermally loaded machine,
and the launcher itself. Against that range, a 42.36s load is roughly 2.1-2.4x. Both belong in the
writeup: the controlled figure as the claim, the lived range as context, rather than picking
whichever is larger.

## Variance, and how many rounds this needed

| condition | range across 5 runs |
| --- | ---: |
| `vanilla` | 1.15s |
| `fast` | 1.79s |
| `full` | 1.23s |

Against a 37.74s effect, five rounds per condition is more than this needed; three would have
separated these conditions just as decisively. The five-round threshold in the harness exists so
that a rank test can produce a p-value below 0.1, which is a real constraint on the test and an
irrelevant one at this effect size. Five was kept here only so the comparison lines up with the
earlier accepted campaign.

The per-round shuffle deserves a second look. With two conditions it degenerates easily: seeded at
32000 it produces the same order in all five rounds, which removes the entire protection it exists
to provide. Three conditions make that far less likely, and this session's seed produced a mixed
order, but the mechanism should not depend on the seed being lucky.

## Reproduction

```bash
scripts/run-startup-benchmark.sh --unattended --conditions vanilla,fast,full \
  --rounds 5 --cooldown-seconds 45
```

Drop `--skip-warmup` on an installation that has not been launched recently: GraphicsLib writes
generated normal maps on its first run, and that one-time write is enough to move the profile
fingerprint and invalidate a comparison mid-campaign.
