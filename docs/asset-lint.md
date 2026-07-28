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

Every threshold here was tuned against one profile. Running `--path` over all 74 installed mod
directories gives 74 independent samples: **median 0 findings, 40 of 74 completely clean**, and no
rule firing on more than a third of mods. Most mods are fine, which is the result to want from a tool
that reports on other people's work.

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

1,266 findings across 73 resource roots: **740.7 MB in video memory, 687.2 MB decoded at load, 88.0 MB
on disk.**

| Rule | Findings | Bytes |
| --- | ---: | ---: |
| `texture-progressive` | 279 | — (389.4 Mpixel, ~8.75× decode) |
| `texture-npot-padding` | 274 | 740.7 MB VRAM |
| `audio-oversampled` | 250 | 374.2 MB decoded |
| `audio-unreferenced` | 196 | 19.7 MB disk |
| `asset-editor-source` | 94 | 41.2 MB disk |
| `asset-duplicate-content` | 87 | 9.5 MB disk |
| `asset-shadowed` | 63 | 17.6 MB disk |
| `audio-long-effect` | 17 | 313.0 MB decoded |
| `asset-extension-mismatch` | 4 | — |
| `audio-undecodable` | 2 | — |

`sound-declared-missing` finds nothing here, which is the result to want: every one of the 1,823
declared sound files is supplied by some mod. It is exercised synthetically instead.

The editor-source total is 94 files a user downloads and stores that the game never opens — 77 of
them `.pdn` files in a single mod, including one pair named `orbital.pdn` and `orbital - copy.pdn`.

`texture-progressive` is the most actionable of the nine. 41% of the profile's JPEGs are stored
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
