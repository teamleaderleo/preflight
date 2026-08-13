# The startup benchmark now measures the product's `--fast` preset

**Date:** 2026-08-05  
**Install:** Starsector 0.98a-RC8, 83 enabled mods, macOS 26.5.1, Apple M5, 24 GB  
**Repository parent:** `b71f475`  
**Protocol:** direct, unattended, warm prepared cache, no settling launch, two consecutive runs  
**Corrected session:** `~/.starsector-preflight/benchmarks/20260805-054329`  
**Diagnostic old-subset session:** `~/.starsector-preflight/benchmarks/20260805-053848`

## The result

| run | game log start to graphics preload |
| --- | ---: |
| corrected `--fast` 1 | **31.76s** |
| corrected `--fast` 2 | **32.64s** |
| **median** | **32.20s** |
| range | 0.88s |

This is a repeated warm pair, not the harness's five-run statistical campaign. It is enough to
answer the project's narrower repeated-33-second goal; it is not a new baseline comparison or a
population-level speedup claim.

Both runs passed the harness's behavior gates:

- 21,652 prepared texture hits and three expected missing compatibility entries;
- 21,652 prepared-pixel hits, zero prepared-pixel fallback, and 21,652 conversions bypassed;
- 228/228 Janino complete-map hits, with no miss, corruption, error, or policy decline;
- 1,469/1,469 keyed merged-read hits, with no miss, capture, collision, or write;
- GraphicsLib compact replay applied exactly once;
- 22 exact transformations applied, zero transformation decline, zero contained failure, and no
  kill switch or rejected source binding.

The receipts report launcher exit 143 because the unattended harness deliberately sends SIGTERM
after observing the completion marker. The harness records that owned stop as success, runs the JVM
shutdown hooks, waits for the wrapper to finalize its receipts, and found no remaining game JVM.

## Why the first pair said 56 seconds

The same command initially produced 54.23s and 58.13s. The cache was working—both runs served 21,653
compatibility textures and hit every merged read—but the benchmark's `fast` dispatch did not pass
the CLI's `--fast` option. It expanded an older flag set:

```text
--adapter --texture-auto --no-record
```

That condition was named before the product preset existed. It intentionally used compatibility
textures and omitted the prepared-pixel bridge and two rule caches. Later the real `--fast` preset
acquired prepared audio, load-JSON memoization, GraphicsLib compact replay, Janino bytecode reuse,
the insignia cache, gameplay caches, and other live-gated adapters. The benchmark label stayed the
same while the product moved on, so it silently ceased to measure the installed launcher path.

The correction makes the condition dispatch literal and unambiguous:

```text
preflight run ... --fast --texture-cache-dir <cache>
```

The old compatibility subset remains available as `compatibility` for component comparisons. The
old `full` condition remains frozen as the explicit 2026-08-03 stack so that the accepted historical
campaign is reproducible; it is no longer described as every optimization currently landed.

`fast` now uses the prepared-pixel acceptance gate, so a fail-open launch that serves compatibility
objects but bypasses no conversions cannot be accepted as a product-path timing. The reporter also
labels the condition `current --fast preset`, preventing the old ambiguity from returning in output.

## Reproduction

```bash
scripts/run-startup-benchmark.sh \
  --rounds 2 \
  --conditions fast \
  --unattended \
  --skip-warmup
```

For a publishable comparison against the modded install without Preflight, use five interleaved
rounds and a fixed cooldown:

```bash
scripts/run-startup-benchmark.sh \
  --rounds 5 \
  --conditions vanilla,fast \
  --unattended \
  --cooldown-seconds 45
```
