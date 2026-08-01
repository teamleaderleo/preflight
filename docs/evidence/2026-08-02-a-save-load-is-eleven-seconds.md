# A save load is 11.3 seconds, and two thirds of it is the JSON/spec path

> **This file was first published claiming a save load takes 96 seconds. That was wrong by 8.5x.**
> The 96-second window was measured by waiting for the game's log to go quiet, while the operator
> was *playing the game*, so roughly 78 seconds of ordinary campaign ticks — mission generation,
> Nexerelin fleet spawning, diplomacy events — were attributed to loading. The operator caught it
> immediately: "loading the file did not take 96 tho it was more like 6 at most end to end."
> They were right. The whole document below is the corrected measurement, and the original
> attribution table has been deleted rather than kept, because every row in it was wrong.
>
> The lesson is specific and worth keeping: **"the log stopped changing" is not "the work
> finished."** The game logs during play. The correct boundary was available the whole time in the
> game's own lifecycle markers, and I did not look for it before publishing.

**Date:** 2026-08-02
**Install:** Starsector 0.98a-RC8, 77 enabled mods, macOS 15, M5 MacBook Air, **no thermal
constraint**
**Save:** `save_LindseyEulalia_7487418333814238931`, 42.7 MB, Cycle 206
**Method:** the game driven through its own UI; boundaries taken from `CampaignGameManager`'s own
staging lines, timing from the log's millisecond stamps
**Status:** the anchor measurement the save analysis was missing. Coarse attribution, not a profile.

## The real numbers

`CampaignGameManager` announces its own stages, which makes the boundaries unambiguous:

```
158.63s   Loading .../saves/save_LindseyEulalia_7487418333814238931
158.68s   Loading stage 2, stage 3
165.37s   Loading stage 4 ... 29, "Finished loading"
166.68s   Loading stage 30 ... 33
169.34s   Loading stage 34, 35
169.94s   Loading stage 36 ... "stage 39 - last"
--------  gameplay begins
174.27s   GateScanner: CheckForGates took 5ms
194.15s   DiplomacyManager: Starting diplomacy event creation
```

| boundary | elapsed |
| --- | ---: |
| `Loading <save>` to **`Finished loading`** | **6.74 s** |
| `Loading <save>` to `stage 39 - last` | **11.31 s** |

The operator's stopwatch impression of "6 at most, click to gameplay resumption" matches
`Finished loading` exactly. The remaining 4.6 s of staging runs while the campaign screen is already
coming up.

## Where the 11.31 seconds go

Attributing each interval between consecutive `main` log lines to the subsystem that ends it, over
the true window only:

| bucket | seconds | share |
| --- | ---: | ---: |
| **vanilla: JSON/CSV/spec loading (`LoadingUtils`)** | **7.10** | **63.1%** |
| vanilla: XStream save read | 1.97 | 17.5% |
| MOD: MagicLib (`org.*`) | 1.34 | 11.9% |
| MOD: `boggled` | 0.60 | 5.3% |
| vanilla: texture loading | 0.06 | 0.6% |
| everything else (6 mods) | ~0.14 | ~1.3% |
| **mod code total** | **2.12** | **18.8%** |
| **vanilla total** | **9.14** | **81.2%** |

All 1,372 log lines in the window are on `main`. The load is single-threaded.

The single largest stall is one gap:

```
+5.48s  ->  164.85s  LoadingUtils  Loading JSON from [DIRECTORY: .../Contents/Resources/Java/...]
```

**One 5.5-second pause, ending in a JSON directory load.** That is half the entire save load.

## What this settles

**The JSON/spec path is 63% of a save load.** This is the headline, and it is a much stronger
result than the wrong one it replaces. That corpus — 13,069 JSON and CSV files across 84 resource
roots, only 40.9 MB but resolved through a per-root `File.exists` walk — was already
[reframed](2026-08-02-what-is-left-measured-without-launching.md) as a file-count problem worth
attacking through `ResourceIndex`. It now turns out to be:

- paid at **startup**, and
- paid again on **every save load**, where it is **two thirds of the time**.

For a player who reloads after a lost fight, that is the cost they feel most often. It makes the
resource-index work the clear top priority, ahead of Janino and well ahead of anything in the
serialiser.

**XStream is 1.97 s — 17.5%.** The [synthetic estimate](2026-08-02-save-loading-is-not-parsing.md)
projected ~3.3 s for a 103 MB save; this is a 42.7 MB save, so the two are consistent. That
document's conclusion holds and is now measured: **the serialiser is not the bottleneck.**

Both rejected save ideas stay rejected, with better arithmetic behind it:

- the reference pre-scan chased ~40 ms of `HashMap.put` inside a 1.97 s slice of an 11.31 s load;
- a binary format eliminating XML parsing entirely buys the parse fraction of that 1.97 s — on the
  order of **0.3 s**, under 3% of a load, in exchange for a cache, a prepare step and an
  invalidation story.

**Mod code is 18.8%**, and most of it is two mods. Not nothing, but not the story either.

## Method caveats

- **This is not a profile.** The JFR recording stopped sampling 55 seconds before the save was
  clicked, so there are no execution samples for this window. Everything here is wall-clock gap
  between log lines, attributed to whoever wrote the second line. Work that logs nothing is
  invisible; chatty subsystems are credited generously. **Getting the recording to cover a save load
  is the obvious next step**, and it would replace this attribution with real frames — including
  telling us what that 5.5-second JSON stall is actually doing.
- One save, one mod set, one machine. The machine was **not** thermally constrained.
- The 63% figure rests substantially on a single 5.48 s gap. A profile could show that gap is
  something other than JSON parsing that merely happens to be followed by a JSON log line.
