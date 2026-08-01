# The first valid startup number, and what it says

**Date:** 2026-08-01
**Install:** Starsector 0.98a-RC8, 77 enabled mods, macOS 15 (Darwin 25.5.0), M5 MacBook Air, 24 GB
**Session:** `~/.starsector-preflight/benchmarks/20260801-080055`
**Protocol:** direct (unattended), 240s cooldown before every launch, 5 rounds × 3 conditions
**Status:** `benchmarkAccepted: true` — the first campaign in this project's history to reach it

## The result

| condition | n | median | mean | sd | runs |
| --- | --- | --- | --- | --- | --- |
| `vanilla` | 5 | 95.78s | 95.70s | 0.50 | 94.9 95.6 95.8 96.1 96.2 |
| `fast` (cache, compatibility) | 5 | 97.22s | 96.98s | 0.86 | 95.6 96.8 97.2 97.5 97.8 |
| `prepared` (cache + pixel bypass) | 5 | 94.10s | 94.29s | 1.35 | 93.0 93.1 94.1 95.0 96.3 |

Paired within each round, where both conditions saw the same thermal state minutes apart:

| comparison | mean | rounds won |
| --- | --- | --- |
| `prepared` beats `fast` | **+2.68s** | **5 / 5** |
| `prepared` beats `vanilla` | +1.41s | 4 / 5 |
| `fast` beats `vanilla` | **−1.28s** | **1 / 5** |

Launch-order drift across the whole campaign: **+0.47s**, 2.8% of variance. The uncooled campaign
two hours earlier drifted +19.4s and 89%. The 240-second cooldown removed the confound by a
factor of forty.

## Three findings

**1. The prepared-pixel bypass works. It is the only thing here that does.**

Five rounds out of five, by 2.68s. Every run carried all 6651 textures with zero fallbacks. This
is the first measured acceleration in the project's history.

**2. The texture cache, on its own, makes startup slower.**

`fast` lost to `vanilla` in four rounds out of five, by 1.28s on average. It serves 6651 cache
entries and 643 MB, and still costs more than letting the game decode the PNGs itself. The
mechanism is not mysterious: the compatibility path hands the game a `BufferedImage` it must
still unpack a pixel at a time, so the cache buys only the decode — 13–16% of texture time on the
reviewed profile — while paying blob I/O and a **SHA-256 of every source file on the loading
thread**, separately measured at 1.01s. That figure alone very nearly accounts for the deficit.

Everything Preflight currently delivers comes from the conversion bypass. The cache is the
vehicle, not the win.

**3. The profile's shares do not convert into wall clock, and that is the important one.**

JFR attribution put the `BufferedImage`→`ByteBuffer` conversion at 34–40% of the loading thread,
which was 45% of all execution samples — roughly 15–18% of sampled CPU, on the order of 15
seconds of a 96-second load. Removing it entirely bought **2.68 seconds**.

So about four fifths of the CPU work we deleted from the loading thread did not exist in the wall
clock. The loading thread is not the critical path for most of the load. Whatever is — GPU upload
inside `glTexImage2D`, I/O, another thread, or lock contention — is not something execution
sampling of Java frames can see, and is not something more of this kind of optimization will
reach.

The log's own phase timeline agrees that textures dominate (`TextureLoader` owns roughly 50 of
the 96 seconds, in two blocks at 25–65s and 85–95s) while saying nothing about *what* inside the
texture phase is waiting. One window is conspicuous: 40–45s logs almost nothing at all.

## What this costs a user

`prepared` versus `vanilla` is what someone would actually feel: **1.4 seconds off 95.7, about
1.5%**, for 5.3 GB of cache and a 13-second preparation step. Four rounds out of five, so the
direction is probably real, but the size is not something anyone would notice.

That is the honest headline. The project has a measured acceleration for the first time, and it
is small.

## Why this campaign is trustworthy when the earlier ones were not

Five separate defects had to be fixed today before any number meant anything, and every one of
them was invisible while the harness and the detector were the only things checking each other:

1. the measurement anchored on a log line chosen by flush timing (±18s, and the "bimodality");
2. completion waited for a silence the game never produces unattended;
3. a launch straddling a log rotation was counted as two, and never completed;
4. a rotation racing the reader killed the detector, producing an exclusion that looked like a
   failed launch;
5. thermal drift of +19.6s across a campaign, ten times the effect being measured, with nothing
   watching for it.

Each is now guarded, and `scripts/starsector_log_load_times.py` reconstructs load times from
Starsector's own log independently of all of it. For this campaign the two agree: 93.0–97.8s from
the game's log against 93.0–97.8s from the harness.

## What to do next, in order

1. **Find the real critical path.** Deleting 15s of loading-thread CPU produced 2.7s. Until that
   ratio is understood, every further CPU optimization is speculative. The 40–45s silent window
   is the place to start looking, and the instrument is probably not JFR execution sampling.
2. **Take the SHA-256 off the loading thread.** Already designed, never built, and now with a
   measured 1.01s justification plus a net-negative cache to explain.
3. **Do not ship the compatibility cache as a speed feature.** On this profile it is a
   regression. It is only worth carrying as the substrate the prepared-pixel path needs.
