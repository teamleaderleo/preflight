# 1.22 GiB of padding, gone

**Date:** 2026-08-02
**Install:** Starsector 0.98a-RC8, 89 mods, macOS 15, M5 MacBook Air
**Run:** direct launch (`launchDirect`), `--texture-mode prepared-pixels --prepared-unpadded --no-record`
**Status:** correctness validated on a real full load. **No timing claim** -- the machine had no room
cooling and the operator quit the game manually after the load completed.

The [half-an-invariant crash](2026-07-31-half-an-invariant-kills-the-launcher.md) of 2026-07-31 left
`--prepared-unpadded` inert: the fold bypass had no way to reach an installed loader, because both
plans rewrite `com/fs/graphics/TextureLoader` and the registry dispatches one plan per class. That
composition landed today, and this is the first run with it active.

## The result

| | padded (campaign, `prepared-5`) | unpadded (this run) |
| --- | ---: | ---: |
| textures served | 21,653 | **23,407** |
| upload bytes supplied | 3,923,988,688 | **2,611,973,707** |
| **padding bytes** | **1,394,162,605** | **0** |
| padded uploads | 17,525 | **0** |
| peak direct buffer | 25,165,824 | **16,777,216** |
| fallbacks / NPOT / dimension / internal errors | 0 | **0** |
| circuit breaker | not tripped | not tripped |
| pending buffers at exit | 0 | 0 |

**3.65 GiB uploaded becomes 2.43 GiB -- 1.22 GiB less -- while serving 1,754 *more* textures.**

Padding went from **35.5% of every upload** to **zero**. The peak direct buffer high-water mark fell
from 24 MiB to 16 MiB, so the transient cost came down with the resident one.

Every safety counter stayed at zero: no NPOT probe fallbacks, no dimension fallbacks, no internal
errors, no quarantine, circuit breaker never armed, and nothing leaked (`pendingBuffers: 0`).
`outcome: COMPLETED, exitCode: 0`.

## The crash that used to happen, specifically

The first attempt at this run sat at the launcher rather than starting the game, and that turned out
to be useful: it isolated the exact texture that killed the process last time.

```
peakDirectBytes  668,043
```

`graphics/ui/launcher_bg.jpg` is 597x373 RGB. 597 x 373 x 3 = **668,043** bytes at true size, and the
July crash read:

```
Fatal: Number of remaining buffer elements is 668043, must be at least 1572864.
```

1024 x 512 x 3 = 1,572,864 is the padded allocation the loader used to demand. This run supplied
668,043 **and the allocation asked for 668,043**, because the fold bypass shrank both halves
together. That is the invariant working, observed on the exact texture that proved it broken.

## What this does not say

- **Nothing about time.** The machine is thermally unconstrained-adjacent at best today, no cooling,
  and the session was ended by hand. VRAM and byte counts are exact regardless; seconds are not, and
  none are claimed here.
- **The two runs are not the same texture set.** This run served 1,754 more textures than the
  campaign run it is compared against -- the menu was open longer. That makes the byte comparison
  *conservative* rather than flattering: more textures, fewer bytes.
- **Whether 1.22 GiB of VRAM buys frame rate is unmeasured**, and on a 24 GB unified-memory machine
  it may buy nothing observable. The claim here is that the allocation is gone, not that anything
  got faster. Machines with less VRAM headroom are where this should matter, and none were tested.
- **`--prepared-unpadded` is still opt-in.** Default behaviour is unchanged.

## A foot-gun found on the way

`preflight run` does not pass Starsector's `launchDirect` property; only
`scripts/run-startup-benchmark.sh` does, via `EXTRAARGS`. So a bare `preflight run` builds the
launcher UI and waits for a Play click that an unattended session will never give. The first attempt
here sat there for fourteen minutes, and the only reason that was obvious was a `jstack` showing
`GLLauncher$2.run` sleeping in `BaseGameState.traverse`.

Worse, the game's log made it look like a full load had happened: `starsector.log` is opened without
truncation, so a short run leaves the previous run's tail in place beyond its own writes. Grepping it
found "Loading variant" lines that belonged to a *different launch*. The counters
(`prefetchSkipped: 0`) were the only honest signal.

Two things worth fixing: `run` should offer the direct-launch path itself, and anything reading
`starsector.log` should bound itself to the current run rather than trusting the file's tail.
