# The audio equivalence gate decodes an API the game never calls (2026-07-26)

The installed-JOrbis equivalence gate ([doc](../installed-jorbis-equivalence.md), #89) was built to be
the last thing standing between the project and prepared audio. It had never been run against the
reviewed installation. Running it fails all five valid fixtures — and the reason is not the fixtures.

## Running it

Both decoder archives in the reviewed installation match the pinned digests exactly:

```text
jogg-0.0.7.jar    ed7946260897d97c468a4749b3d9d5e436a268fa948bc32e75a7487130e89379
jorbis-0.0.15.jar d049b2a1c6ddefde3a5cbff320c96fdd5aefa09b0d3bbea3fe44839f7e6713f9
```

The gate reports `identityExact = true`, `invalidBehaviorStable = true`, `equivalent = false`. All six
malformed-input cases behave exactly as expected. Every valid case disagrees on PCM:

| Fixture | expected bytes | actual bytes | PCM hash |
| --- | ---: | ---: | --- |
| `mono-22050` | 3,584 | 4,096 | differs |
| `stereo-44100` | 15,872 | 16,384 | differs |
| `silence-mono-8000` | 3,584 | 4,096 | differs |
| `clipping-stereo-48000` | 15,872 | 16,384 | differs |
| `packet-boundary-mono-44100` | 16,640 | 16,640 | differs |

The constant 512-byte excess looked like a tail-trimming difference, which would have been an ordinary
finding. It is not what is happening.

## The decoded audio is almost entirely silent

Decoding the fixtures through the gate's own decode path and counting non-zero samples:

| Fixture | non-zero samples | peak |
| --- | ---: | ---: |
| `mono-22050` | 509 / 2,048 | 30,570 |
| `stereo-44100` | 256 / 8,192 | 28,180 |
| `clipping-stereo-48000` | **0 / 8,192** | 0 |
| `packet-boundary-mono-44100` | 1,532 / 8,320 | 32,768 |

A fixture whose entire purpose is clipping stress decodes to digital silence. The non-zero samples that
do survive sit in isolated block-aligned runs — `513–639`, `897–1151`, `1409–1535` for the mono case —
which is the overlap-lap regions surviving while the block bodies are dropped.

The decode loop itself is not the problem. `read()` returning `0` really is end of stream here:
calling it forty more times past the first zero yields zero further bytes on every fixture.

## The gate decodes through `VorbisFile`. Nothing in the game does.

`InstalledJorbisEquivalenceChild` decodes exclusively through
`com.jcraft.jorbis.VorbisFile.read(byte[], int, int, int, int, int[])`.

Disassembling every class in the shipped `fs.sound_obf.jar`, exactly one class references the decoder
at all — `sound/void` — and it uses only the low-level API:

```text
com/jcraft/jogg/SyncState.init/buffer/wrote/pageout/data/clear
com/jcraft/jogg/StreamState.init/pagein/packetout/clear
com/jcraft/jogg/Page.serialno/eos
com/jcraft/jorbis/Info.init/synthesis_headerin/channels/rate/clear
com/jcraft/jorbis/DspState.synthesis_init/synthesis_blockin/synthesis_pcmout/synthesis_read/clear
com/jcraft/jorbis/Block.init/synthesis/clear
com/jcraft/jorbis/Comment.init
```

`VorbisFile` does not appear. A string scan across all 98 JARs in the reviewed installation — 26 core
archives and 72 belonging to the 75 resolved mods — finds no reference to it anywhere. The class exists
in `jorbis-0.0.15.jar`; nothing on the machine calls it.

This was already recorded and not noticed. The
[2026-07-16 real-install report](2026-07-16-real-install-audio-startup-report.md) lists the retained
method inventory as `SyncState.buffer/wrote/pageout`, `StreamState.pagein/packetout`,
`Info.synthesis_headerin`, `Block.synthesis` — the low-level API, observed in a live process. The gate
built afterwards used a different one.

## Decoding the way the game does

Reconstructing `sound/void`'s call sequence — page out, page in, packet out, three header packets,
`synthesis_init`, then `synthesis`/`synthesis_blockin`/`synthesis_pcmout`/`synthesis_read` — and
decoding the same fixture bytes:

| Fixture | `VorbisFile` non-zero | low-level non-zero |
| --- | ---: | ---: |
| `mono-22050` | 509 / 2,048 | **2,048 / 2,048** |
| `stereo-44100` | 256 / 8,192 | **8,192 / 8,192** |
| `silence-mono-8000` | 0 / 2,048 | 0 / 2,048 |
| `clipping-stereo-48000` | 0 / 8,192 | **8,192 / 8,192** |
| `packet-boundary-mono-44100` | 1,532 / 8,320 | **8,319 / 8,320** |

Same bytes, same JARs, same JVM. The two paths produce different audio, and only one of them is the
one that plays. `silence-mono-8000` agrees because silence cannot distinguish them — which is why it
was the only fixture whose shared prefix ever matched.

## Against the reference decoder, the real path is fine

Compared with the committed `ffmpeg 7.1.3 / libvorbis` references over their shared length:

| Fixture | samples | differing | max delta |
| --- | ---: | ---: | ---: |
| `mono-22050` | 1,792 | 1,542 (86.0%) | **2** |
| `stereo-44100` | 7,936 | 6,733 (84.8%) | **2** |

Two least-significant bits out of 65,536, distributed symmetrically around zero (`-2: 97, -1: 676,
0: 250, 1: 657, 2: 112` for the mono case). That is ordinary rounding between two implementations of a
lossy codec whose specification does not require bit-exact decoding.

The remaining structural difference is the one the byte counts showed all along: JOrbis emits a final
block that libvorbis trims against the last page's granule position — 256 mono frames or 128 stereo
frames, 512 bytes either way. Through the real path that tail carries audio, not the silence the
`VorbisFile` path produced.

So the ffmpeg references are sound, the game's decoder is sound, and they disagree only in the ways two
Vorbis decoders are permitted to.

## What this changes

The gate's expected-PCM table is not a description of anything Starsector produces. Its five expected
hashes and byte counts were derived from a reference decoder, and then compared against a third
implementation that no shipped code path uses. The CI tests never caught it because they decode through
a repository-owned stub `VorbisFile` in `preflight-cli/src/test/java/com/jcraft/jorbis/`, which replays
committed reference PCM — so the tests confirmed the expectations against themselves.

This is the same defect as the texture loader fixture corrected in #201: the fixture modelled a
plausible implementation instead of the installed one. There it cost a wrong belief about padding.
Here it invalidates the gate guarding M5.

**The oracle is wrong, not the target.** M5's exit condition is "byte-for-byte and metadata-equivalent",
which is right — but equivalence is owed to the installed JOrbis driven through `sound/void`'s call
sequence, not to libvorbis. Against that oracle byte-for-byte is genuinely reachable, because it is the
same implementation on both sides. Against libvorbis it never was: a prepared-audio cache keyed on
ffmpeg output would hand the game audio two LSBs and one block different from what it decoded itself.

## Rebuilt the same day

The decoder landed in #207 and the gate was rewired onto it in #208. Against the reviewed installation
it now reports `equivalent: true`, with the disagreement exactly as predicted above: max sample delta
2 on both tone fixtures, 0 on silence, and 256 mono / 128 stereo untrimmed frames.

The rebuilt gate asserts in two directions, because neither is sufficient alone. Exactly against the
installed decoder — determinism and `PreparedAudio` round trip — for the properties a cache depends on.
Within tolerance against libvorbis, plus a rule that any fixture declared to contain audio must not
decode to silence, for the property that catches a decoder wired to the wrong thing.

That second direction is the whole lesson here. Making the decoder emit zeros leaves determinism and
round-trip both passing — silence is perfectly reproducible — and only the external checks fail. The
superseded gate had no external check that could tell audio from silence, which is why it reported a
completely silent decode as an ordinary hash mismatch.

**`SoundWrapperObservationChild` has the same defect and is not yet fixed.** It decodes fixtures with
`VorbisFile` and compares the result against what `sound/J` produces.

## What is not yet established

The low-level reconstruction here matches `sound/void`'s **API sequence**, verified against its
disassembly. It is not yet verified to match its **output byte for byte**. The float-to-PCM conversion
in `sound/void` scales by `double 32767.0` and clamps to `[-32768, 32767]`, which is what the
reconstruction does, but the equality has not been proven, so this document pins no expected hashes.
Establishing them is the corrected gate's job, and that gate has to decode through the low-level API.
