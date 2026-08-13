# The loading thread never waits for the audio it decodes

*2026-07-30*

Yesterday's probe established that Starsector decodes every declared sound effect before the main menu
— [1,169.4 MB of PCM](2026-07-29-the-game-builds-1-2-gb-of-pcm-before-the-main-menu.md), all 2,050
files, in a 1.5-second burst. That answered "does this work happen at load".

It does not answer "does this work make loading take longer", and those are different questions. This
page answers the second one, from recordings that already existed.

**The answer is no, on this machine.** Audio decoding runs on a worker pool that the loading thread
never waits for, and the pool spends much of the load idle. Texture decoding is the opposite: it is on
the loading thread, and it is 40–53% of it.

## Method

Two recordings, one with `--trace-all-file-reads` and one without, from different sessions eleven days
apart. `jdk.ExecutionSample` attributed by the first non-JDK frame using the same category rules as
`StartupCpuAttribution`, restricted to the **first 90 seconds** and split **by sampled thread** —
which is the part previous analysis had not done. Blocking is every `jdk.ThreadPark`,
`jdk.JavaMonitorWait`, `jdk.JavaMonitorEnter` and `jdk.ThreadSleep` event in the same window.

## Where the work runs

| thread | 2026-07-28 | 2026-07-19 | what it does |
| --- | --- | --- | --- |
| `Thread-3` | 4,423 samples | 2,320 samples | **the loading thread** |
| `pool-1-thread-1` | 1,005 (1,002 audio) | 772 (769 audio) | audio decode worker |
| `pool-1-thread-2` | 1,012 (1,009 audio) | 764 (761 audio) | audio decode worker |
| `Thread-6` | 714 (644 Janino) | 483 (428 Janino) | Janino compilation |
| `Thread-8` | 249 (235 texture) | 132 (130 texture) | texture helper |

Composition of the loading thread itself:

| on `Thread-3`, first 90 s | 2026-07-28 | 2026-07-19 |
| --- | ---: | ---: |
| texture / image | **1,754 (39.7%)** | **1,226 (52.8%)** |
| Starsector loading | 2,344 (53.0%) | 524 (22.6%) |
| Starsector other | 320 (7.2%) | 567 (24.4%) |
| **audio decode** | **1 (0.02%)** | **2 (0.09%)** |

Two independent runs put one and two audio samples respectively on the loading thread. Audio decoding
is somewhere else entirely.

## The loading thread does not wait for it

Blocking on `Thread-3` in the first 90 seconds, excluding its own `Thread.sleep`:

| | 2026-07-28 | 2026-07-19 |
| --- | ---: | ---: |
| `ThreadPark` | 1 ms | 0 |
| `JavaMonitorWait` | 34 ms | 0 |
| `JavaMonitorEnter` | 32 ms | 26 ms |
| **total waiting on other threads** | **67 ms** | **26 ms** |

Meanwhile the audio workers park — that is, sit with no work to do:

| | 2026-07-28 | 2026-07-19 |
| --- | ---: | ---: |
| `pool-1-thread-1` parked | 22,368 ms | 10,649 ms |
| `pool-1-thread-2` parked | 22,363 ms | 10,658 ms |

The pool is ahead of demand, not behind it, and the loading thread never blocks on its output. The
machine is a 10-core Apple system (4 performance, 6 efficiency), so two decode workers running beside
one loading thread have cores to spare.

`Thread-3` does sleep — 16.2 s across 4,042 sleeps, and 29.6 s across 8,270 — which looks like frame
pacing on the loading screen rather than waiting for anything. It is busy the remaining 80%+ of the
window.

## What this changes

**Prepared audio would remove real CPU work from a thread nobody is waiting for.** On this machine
that is close to invisible in wall-clock startup time. The 1,169.4 MB figure is still correct and
still describes work the game genuinely does before the menu; it simply is not on the path that
determines when the menu appears.

That is not the same as worthless. It is real CPU and real energy, it would matter on a machine with
fewer cores where two decode workers contend with the loading thread, and a profile with far more
audio could saturate the pool until it *does* become the constraint. But none of that has been
measured, and the milestone should not be justified on a wall-clock argument that this evidence does
not support.

**Texture preparation is the one of the three that sits on the critical path**, at 40–53% of the
loading thread across two runs. [The roadmap's ordering](../roadmap.md) — ship texture reuse first —
was argued from decode cost per texture; this supports it on thread placement as well, which is the
stronger reason.

**Janino is also off the loading thread**, on `Thread-6`. The same caution applies to it.

**A ceiling worth noting.** In the run that started a new campaign, 53% of the loading thread is
`com.fs.starfarer.loading` — world generation and mod initialisation — which no cache in this project
addresses. Even a perfect texture cache leaves that untouched.

## What this does not establish

- **Two runs, one machine, one profile, macOS.** A four-core machine could show the pool contending
  with the loading thread, which would change the conclusion.
- **CPU samples are not wall-clock time.** For a thread that blocks 67 ms in 90 seconds they are a
  good proxy for where its time goes, but they do not predict how much faster loading gets if that
  work disappears.
- **It does not show that removing texture work speeds up startup.** It shows the work is on the
  thread that matters. Only the OFF-versus-ENABLED benchmark in
  [#80](https://github.com/teamleaderleo/preflight/issues/80) can turn that into a number,
  and no such number exists yet.
- **It says nothing about memory.** 1,169 MB of decoded PCM is a footprint question regardless of
  which thread built it.

## Why this was not found earlier

The CPU attribution has reported per-thread breakdowns all along, and `topThreads` in every summary
carries the numbers that make this obvious. What was missing was ever asking *which thread* a domain's
cost lands on, and whether the loading thread waits for it. Total CPU share across all threads —
`AUDIO_DECODE 14.5%`, `TEXTURE_IMAGE 15.5%` — makes audio and texture look like comparable targets.
Split by thread and by who waits for whom, they are not comparable at all.
