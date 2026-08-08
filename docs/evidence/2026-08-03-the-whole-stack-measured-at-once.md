# The whole stack, measured at once

**Date:** 2026-08-03
**Install:** Starsector 0.98a-RC8, 83 enabled mods, macOS 15, M5 MacBook Air (10 cores), 24 GB
**Session:** `~/.starsector-preflight/benchmarks/20260803-021319`
**Repository head:** `6d85820`
**Protocol:** direct (unattended), 45s cooldown before every launch, no settling launch, 5 rounds x 3 conditions, seed 14624
**Status:** 15 of 15 runs accepted, no exclusions

> **Superseded intermediate measurement — not the project baseline or headline.** The development
> installation's observed worst case reached roughly **101 seconds**, its established five-run
> controlled median was **88.13 seconds**, and the later fastest recorded launch was **15.88
> seconds**. The chronological headline is **101 seconds to 15.88 seconds**. The 80.09-second and
> 42.36-second values below belong only to this August 3 partially optimized comparison.

Both comparison arms already included hard-coded AshLib and GraphicsLib fixes installed in the mod
JARs. This campaign measures the additional Preflight stack that existed on August 3. It remains
checked in as evidence for that intermediate state rather than as the project's before/after result.

At this point in the investigation, the running estimate was arithmetic: component savings measured
one at a time and added together. This was the first campaign to turn on everything that had landed
by August 3 and time that intermediate stack.

## The August 3 intermediate result

| condition | n | median | min | max | range |
| --- | --- | ---: | ---: | ---: | ---: |
| `vanilla` (no Preflight) | 5 | 80.09s | 79.04 | 80.19 | 1.15 |
| `fast` (compatibility textures, no rule caches) | 5 | 47.08s | 46.02 | 47.81 | 1.79 |
| `full` (everything landed by August 3) | 5 | 42.36s | 41.56 | 42.78 | 1.23 |

| comparison | delta | isolates |
| --- | ---: | --- |
| `full` vs `vanilla` | +37.74s (47.1%), 1.89x | the then-current Preflight stack after the installed mod-side fixes |
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

`vanilla` is a bad name for this condition and it is worth saying so before anything else. It runs the
game's own launcher with `JAVA_TOOL_OPTIONS` cleared -- but the installation it launches is the
modded one, all 83 of them. It is not vanilla Starsector. It is *the modded install with no
Preflight*, which is the right comparison but not the name on the label.

That distinction is exactly what moved the baseline. Between the two campaigns, on 2026-08-02, two
patched mod jars were installed: `Ashlib_/jars/ashlib.jar` at 09:43 and
`zz GraphicsLib-1.12.1/jars/Graphics.jar` at 10:07. Those are the AshLib `ShipRenderInfo` memoization
and the GraphicsLib compact auto-generation replay -- **changes to the mods' own source, not
Preflight code.** There is no AshLib or GraphicsLib code anywhere in `preflight-agent` or
`preflight-cli`.

So the savings from those two patches are now *inside* the 80.09s baseline. The condition got faster
because the installation got faster, which is exactly what a no-Preflight condition should report.

This also resolves what the accumulated scorecard was doing wrong. It listed both patches among
Preflight's own savings and subtracted them from a baseline recorded before they were installed:

| | seconds |
| --- | ---: |
| scorecard component total | 47.63-48.00 |
| less AshLib + GraphicsLib, which are mod-side and now sit in the baseline | -11.89 to -12.26 |
| remainder attributable to Preflight | **35.4-36.1** |
| **measured (`full` vs the same session's baseline)** | **37.74** |

Within about 2 seconds, which is the same accuracy the floor prediction showed. The component
arithmetic was not really wrong; it was crediting Preflight with work that ships in two mod jars, and
subtracting it twice.

The rule that follows still holds, and now for a concrete reason: **divide by the baseline measured
in the same interleaved session.** Anything that changes the installation -- including a mod patch of
your own -- moves it.

## What these intermediate numbers establish

**A historical measurement of the August 3 installation state, on one machine and one profile.**

It is internally controlled: unattended, direct-launch, interleaved, quiet machine, warm caches,
with no settling launch needed because the installation had already stopped changing. It isolates
the Preflight stack present that day after the two mod-side patches. Its scope ends at that
intermediate installation state.

The development installation's earlier lived range reached roughly 101 seconds and its controlled
median was 88.13 seconds. Later accepted work reached 15.88 seconds. Those chronological points and
this intermediate campaign have different installation states, so the raw August 3 ratio doesn't
replace either the project headline or a fresh release-candidate comparison.

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
