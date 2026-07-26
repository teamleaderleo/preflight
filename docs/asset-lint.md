# Asset lint

`preflight lint` reports asset problems in a profile, attributed to the mod that ships them.

```bash
java -jar preflight.jar lint --game "/path/to/Starsector"
java -jar preflight.jar lint --game "/path/to/Starsector" --mod uaf
java -jar preflight.jar lint --game "/path/to/Starsector" --json --output lint.json
```

It reads file headers. It never edits, moves, or rewrites anything, and it always exits `0` — these
are findings offered to someone, not a build gate imposed on them.

## Why this exists

Everything else in this repository works *around* whatever a profile contains: caches, adapters,
equivalence gates, kill switches. All of that machinery exists because changing what a running game
sees is dangerous.

A mod author fixing an asset needs none of it. If one mod re-encodes 176 sound effects from 192 kHz
to 44.1 kHz, every user of that mod loads faster, with no cache, no hook, and nothing to fail open
from. That is better leverage than anything Preflight can do at runtime, and it uses the same
analysis — just pointed at the source instead of routed around it.

## Tone is a design constraint

The audience did not ask for this and is entitled to disagree with it. So:

- Every rule states a **measured cost** and **why it is paid**. A finding an author cannot act on, or
  cannot evaluate, is noise.
- Rules are deliberately few. A tool that tells strangers their work is wrong pays a much higher
  price for a false positive than for a missing check. A rule earns its place only when the cost is
  measurable from the file itself and does not depend on artistic intent.
- Nothing is described as a mistake. `audio-unreferenced` says a file may be played from code or may
  be a leftover, because the linter genuinely cannot tell.
- There is no score, grade, or ranking of mods.

## Rules

| Rule | Severity | Cost | What it means |
| --- | --- | --- | --- |
| `audio-undecodable` | error | — | Not decodable as Ogg Vorbis. The game decodes only Vorbis in an Ogg container, whatever the file is named. Downgraded to info when nothing declares the file. |
| `audio-oversampled` | warning | decoded | Recorded at 96 kHz or above. Vorbis decode cost scales with sample rate, and content above ~22 kHz is not reproduced by consumer playback. 48 kHz is an ordinary production rate and is not flagged. |
| `audio-long-effect` | warning | decoded | Declared outside the music section and over 60 seconds, so it is decoded in full at load rather than streamed. |
| `audio-unreferenced` | info | disk | Shipped but named by no `sounds.json` in the profile. |
| `texture-npot-padding` | warning | VRAM | Dimensions are not powers of two, so the stock loader uploads into the next power-of-two buffer and the remainder holds nothing. Reported above 1 MB of padding. |

Classification of sound comes from `sounds.json`, never from directory naming — see
[the audio census](audio-census.md). Texture padding uses `GpuTextureFootprint.paddingBytes`, the same
calculation the prepared-pixel work measured against a live process.

Only the override winner at each logical path is examined, since that is the file the game loads.

## Byte totals are never combined

Findings carry one of three cost kinds, and the report keeps them apart:

| Cost | Resource |
| --- | --- |
| `disk` | bytes shipped |
| `decoded` | PCM the game builds in memory while loading |
| `vram` | bytes resident in video memory |

A megabyte of video memory and a megabyte of disk are different problems. Summing them would produce
a headline that overstates every finding it contains, so no combined figure exists anywhere in the
report or the prose output — enforced by a test.

## Measured against the reviewed profile (2026-07-26)

739 findings across 73 resource roots: **740.7 MB in video memory, 687.2 MB decoded at load, 19.7 MB
on disk.**

The video-memory figure is the same waste the
[NPOT padding removal](evidence/2026-07-26-padding-removal-needs-no-instruction-surgery.md) targets
from the runtime side. The two approaches are complementary: mods can stop shipping non-power-of-two
art, and Preflight can stop padding it on hardware that supports `GL_ARB_texture_non_power_of_two`.
Neither makes the other redundant, because most users run mods they did not write.

## What it does not do

No fixes are applied and none are offered. A transform mode — resampling, stripping unreferenced
files, re-encoding — would touch other people's assets and needs its own safety story, which does not
exist yet.

It also cannot see references made from mod code. `audio-unreferenced` means "no `sounds.json` names
this", not "this is dead".
