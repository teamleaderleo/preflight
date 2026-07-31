# The 18-second bimodality was the harness, not the game

**Date:** 2026-08-01
**Install:** Starsector 0.98a-RC8, macOS 15 (Darwin 25.5.0), M5 MacBook Air, 24 GB
**Status:** resolved; every startup number recorded before this date needs re-reading

## What was believed

Four campaigns on 2026-07-31 produced a startup distribution that split cleanly in two, with
roughly 18 seconds between the modes and no condition that predicted which mode a run landed in:

| condition | runs |
| --- | --- |
| `profile` | 74.2s, 93.5s, 75.5s |
| `vanilla` (earlier campaign) | 75.2s, 90.7s, 92.3s |
| `fast` | 77.7s, 75.2s, 92.2s, 74.9s |

That gap was larger than every optimization under discussion put together, and it was recorded
as unexplained. It is not a property of the game.

## What it actually was

The harness measured from **the first timestamped log line that appeared after its snapshot**.
Starsector's launcher writes into the same `starsector.log` the game does, and the snapshot is
taken when the launcher *looks* ready. Whether the launcher's own lines had been flushed by
that moment therefore decided whether the early part of loading fell inside the measured
interval or outside it.

Lining every recorded run up against the line it anchored on separates the two modes perfectly,
and nothing else does:

| anchor line | measured |
| --- | --- |
| `StarfarerLauncher - Running with the following mods…` (≈8–9s into the JVM) | 92.2, 93.5, 93.6, 99.1 |
| `RulesParser` / `LoadingUtils`, mid-load (≈23–27s in) | 74.2, 74.9, 75.2, 75.5, 77.7 |

Two spellings of a single quantity. The mode a run landed in was decided by log4j's flush
timing against a six-second quiet window.

## Which mode was right

The high one. `scripts/starsector_log_load_times.py` recovers each launch's interval from the
game's own log with no harness involved — from the first line of Starsector's game-start method
to GraphicsLib's post-preload VRAM report. Against the seven launches still present in the
rotation:

```
  start@    4771ms  starsector.log.3       90.0s
  start@    8077ms  starsector.log.3       94.0s
  start@    8777ms  starsector.log.2       99.1s
  start@    8476ms  starsector.log.2       91.4s
  start@    9079ms  starsector.log.1       92.2s
  start@    8744ms  starsector.log.1       89.6s

  6 complete launches  min 89.6s  median 91.8s  max 99.1s
```

Unimodal, 89.6–99.1s, spread ≈ 9.5s. The launcher-anchored harness runs match it. The
mid-load-anchored ones understate by 14–18 seconds, because they started the clock partway
through the load they were supposed to be timing.

So the correction runs one way: **startup is ~92s, not ~75s**, and roughly a fifth of the load
had been silently excluded from an unpredictable subset of runs.

## What changed

`watch-main-menu` now anchors on the first line Starsector's game-start method logs —
`Running with the following mods (in order of priority):`, or `Running vanilla game with no
mods.` on an install with nothing enabled. Both come from the same method at the same point, so
the anchor is the exact instant the launcher hands over, and it does not move with flush timing,
snapshot timing, or which protocol drove the launch.

The discarded anchor is still reported as `firstObservedLogLine` /
`firstObservedLogLineToGraphicsPreloadMs`, so the size of the artifact stays visible in each
run's own record rather than living only in this document.

A stream that has lines but no start marker is now reported as
`gameStartMarkerStreams: 0` rather than as no candidate stream at all: that is the shape of
"the launcher ran and the game never did", and it used to look like a slow load.

## What this invalidates

Every `gameLogStartToGraphicsPreloadMs` recorded before 2026-08-01, and every comparison built
on one. In particular the `prepared` vs `fast` pilot: `prepared` was recorded at 99.1s and
`fast` at 92.2/74.9s, and only one of those two `fast` numbers was measuring the same thing the
`prepared` number was. No conclusion about prepared-pixels survives.

The absolute claim that startup is ~75 seconds does not survive either. It was never measured;
it was the low half of an artifact.

## Why it was invisible

The harness and the detector were consistent with each other, and the recorded runs each looked
internally sound — a real anchor line, a real preload marker, a real quiet window. Nothing
inside the measurement disagreed with anything else inside the measurement. It took reading the
game's own log independently of the tool that drove it, which is why
`scripts/starsector_log_load_times.py` now exists as a permanent check rather than as the
one-off that found this.
