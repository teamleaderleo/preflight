# Ten percent, by not waiting

**Date:** 2026-08-01
**Install:** Starsector 0.98a-RC8, 77 enabled mods, macOS 15, M5 MacBook Air (10 cores), 24 GB
**Session:** `~/.starsector-preflight/benchmarks/20260801-195544`
**Protocol:** direct (unattended), 240s cooldown before every launch, discarded settling launch, 5 rounds x 3 conditions
**Status:** `benchmarkAccepted: true`, 15 of 15 runs, no exclusions

## The result

| condition | n | median | min | max | range |
| --- | --- | --- | --- | --- | --- |
| `vanilla` | 5 | 88.49s | 86.78 | 91.54 | 4.76 |
| **`fast`** (cache + prefetch bypass) | 5 | **78.93s** | 76.51 | 80.11 | 3.60 |
| `prepared` (pixel bypass, prefetch bypass disabled) | 5 | 87.89s | 86.87 | 89.38 | 2.51 |

| comparison | delta | p | isolates |
| --- | --- | --- | --- |
| **`fast` vs `vanilla`** | **+9.56s (10.8%)** | 0.048 | what a user feels |
| `fast` vs `prepared` | +8.96s (11.3%) | 0.048 | the two bypasses against each other |
| `prepared` vs `vanilla` | +0.60s (0.7%) | 1.000 | the pixel bypass alone |

Paired within rounds, where both conditions saw the same thermal state minutes apart:

| round | fast | prepared | vanilla | fast vs vanilla |
| --- | --- | --- | --- | --- |
| 1 | 76.51 | 87.89 | 87.45 | -10.94s |
| 2 | 80.11 | 89.38 | 91.54 | -11.43s |
| 3 | 78.93 | 88.61 | 88.49 | -9.56s |
| 4 | 77.86 | 86.87 | 90.13 | -12.27s |
| 5 | 79.30 | 86.94 | 86.78 | -7.48s |

**`fast` beats `vanilla` in 5 rounds of 5, mean -10.34s, sd 1.87.** It beats `prepared` 5 of 5.
Launch-order drift across the campaign was **-0.5s**, 1.3% of variance.

## What it is

Not a cheaper computation. **A wait that stops happening.**

Starsector enqueues every image resource onto one list and starts exactly one thread to decode
them. `TextureLoader` asks that queue whether a path is pending and then polls the result map,
sleeping 10ms at a time until the single decoder reaches it. On the reviewed profile the loading
thread spent **27 of the load's 96 seconds** in that poll loop
([evidence](2026-08-01-the-loading-thread-waits-on-a-one-thread-prefetcher.md)) -- while the decoded
pixels for those same images sat unread in Preflight's cache, because the compatibility rewrite
spliced its lookup in on the far side of the wait.

The fix drops the enqueue instead of racing it. A path the manifest can serve never goes on the
queue, so the consumer finds nothing, returns immediately, and falls through to the lookup that was
already there. Telemetry from the final run:

```
prefetchSkipped  50879      enqueues taken off the game's queue
prefetchKept         1
attempts         21656      up from 6654 before the bypass
hits             21653      2.53 GB served, up from 643 MB
fallbacks            3      entry-missing
```

The game enqueues **50,880 images and asks for 21,656**. So one thread was also decoding roughly
29,000 images nobody ever collected.

## Two reversals

**1. `fast` was the condition that lost.** In the last accepted campaign it was *slower than
vanilla* by 1.28s, and the roadmap said not to ship it as a speed feature. It now wins by 9.56s.
What changed is not the cache -- the same blob I/O and the same per-lookup SHA-256 are still on the
loading thread. What changed is that the cache is now asked.

**2. `prepared` is no longer the best path.** It is 0.60s from vanilla with p = 1.000: on this
profile it is indistinguishable from doing nothing, and 8.96s behind `fast`.

That is not because the pixel bypass stopped working. It is because **`prepared` cannot take the
prefetch bypass.** That mode answers with a 1x1 raster that reports the texture's real dimensions --
a token only the rewritten conversion can read. Widening the set of paths it serves handed that
token to `com.fs.graphics.oO0O`, a greyscale-to-alpha mask converter that walks the raster, and
crashed the load at 23.6s. The bypass is disabled there, and `prepared` therefore still pays the
27-second wait.

**The two optimizations are currently mutually exclusive, and the smaller one is the pixel bypass.**
Composing them means a carrier that any consumer can read; `TexturePreparedPixelCarrierSurface`
already builds one for the padded case, and it costs a materialisation the mode exists to avoid.
That trade is unmeasured.

## What this campaign cost to get right

Three claims published earlier the same day were wrong, and all three came from the instrument
rather than the game: that the loading thread never blocks (a top-8 truncation dropped it to ninth,
behind six permanently-idle daemons), that the load is therefore an irreducibly serial chain, and
that the idle-core opportunity is not reachable from where Preflight sits. Separately every
published JFR duration was short by 2.49x, because the game launches with
`-XX:+UseFastUnorderedTimeStamps`.

Two harness defects also had to be fixed before a campaign could run at all: `--unattended` stopped
at three operator prompts and `set -e` ended the run one line after it announced it was starting,
twice, silently. And the measurement environment turned out to matter by about as much as the
effect: a settling launch on a quiet machine came in at 75.9s against 82.44s for the same work on a
loaded one, which is why the loaded attempt was discarded rather than resumed.

## What to do next

1. **Ship the prefetch bypass as the default cache-backed path.** It is the first double-digit
   result this project has produced, it is fail-open, and it needs no new artifacts.
2. **Decide what `prepared` is for.** It is currently a slower path that carries a latent hazard.
   Either make its carrier readable so it can take the bypass too, or retire it.
3. **Re-measure the per-lookup SHA-256.** Still on the loading thread, and its 1.01s justification
   came off the broken clock.
4. **The JSON/spec path is still untouched** and is comparable to the texture path in both time and
   allocation.
