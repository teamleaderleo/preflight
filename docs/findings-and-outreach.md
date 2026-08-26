# Findings and outreach

## TL;DR

Preflight finds a lot of weird things. **Most findings do not create an obligation to contact anybody.**

```text
finding
  ↓
Can Preflight handle it safely for users?
  ├─ yes → handle it, document it, stop
  └─ no / source fix is clearly better
          ↓
Clear owner + small durable fix + strong evidence?
  ├─ no → archive it, stop
  └─ yes → possible outreach
```

A good external report saves the recipient work. It shouldn't hand them a new cache, a vague theory, or responsibility for Preflight's internals.

## When a finding should leave the repo

Outreach is strongest when:

- the evidence is direct;
- one owner can act on it;
- the fix is local;
- people benefit without Preflight;
- the advice stays valid across machines/mod lists;
- the recipient doesn't inherit a new maintenance burden;
- the likely value clears the social cost.

A silent correctness defect clears that bar easily. A cross-cutting optimization usually belongs in Preflight.

## Current disposition

| Finding | Disposition | Why |
| --- | --- | --- |
| Four confirmed released-mod config defects | **Worth telling authors** | Specific silent behavior defects with small local fixes. |
| UAF/ORK high-sample-rate effects | **Optional author note** | Mechanical source-side optimization; every user benefits. |
| Progressive JPEG/Adam7 PNG | **General ecosystem guidance** | Actionable, but common enough that linter/docs beat dozens of messages. |
| ORK `melta_fire.ogg` contains FLAC in an Ogg container | **Verify once more, then consider reporting** | Container mismatch is proven; capture current player/log behavior first. |
| Broad NPOT texture padding | **Keep in Preflight** | NPOT art is normal across the ecosystem; runtime handling is the better fix. |
| Shared parsing/indexing/compilation/memoization work | **Keep in Preflight** | Cross-cutting invalidation/fallback belongs in one place. |
| Vanilla-looking runtime inefficiencies | **Keep a shortlist; isolate before outreach** | A huge modded trace isn't yet a clean base-game report. |

## Strongest correctness reports

[Config the game silently never reads](evidence/2026-07-28-config-the-game-silently-never-reads.md) records four released-mod defects checked by hand:

- **exshippack:** `PROXIMITY_FUSE` sits after the projectile's top-level object;
- **eusan_nation:** `fireSoundTwo` sits after the weapon object;
- **ORK:** `pirates.faction` closes before `priorityWeapons` and later content;
- **Mayasuran Navy:** a config begins `0{`.

Those are the cleanest “you probably want to know this exists” reports.

The MagicLib sample fragment found by the same rule stays out of the shortlist because it appears to be sample material rather than a file intended to load. Ambiguity is enough reason to leave somebody alone.

## High-sample-rate audio

The reviewed audio census found **195 declared effects at 96 kHz or above**, holding **391.0 MB** of decoded PCM for 992 seconds of audio. **124 belong to UAF and 38 to ORK.** See [What prepared audio would have to hold](evidence/2026-07-26-what-prepared-audio-would-have-to-hold.md).

Useful correction: the memorable UAF case is **not one giant music file**. It is 124 high-rate effects in the measured profile.

This is unusually good performance outreach because the fix can be as simple as re-exporting/resampling source audio. No cache, hook, API, or runtime dependency needs to be maintained afterward.

If this ever gets sent, a per-mod lint report with exact paths is enough. The message can stay boring: “these effects are exported at 96/192 kHz; the game fully decodes effects; an ordinary production rate would reduce decode work.”

## Progressive images

In the 86-mod sample, **41% of mod JPEGs were progressive**, carrying 25.9% of image pixels, and the measured ImageIO path decoded progressive images about **8.75× slower** than equivalent baseline images. See [What eighty-six mods ship](evidence/2026-07-28-what-eighty-six-mods-ship.md).

The source fix is simple, but the pattern is common. The linter and general modding guidance are a better communication channel than messaging every author.

## Things Preflight should absorb

### NPOT texture padding

**83.9% of mod images** in the sampled ecosystem are non-power-of-two. Most are normal sprite art. Asking authors to redesign four fifths of the ecosystem would be worse than fixing the broad cost at runtime.

The linter keeps only unusually expensive cases; Preflight handles the wider padding problem when the live graphics context supports it.

### Memoization, indexing, compilation, storage layout

The profiler found repeated JSON reads, runtime compilation, list validation, unchanged commodity calculations, texture queueing, and similar seams. [Engineering overview](engineering-overview.md) summarizes them.

Even when one mod amplifies a cost, asking every author to invent and maintain their own cache can create more failure points than it removes. Preflight already owns identity, invalidation, fallback, and evidence for these cross-cutting shortcuts.

## Vanilla / base-game candidates

Interesting observations include eager effect loading, texture queue/padding behavior, repeated collection validation, and recomputation of unchanged state.

They become upstream reports only after this step:

```text
large modded-profile observation
        ↓
small reproduction
        ↓
show it belongs to vanilla/API behavior
        ↓
measure the isolated consequence
        ↓
then consider outreach
```

The audio case shows why. All 2,050 declared effects in the reviewed profile were opened inside a 1.5-second pre-menu burst, yet the loading thread does **not** wait for that audio pool. The observation is real; “this directly adds 1.2 GB worth of startup delay” would be wrong. See [The game builds 1.2 GB of PCM before the main menu](evidence/2026-07-29-the-game-builds-1-2-gb-of-pcm-before-the-main-menu.md) and its linked correction.

So the bar for telling Fractal should be higher than the bar for keeping useful evidence in Preflight.

## One item that needs another check

`ORK/sounds/sfx_wpn_energy/melta_fire.ogg` is a declared effect whose Ogg container contains FLAC rather than Vorbis. The census proves the mismatch.

Before contacting the author, capture the current user-visible or log behavior on the current mod version. Then the report can say what actually fails instead of stopping at “our Vorbis reader rejects this container.”

## What a report should contain

```text
exact file/version
what was observed
small reproduction or measurement
small likely fix
one evidence link, if useful
```

No ranking. No giant Preflight history. No bundle of unrelated findings unless the recipient asks for one.

## The zero-outreach workflow

It is completely valid to:

1. keep the finding in lint/evidence output;
2. classify it here;
3. fix around it in Preflight when that gives users the best result;
4. publish general guidance authors can discover themselves;
5. contact nobody unless a finding clears the bar above.

Keep this page small. The linter owns the thousands of individual findings; this page only owns the few decisions about what, if anything, should leave the repository.