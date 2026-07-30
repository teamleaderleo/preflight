# The game builds 940 MB of PCM before the main menu

*2026-07-29* — **Corrected 2026-07-29.** The first version of this page called the session
"menus only"; the player had in fact entered the campaign and flown on the map, without combat. It
also claimed music is never opened at load, which the recording does not support. Both are fixed
below, and the second correction exposed a gap in the probe that is now reported rather than silent.

M5 has been sized but never justified. [The audio census](../audio-census.md) established what
prepared audio would have to hold; nothing established that the game builds any of it during load
rather than on first play. If loading were lazy, the milestone would move work out of moments nobody
is timing and should not be built.

One launch settled it.

## The run

A single session on the reviewed profile, recorded with
[`run --trace-all-file-reads`](../audio-decode-probe.md). Six minutes, reaching the campaign map and
flying on it; no combat. 418,588 file reads captured, of which 14,679 audio reads resolved to a file
the census declares and **7,309 did not**.

| | declared | opened | PCM behind opened | PCM behind unopened |
| --- | ---: | ---: | ---: | ---: |
| effects | 2,050 | **1,278** | **940.3 MB** | 229.1 MB |
| music | 156 | 0 | 0 | 2,890.1 MB |
| unreferenced | 220 | 0 | 0 | 220.2 MB |

Three facts come out of that table, and the second and third were not what the probe was built to
find.

**Effect loading is eager, and it is a bulk load.** Every one of the 1,278 files was opened inside a
**1.5-second window**, 23 seconds into a 360-second session, and all 14,679 audio reads came from a
single caller. Loading spread across a session is what on-demand looks like. This is not that.

**Music is a container, and this probe could not see it.** Zero of the 156 individually declared
music files were opened — but the recording holds **1,806 reads of `sounds/music/music.bin`**, 14.6 MB,
which the census has no entry for because vanilla music is not shipped as separate files at all. The
first version of this page read that zero as "music is never decoded at load". It supports no such
thing. What can be said is narrower: the *declared* music files were not opened, and what the game
does with `music.bin` is unmeasured.

**Nothing opens the unreferenced files.** Zero of 220. The census called them unreferenced because no
`sounds.json` names them; this is the first direct confirmation that the game agrees. It is also a
free validation of the `audio-unreferenced` lint rule, which had only ever been argued from the
config.

## What the 772 unopened effects are

They are campaign ability sounds: `ability-emergency_burn`, `ability-go_dark`,
`ability-interdiction_pulse`, `ability-neutrino_detector` and their siblings.

The session **did** reach the campaign map, which makes this more interesting than it first looked.
Every first-open in the run falls between 23.2 s and 24.7 s — **no sound file was opened at all after
the 24.7-second mark**, across five and a half further minutes that included flying on the map. So
entering the campaign added nothing. These are sounds for actions the player did not take, and
whether they load on use or never is not answered here.

940.3 MB is still a **floor**, for two reasons now. Combat was never entered. And a third of the
run's audio reads matched no declared file, so the opened set is undercounted by an amount this run
cannot bound.

## The verdict logic was wrong, and this run is what exposed it

The probe first reported `INCONCLUSIVE`. It compared the opened fraction — 62% — against thresholds
of 90% for eager and 10% for lazy, and 62% fell between them.

That was a badly posed test, and the fault is in the design rather than the thresholds. *Lazy* means
**at the time of use**, so *when* files are opened is the direct evidence and *how many* is only a
proxy. A partial fraction is not ambiguity when every open lands inside 1.5 seconds of a six-minute
session; it means the session did not reach a later phase.

The verdict now keys on whether the opens are a burst — a first-open window under 5% of the session,
across at least twenty files — and states explicitly when coverage is partial. Both the original
tests and the new ones survive.

Worth being plain about the risk: this logic changed after seeing the data it now interprets. The
defence is that the new test is *more* principled than the one it replaces, not that it produces a
nicer answer, and the guard against a single open registering as a perfect burst is a test.

## The gap this correction exposed

Chasing the music claim showed the probe was silently discarding every audio read it could not map to
a declared file — 7,309 of 21,988, a third of the audio in the recording. Among them is `music.bin`,
and among them are core effects like `sounds/sfx_impacts/shield_hit_heavy_01.ogg` and
`sounds/sfx_wpn_guns/light_mass_driver_fire_01.ogg`, which live under
`Contents/Resources/Java/sounds/` on this install layout.

A tool that summarises a recording must not drop a third of the relevant evidence without saying so.
The probe now counts unmatched audio reads, names a sample of them, and prints the share above its
own findings. Why those core paths fail to resolve is not diagnosed here and is tracked separately —
the point of this correction is that the number is visible instead of absent.

## What this means for M5

The premise holds. Prepared audio for effects targets real, repeated, pre-menu work — at minimum 940
MB of PCM built on every launch, from files whose identity the census already knows.

It does not license the whole milestone:

- **Music stays out**, on the same caution as before. This run did not strengthen that case, and
  briefly appeared to until the container was found.
- **The 220 unreferenced files stay out.** Nothing loads them.
- **The 940 MB is a floor**, from a session that reached the map but not combat, and that could not
  account for a third of its own audio reads. A combat run should be measured, against a probe that
  resolves everything it reads, before any cache is sized.
- **Reads are still not decodes.** The burst is consistent with eager decoding and hard to explain
  otherwise, but the direct evidence is that the files are opened, and the equivalence work in
  [#207/#208](../roadmap.md) remains what proves what the decoder does with them.
