# The game builds 940 MB of PCM before the main menu

*2026-07-29*

M5 has been sized but never justified. [The audio census](../audio-census.md) established what
prepared audio would have to hold; nothing established that the game builds any of it during load
rather than on first play. If loading were lazy, the milestone would move work out of moments nobody
is timing and should not be built.

One launch settled it.

## The run

A single session on the reviewed profile, recorded with
[`run --trace-all-file-reads`](../audio-decode-probe.md). Six minutes, menus only — no campaign was
entered. 418,588 file reads captured, 14,679 of them audio.

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

**Music is never touched at load.** Zero of 156 files, holding 2,890 MB of PCM — more than three
times the effect total. Whatever the game does with music, it does not decode it up front. M5's
"preserve streaming music until its policy is proven safe" was the right call for a reason nobody had
measured.

**Nothing opens the unreferenced files.** Zero of 220. The census called them unreferenced because no
`sounds.json` names them; this is the first direct confirmation that the game agrees. It is also a
free validation of the `audio-unreferenced` lint rule, which had only ever been argued from the
config.

## What the 772 unopened effects are

They are campaign sounds. The unopened sample is `ability-emergency_burn`, `ability-go_dark`,
`ability-interdiction_pulse`, `ability-neutrino_detector` and their siblings — abilities that only
exist once a campaign is running, in a session that never left the menus.

So 940.3 MB is a **floor**, not a total. A run that entered a campaign would open more. What the
number establishes is that at least 940 MB of PCM is built before the player sees the main menu, on
every launch.

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

## What this means for M5

The premise holds. Prepared audio for effects targets real, repeated, pre-menu work — at minimum 940
MB of PCM built on every launch, from files whose identity the census already knows.

It does not license the whole milestone:

- **Music stays out**, now on evidence rather than caution.
- **The 220 unreferenced files stay out.** Nothing loads them.
- **The 940 MB is a floor from a menus-only session.** A campaign run should be measured before any
  cache is sized.
- **Reads are still not decodes.** The burst is consistent with eager decoding and hard to explain
  otherwise, but the direct evidence is that the files are opened, and the equivalence work in
  [#207/#208](../roadmap.md) remains what proves what the decoder does with them.
