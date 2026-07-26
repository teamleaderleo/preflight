# What prepared audio would have to hold (2026-07-26)

M5 says "reuse short fully decoded effects." Nothing in the repository said how many of those there
are, how much PCM they amount to, or which files the phrase excludes. The
[equivalence gate](../installed-jorbis-equivalence.md) proves five committed fixtures decode
correctly; it says nothing about the 2,141 Ogg files in the reviewed profile.

`preflight audio census` measures them without decoding any of them. It reads each file's
identification header and the granule position on its final page, which together give channel count,
sample rate, and frame count — so 2,141 files are sized in **two seconds**.

## The profile

| Class | Files | Encoded | Decoded |
| --- | ---: | ---: | ---: |
| declared effects | 1,803 | 125.1 MB | **1,172.6 MB** |
| declared music | 141 | 371.2 MB | 2,855.8 MB |
| declared nowhere | 197 | 19.7 MB | 226.1 MB |
| all | 2,141 | 516.1 MB | **4,254.5 MB** |

Vorbis expands **8.2-fold** across the profile and 9.4-fold across the effects alone. That ratio is
the first thing a prepared-audio policy has to answer to. Half a gigabyte of sound on disk is 4.25 GB
of PCM, and "cache the decoded effects" — with no further qualification — means writing 1.17 GB.

## Two thirds of it is not eligible, and directory names do not say which

Music is streamed, and M5 keeps it that way until a streaming policy is proven. It is 67% of the
decoded audio, so classifying it correctly is most of the sizing problem.

Path convention gets it wrong in both directions. `sounds/nsp_templar_theme.ogg` is seven minutes of
music with no `music` anywhere in its path; other mods keep short effects under a `music` directory.
Classifying by path put 44.8 MB and 38.8 MB of themes in the eligible set.

`data/config/sounds.json` is the authority: entries inside the top-level `"music"` object are music,
everything else is an effect. Two details in that file matter and neither is guessable.

An entry may name a `"source"`, in which case `"file"` is a member of an archive or directory rather
than a path in the profile. Core music lives in a 54 MB `sounds/music/music.bin`; mods use directory
sources like `"source":"sounds/wh/music"`, which sweep in every file beneath them. Reading only
`"file"` leaves 337 files looking undeclared. Expanding sources brings that to 197.

Commented-out entries are not loaded. Starsector's JSON permits `#` and `//` comments, and
`sounds.json` uses them heavily to disable entries in place, so a parser that ignores comments
collects sounds the game never touches.

The 197 that remain are declared nowhere at all — 226 MB of decoded audio in files no `sounds.json`
references, mostly leftovers in mods that ship more audio than they wire up. They should never be
prepared, and a cache keyed by walking the sound directories would prepare all of them.

## Duration decides almost everything

| Effect duration | Files | Decoded | Cumulative |
| --- | ---: | ---: | ---: |
| under 1 s | 410 | 26.7 MB | 26.7 MB |
| 1–5 s | 1,101 | 376.5 MB | 403.2 MB |
| 5–15 s | 237 | 335.1 MB | 738.3 MB |
| 15–60 s | 38 | 121.3 MB | 859.6 MB |
| over 60 s | 17 | 313.0 MB | 1,172.6 MB |

**17 files hold 313 MB** — 27% of the eligible bytes in under 1% of the eligible files. They are
ambient loops and mod themes declared as effects rather than music. Meanwhile the 1,511 effects under
five seconds — 84% of the files, and what "short fully decoded effects" plainly means — total 403 MB.

So a duration bound is worth more than any other single policy knob, and the exit condition's word
"short" is doing real work. It is worth making it a number.

## A third of the eligible bytes are ultrasonic

| Sample rate | Files | Decoded |
| --- | ---: | ---: |
| 192,000 Hz | 141 | 345.1 MB |
| 96,000 Hz | 54 | 45.9 MB |
| 48,000 Hz | 127 | 53.3 MB |
| 44,100 Hz | 1,378 | 688.4 MB |
| 32,000 Hz | 22 | 2.2 MB |
| 24,000 Hz | 23 | 11.8 MB |
| 22,050 Hz | 57 | 25.9 MB |

195 effect files are recorded at 96 kHz or above. They are **10.8% of the eligible files and 33.3% of
the eligible decoded bytes**: 391.0 MB for 992 seconds of audio that would occupy 100.3 MB at
44.1 kHz. Most belong to one mod — 124 to `uaf`, 38 to `ORK`.

This is not only a storage observation. Vorbis decode cost scales with sample rate, so 992 seconds at
192 kHz costs roughly four times the MDCT work of the same audio at 44.1 kHz. `Mdct.mdct_kernel` was
the single largest audio method in the
[2026-07-16 profile](2026-07-16-real-install-audio-startup-report.md) at 398 samples. Some meaningful
part of the audio share of startup CPU is spent reconstructing frequencies above 22 kHz.

Resampling is a **transformation**, not a cache, and it is not equivalent — it changes the PCM the
game receives, so it is out of scope for M5 and cannot be smuggled in under prepared audio. Recorded
here because it is the largest single distortion in the profile's audio cost and it belongs on the
record before anyone sizes a cache against these numbers.

## Two files in the profile cannot be decoded at all

The census reports both, and both are real:

| Path | Provider | Why |
| --- | --- | --- |
| `sounds/sfx_wpn_energy/melta_fire.ogg` | ORK | FLAC in an Ogg container, not Vorbis |
| `sounds/sfx_wpn_energy/bt_holy_aura_charge.ogg` | ORK | zero bytes |

`melta_fire.ogg` is 424,798 bytes whose first packet begins `\x7fFLAC`, and it is a **declared
effect** — `sounds.json` references it, so the game tries to load it. The zero-byte file is
undeclared.

Neither is exotic enough to be a curiosity. They are what a prepared-audio pass will meet on a real
machine, and the requirement they impose is that failure to decode is a classification, not an error:
`OggVorbisIdentification` returns `UNSUPPORTED` for the FLAC file and the empty one, the census counts
them separately, and their decoded contribution is zero rather than a guess.

## What this does not establish

The census reads containers. It does not decode, so it does not prove any of these files decode
correctly through the installed JOrbis — only the equivalence gate speaks to that, and only for its
five fixtures.

Frame counts come from the granule position, which is what the container declares. On four of the
five committed fixtures the installed decoder produces exactly that many frames; on
`packet-boundary-mono-44100`, which was built to end on an uneven packet, it produces 8,320 against a
declared 8,193, because JOrbis never trims its final block. **The measurement is therefore a floor,
tight to within one block per file** — under 0.1% on multi-second audio, and bounded by 8,192 frames.

This also corrects a claim in the
[gate rebuild](2026-07-26-the-audio-gate-decodes-an-api-the-game-never-calls.md). That document
explained the 256-mono/128-stereo difference as "libvorbis trims the final block against the last
page's granule position; JOrbis does not." The granule positions are 2,048 and 4,096, and the
installed decoder produces exactly 2,048 and 4,096 frames — it is the committed **libvorbis reference
files** that are 1,792 and 3,968, short of what their own containers declare. Trimming against the
granule position cannot be the explanation, because trimming to it is what JOrbis already does here.
Why the references are short is open; no local ffmpeg is available to re-derive them. The gate's
tolerance is unaffected — it bounds the difference rather than explaining it — but the explanation was
wrong and is corrected in place.

No eligibility decision is made here. `candidateEquivalence`, `equivalenceEstablished`,
prepared-audio reads and writes, and live transformation all remain false.
