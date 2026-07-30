# The game builds 1.2 GB of PCM before the main menu

> **Read with [the loading thread never waits for the audio](2026-07-30-the-loading-thread-never-waits-for-the-audio.md) (2026-07-30).**
> Everything on this page stands. But this work runs on a pool the loading thread never waits for, so
> it is not on the path that determines when the menu appears, and nothing here should be read as
> "startup is 1.2 GB slower". *Does this work happen at load* and *does this work delay load* are
> different questions, and this page only answers the first.

*2026-07-29* — **Corrected twice; this page has been wrong twice.**

*Corrected 2026-07-30, and renamed — it was published as "940 MB".* The probe resolved the paths in
the recording against its own working directory rather than the game's, so every resource Starsector
opened by relative path looked unopened. The effect count was **1,278 of 2,050**; it is **2,050 of
2,050**. The PCM was **940.3 MB**; it is **1,169.4 MB**. The section explaining which effects went
unopened described a bug in this repository, not behaviour of the game, and has been deleted.
[#232](https://github.com/teamleaderleo/starsector-preflight/issues/232).

*Corrected 2026-07-29.* The first version called the session "menus only"; the player had entered the
campaign and flown on the map, without combat. It also claimed music is never opened at load, which
the recording does not support.

M5 has been sized but never justified. [The audio census](../audio-census.md) established what
prepared audio would have to hold; nothing established that the game builds any of it during load
rather than on first play. If loading were lazy, the milestone would move work out of moments nobody
is timing and should not be built.

One launch settled it.

## The run

A single session on the reviewed profile, recorded with
[`run --trace-all-file-reads`](../audio-decode-probe.md). Six minutes, reaching the campaign map and
flying on it; no combat. 418,588 file reads captured, of which 20,182 audio reads resolved to a file
the census declares and 1,806 did not — all of the latter being `music.bin`, below.

| | declared | opened | PCM behind opened | PCM behind unopened |
| --- | ---: | ---: | ---: | ---: |
| effects | 2,050 | **2,050** | **1,169.4 MB** | 0 |
| music | 156 | 0 | 0 | 2,890.1 MB |
| unreferenced | 220 | 0 | 0 | 220.2 MB |

**Effect loading is eager, it is complete, and it is a bulk load.** Every declared effect in the
profile — all 2,050 — was opened inside a **1.5-second window**, between 23.2 s and 24.7 s of a
359.6-second session, and all 20,182 audio reads came from a single caller,
`com.fs.graphics.L.Ô00000`. Loading spread across a session is what on-demand looks like. This is not
that.

**Music is a container, and this probe cannot see inside it.** Zero of the 156 individually declared
music files were opened — but the recording holds **1,806 reads of `sounds/music/music.bin`**, 14.6 MB,
which the census has no entry for because vanilla music is not shipped as separate files at all. An
earlier version of this page read that zero as "music is never decoded at load". It supports no such
thing. What can be said is narrower: the *declared* music files were not opened, and what the game
does with `music.bin` is unmeasured.

**Nothing opens the unreferenced files.** Zero of 220. The census called them unreferenced because no
`sounds.json` names them; this is the first direct confirmation that the game agrees. It is also a
free validation of the `audio-unreferenced` lint rule, which had only ever been argued from the
config.

## Nothing loads after the menu

Every first open in the run falls between 23.2 s and 24.7 s. **No sound file was opened for the first
time after the 24.7-second mark**, across five and a half further minutes that included flying on the
campaign map.

This is why the figure is no longer a floor. The earlier version of this page called it one because
combat was never entered — but the profile declares 2,050 effects and the game opened all 2,050
before the main menu. Combat cannot add to a set that is already complete, and a sound loaded from
outside the census would appear as an unmatched read, of which this run has none but `music.bin`.

What remains outside the number is what was always outside it: music, the 220 unreferenced files
nothing touches, and the gap between opening a file and decoding it.

## The verdict logic was wrong, and this run is what exposed it

The probe first reported `INCONCLUSIVE`. It compared the opened fraction — 62% — against thresholds
of 90% for eager and 10% for lazy, and 62% fell between them.

That was a badly posed test, and the fault is in the design rather than the thresholds. *Lazy* means
**at the time of use**, so *when* files are opened is the direct evidence and *how many* is only a
proxy. A partial fraction is not ambiguity when every open lands inside 1.5 seconds of a six-minute
session.

The verdict now keys on whether the opens are a burst — a first-open window under 5% of the session
or under three seconds outright, across at least twenty files — and states explicitly when coverage is
partial.

There is an irony worth recording. The 62% that motivated this redesign was not a real fraction: it
was the path bug below. The design change stands on its own reasoning, and the fraction thresholds
would now agree with the burst test anyway — but the example that drove it was noise. Redesigning a
measure in response to a number you have not yet explained is how that happens.

## The bug behind both corrections

Flight Recorder stores the path the JVM passed to the OS, not a resolved one. Starsector runs with
its core resource directory as its working directory and opens its own resources by relative path:
`sounds/sfx_impacts/shield_hit_heavy_01.ogg`, with no install prefix. The probe resolved those
against *Preflight's* working directory, where they do not exist.

The scale, from the same recording: **4,288 distinct relative paths, 54,071 read events** — 13% of all
file reads. 4,284 of those paths exist under the core root; the four that do not are `.inprogress`
save files renamed between the read and the analysis. Of the relative reads, 7,309 were audio, and
5,503 of those were declared effects the probe had reported as never opened.

It stayed hidden because it cannot reproduce in a single process: resolving a relative path against
"here" works fine when the recorder and the analyser share a working directory, and in production they
never do. The regression test now starts a child JVM in the core directory so that the two disagree,
and it fails with the original symptom — a confident `LAZY` — when the fix is removed.

Chasing the earlier music correction is what surfaced this, via the 33% of audio reads the probe was
discarding in silence. That share is now printed above the findings, which is how a third correction
gets found faster than this one was.

## What this means for M5

The premise holds, and more strongly than the first version of this page claimed. Prepared audio for
effects targets real, repeated, pre-menu work — **every declared effect in the profile, 1,169.4 MB of
PCM, built before the player sees the main menu, on every launch**, from files whose identity the
census already knows.

It does not license the whole milestone:

- **Music stays out**, on the same caution as before. This run did not strengthen that case, and
  briefly appeared to until the container was found.
- **The 220 unreferenced files stay out.** Nothing loads them.
- **Reads are still not decodes.** The burst is consistent with eager decoding and hard to explain
  otherwise, but the direct evidence is that the files are opened, and the equivalence work in
  [#207/#208](../roadmap.md) remains what proves what the decoder does with them.
- **1,169.4 MB is the census's PCM estimate**, not measured resident memory.
