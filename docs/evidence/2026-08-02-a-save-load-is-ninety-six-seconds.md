# A save load is 96 seconds, and XStream is 14% of it

**Date:** 2026-08-02
**Install:** Starsector 0.98a-RC8, 77 enabled mods, macOS 15, M5 MacBook Air
**Save:** `save_LindseyEulalia_7487418333814238931`, 42.7 MB, Cycle 206
**Method:** the game driven through its own UI, timed from the log's own millisecond stamps
**Status:** the anchor measurement the save analysis was missing. **Coarse attribution, not a
profile** -- see the method caveats.

Every previous document in this line ended with the same admission: *nobody has measured how long a
Starsector save actually takes to load.* Now somebody has.

## 96 seconds

The load spans log timestamps **147.3s to 243.3s** — **96.0 seconds** — for a 42.7 MB save. That is
**longer than the entire optimized startup this project just spent three campaigns getting down to
62.6s.**

So the question "is there a save-loading problem worth solving" is settled: there is, and it is
bigger than the one already solved.

**1,469 of the 1,480 log lines in that window are on `main`.** The load is essentially
single-threaded on a ten-core machine.

## Where the 96 seconds go

Each interval between consecutive log lines is attributed to the subsystem that *ends* it:

| bucket | seconds | share |
| --- | ---: | ---: |
| **vanilla: intel / mission generation** | **28.8** | **30.2%** |
| **MOD: Nexerelin (`exerelin`)** | **20.9** | **21.9%** |
| **vanilla: JSON/CSV/spec loading (`LoadingUtils`)** | **17.1** | **18.0%** |
| vanilla: XStream save read (`CampaignGameManager`) | 13.3 | 14.0% |
| vanilla: texture loading | 9.0 | 9.5% |
| MOD: MagicLib etc (`org.*`) | 5.1 | 5.4% |
| everything else (7 mods) | ~1.1 | ~1.2% |
| **mod code total** | **27.1** | **28.4%** |
| **vanilla total** | **68.3** | **71.6%** |

## What this settles

**XStream deserialisation is 13.3 seconds — 14%.** The
[synthetic estimate](2026-08-02-save-loading-is-not-parsing.md) put generic XStream work near 3.3s,
so it was low by about 4x — real Starsector classes are richer than the synthetic graph, exactly as
that document warned. But its *conclusion* holds and is now measured rather than inferred: **86% of
a save load is not XStream**, and every idea aimed at the serialiser was aimed at a seventh of the
problem.

That retroactively vindicates rejecting the two ideas that were rejected. The reference pre-scan
would have chased ~40 ms inside a 13.3-second slice of a 96-second load. A binary intermediate
format that eliminated XML parsing entirely would have bought at most the 685 ms measured earlier —
**0.7% of a save load.**

**The biggest single item is intel and mission generation, at 30%.** `AnalyzeEntityMissionIntel`,
`SurveyPlanetMissionIntel` and their kin, regenerating procedural content on every load. This is the
"post-load fixup" the earlier document guessed at without evidence, and it is the largest thing in
the window. It is also *computation*, not I/O — which makes it a poor fit for a cache and a good fit
for whatever Fractal would want to do about it.

**The JSON/spec path is paid again on every save load — 17.1 seconds of it.** This is the finding
that changes priorities most. The spec corpus was
[reframed](2026-08-02-what-is-left-measured-without-launching.md) as a file-count problem worth
attacking through the resource index; it now turns out that work is not a startup-only lever. It is
paid at launch **and again every time a save is loaded**, which for an actual player is many times
per session.

**Textures are 9.5%, and our cache already covers that path.** Whatever the prepared-pixel and
prefetch work is worth at startup, some fraction of it applies here too. Unmeasured, but it is the
one bucket Preflight already has a mechanism for.

## Method caveats, which are real

- **This is not a profile.** The JFR recording stopped sampling at 06:32:22, fifty-five seconds
  before the save was clicked, so there are no execution samples for this window. Every number here
  comes from attributing the wall-clock gap between two log lines to the logger that wrote the
  second one. Work by a subsystem that logs nothing is invisible, and work that logs frequently is
  credited generously.
- **Thermally constrained machine, no room cooling**, and the operator was interacting with the
  game. Treat 96 seconds as the right order of magnitude, not a benchmark figure.
- **One save, one mod set, one machine.** Nexerelin at 22% is a property of this profile; a
  different mod list moves the mod share a lot.
- Fixing the JFR to cover the save-load window is the obvious next step and would replace the
  coarse attribution above with real frames.

## What it changes

1. **The resource-index / JSON work moves up.** It is now the only item known to pay at both startup
   and every save load, and it is 18% of a 96-second load on top of whatever it is worth at launch.
2. **Save loading is worth attacking, and not through the serialiser.** 86% of it sits outside
   XStream.
3. **Single-threadedness is the shape of the problem.** 1,469 of 1,480 lines on `main`, on a
   ten-core machine — the same structural fact that made the startup prefetcher a 27-second wait.
   Fast Rendering's answer to that at startup was to parallelise it, and nothing here is parallel.
