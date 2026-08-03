# Stock jars, measured end to end: 87.5s to 40.9s

**Date:** 2026-08-03
**Install:** Starsector 0.98a-RC8, 83 enabled mods, macOS 15, M5 MacBook Air (10 cores), 24 GB
**Session:** `~/.starsector-preflight/benchmarks/20260803-143146`
**Protocol:** direct (unattended), 45s cooldown before every launch, 2 conditions x 2 rounds
**Status:** 4 of 4 runs accepted
**Mods:** shipped AshLib and GraphicsLib jars. No mod-side patches are installed.

## The result

| condition | n | median | run 1 | run 2 |
| --- | ---: | ---: | ---: | ---: |
| `vanilla` (stock mods, no Preflight) | 2 | **87.52s** | 85.93 | 89.12 |
| `full` (everything landed) | 2 | **40.88s** | 40.86 | 40.90 |

**46.64s removed, 53.3%, 2.14x.** The two `full` runs differ by 41 ms.

This is the first campaign measured against genuinely unpatched mods. Every earlier number in this
repository was taken on an install carrying two hand-patched mod jars, which sat inside the baseline
and made it look 8 seconds faster than a stock install is.

## What changed since the previous campaign

Two plans landed on the day of this campaign:

- `loadjson-memo-v1` rewrites `LoadingUtils.ô00000(String)`, the method behind
  `SettingsAPI.loadJSON`, to read each path once per launch. 39,017 calls per launch for 8,378
  distinct paths -- 78.5% repeats;
- `resource-probe-cache-v1` answers the resolver's per-root `File.exists()` from a remembered
  directory listing. 1,618,401 probes per launch, 42.6 per lookup.

Measured separately with the phase probe, against the same stock baseline:

| | before | after |
| --- | ---: | ---: |
| mod callbacks | 23.97s | 15.46s |
| `ashlib.data.plugins.AshLibPlugin` | 8.38s | **1.92s** |
| `org.dark.shaders.ShaderModPlugin` | 10.92s | 8.87s |

AshLib's own upstream patch reaches 2.343s on its callback by memoizing its repeated `loadJSON`
calls in one class. Preflight reaches 1.92s without AshLib's cooperation, and every other mod on the
profile gets the same treatment: Chatter, Kaleidoscope and 73 others.

## What is left

| block | cost |
| --- | ---: |
| spec store | 16.77s |
| mod callbacks | 15.46s, of which GraphicsLib is 8.87s |
| progress 10 -> 25% | 13.68s |
| progress 50 -> 75% | 8.58s |

## A reporting defect this campaign exposed

The harness printed `No comparison yet: no pair of conditions both have a successful run` while
`results.jsonl` held four accepted runs with metrics. The runs are fine and the medians above are
read straight from that file, but the summary path does not agree with the acceptance path and needs
fixing before the next campaign is read from its console output.

## Reproduction

```bash
scripts/run-startup-benchmark.sh --unattended --conditions vanilla,full --rounds 2 --cooldown-seconds 45 --skip-warmup
```
