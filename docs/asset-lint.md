# Asset lint

`preflight lint` reports asset problems in a profile, attributed to the mod that ships them.

```bash
java -jar preflight.jar lint --path ./MyMod                        # one mod, on its own
java -jar preflight.jar lint --game "/path/to/Starsector"          # a whole profile
java -jar preflight.jar lint --game "/path/to/Starsector" --mod uaf
java -jar preflight.jar lint --game "/path/to/Starsector" --json --output lint.json
```

`--path` lints a single mod directory with no profile around it — the shape a mod author actually
works in. `--game` resolves a full profile, with override resolution and cross-mod rules.

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
| `texture-progressive` | warning | — | Stored for progressive display (progressive JPEG, Adam7 PNG). ImageIO decodes these about 8.75× slower than the same image stored normally, [measured](evidence/2026-07-28-progressive-jpeg-costs-nine-times-the-decode.md). |
| `asset-extension-mismatch` | info | — | Contents are a different image format from the one the name claims. |
| `sound-declared-missing` | error | — | Named by a `sounds.json` but supplied by no mod, so the game has nothing to play. Covers every declared extension, not just `.ogg`. |
| `asset-editor-source` | info | disk | An editor project file (`.pdn`, `.psd`, `.xcf`, `.kra`, `.aseprite`, `.blend`, …). The game reads none of them. |
| `asset-duplicate-content` | info | disk | Byte-for-byte identical to a file at a different path. Reported above 64 KB. |
| `asset-shadowed` | info | disk | A later mod provides the same path, so this copy never loads. Reported above 64 KB. |
| `config-unparseable` | error | — | A bracket, string or comment that never closes, or a file that never opens an object or array. No reader can finish it. |
| `config-unread-content` | error | — | Configuration sitting *after* the top-level value has closed, so a reader that takes one value never applies it. |

The two config rules read `.json`, `.variant`, `.wpn`, `.ship`, `.proj`, `.system`, `.skin`,
`.faction` and `.skill` — 15,353 files in the reviewed profile. They check structure only. The
dialect accepts `#` and `//` comments, trailing commas, unquoted keys and numeric suffixes like
`0.1f`, all of which shipping mods rely on, and a strict JSON reader pointed at this ecosystem
reports almost all of it as broken.

Trailing brackets are ignored: 27 files across MagicLib, Nexerelin and Arma Armatura end with one
brace too many and all of them work, because a reader consumes one value and stops.
`config-unread-content` needs an actual key out there, not punctuation. These are also the only rules
reported against *every* mod that ships the file rather than only the override winner — the other
rules are about bytes, where only the loaded copy costs anything, while this one is about an author's
file being wrong. See [the evidence](evidence/2026-07-28-config-the-game-silently-never-reads.md).

Classification of sound comes from `sounds.json`, never from directory naming — see
[the audio census](audio-census.md). Texture padding uses `GpuTextureFootprint.paddingBytes`, the same
calculation the prepared-pixel work measured against a live process.

Only the override winner at each logical path is examined for cost, since that is the file the game
loads. `asset-shadowed` is the exception — it is about the copies that do not.

`asset-editor-source` deliberately excludes `.wav`. It looks like an intermediate format but
Starsector plays it, and twenty effects in the reviewed profile are declared as `.wav`; flagging it
would tell authors to delete working sound.

Duplicate detection hashes only files that share an exact byte length, since two files cannot be
identical at different sizes. That reduces 48,875 files to a couple of hundred candidates and keeps
the whole run at about six seconds.

Two rules would otherwise count the same bytes twice: the same path in two mods is `asset-shadowed`,
not `asset-duplicate-content`, and a test enforces that a shadowed path produces exactly one finding.

### Size floors

`asset-shadowed` and `asset-duplicate-content` ignore files under 64 KB, and
`texture-npot-padding` ignores under 1 MB of waste. Without the shadowed floor the reviewed profile
reports 1,841 shadowed copies averaging 15 KB — 67% of all findings, for 27.6 MB of disk whose intent
the linter cannot read. Most shadowing is deliberate. The floor keeps the cases where real content is
being replaced and drops the ones that would bury every other rule.

## Three rules need a whole profile

`--path` suppresses these rather than answering them from a directory that cannot know:

| rule | why |
| --- | --- |
| `asset-shadowed` | compares providers, and there is only one |
| `sound-declared-missing` | would fire on every core sound a mod legitimately reuses |
| `audio-unreferenced` | another mod's `sounds.json` may declare these files |

The third was found by cross-checking both modes: `knights_of_ludd` reports sixteen unreferenced
sounds alone and none in the profile, because a companion mod declares them. See
[the evidence](evidence/2026-07-28-linting-one-mod-alone.md). Standalone reports name the suppressed
rules in `rulesRequiringAProfile`, so a clean result does not read as a stronger claim than it is.

## Calibration

Every threshold here was tuned against one profile, so `--path` was run over 86 installed mod
directories as 86 independent samples: **median 0 findings, 44 of 86 completely clean**, and no rule
firing on more than a third of mods. Firing rates on the 13 mods installed *after* the thresholds
were set sit within a few points of the overall rates. Most mods are fine, which is the result to
want from a tool that reports on other people's work.
[The evidence](evidence/2026-07-28-what-eighty-six-mods-ship.md) also records two ecosystem rates:
41% of mod JPEGs are stored progressively, carrying 26% of all mod image pixels, and 83.9% of mod
images are not powers of two.

That second figure is why `texture-npot-padding` has a 1 MB floor and says out loud that
non-power-of-two is normal. Sprite art is whatever size the sprite is; padding it would be worse.
The floor admits 288 findings out of 23,571 NPOT files, the smallest 176 px on its shorter edge, so
what survives is large art where rounding up costs megabytes. The remaining 2.2 GB is a runtime
problem for padding removal to solve, not something to ask four fifths of authors to redraw.

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

## Measured against the reviewed profile (2026-07-28, 84 roots)

1,392 findings across 84 resource roots: **771.9 MB in video memory, 687.9 MB decoded at load,
100.8 MB on disk** — and 6 findings at `error` severity, which cost no bytes at all and matter more
than any of those totals.

| Rule | Findings | Bytes |
| --- | ---: | ---: |
| `texture-npot-padding` | 288 | 771.9 MB VRAM |
| `texture-progressive` | 285 | — (386 Mpixel, ~8.75× decode) |
| `audio-oversampled` | 256 | 374.9 MB decoded |
| `audio-unreferenced` | 219 | 20.6 MB disk |
| `asset-duplicate-content` | 113 | 11.4 MB disk |
| `asset-editor-source` | 94 | 41.2 MB disk |
| `asset-shadowed` | 85 | 27.7 MB disk |
| `asset-extension-mismatch` | 28 | — |
| `audio-long-effect` | 17 | 313.0 MB decoded |
| `config-unread-content` | 3 | — |
| `audio-undecodable` | 2 | — |
| `config-unparseable` | 2 | — |

The five config findings are the only ones here that describe something *broken* rather than
something expensive, and four of the five are real defects in released mods: a missile whose
`PROXIMITY_FUSE` block sits outside the top-level object and therefore does nothing, a weapon whose
`fireSoundTwo` never plays, a faction file that closes early and drops everything from
`priorityWeapons` onward, and a config that begins `0{`. Five findings in 15,353 config files is the
signal-to-noise these rules were calibrated for.

`sound-declared-missing` finds nothing here, and has now seen 83 enabled mods across two profile
sizes without firing once. That is the result to want — every declared sound file is supplied by some
mod — and it is exercised synthetically instead.

The editor-source total is 94 files a user downloads and stores that the game never opens — 77 of
them `.pdn` files in a single mod, including one pair named `orbital.pdn` and `orbital - copy.pdn`.

`texture-progressive` is the most actionable of the cost rules. 41% of the profile's JPEGs are stored
progressively, carrying more pixels than all the baseline ones combined, and decode is two thirds of
what loading a texture costs. The fix changes no pixels.

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
