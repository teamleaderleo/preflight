# Ogg/Vorbis golden fixtures

This fixture set establishes a bounded, reproducible gate for future
prepared-audio decoder work. It does not claim equivalence with Starsector's
shipped decoder and it does not wire a live decoder adapter.

That disclaimer turned out to matter. The installed-JOrbis equivalence gate treated these libvorbis
references as expected output for the shipped decoder, which they were never generated to be; measured
on 2026-07-26 the game's real decode path differs from them by up to 2 LSB plus one untrimmed final
block. The fixtures and their `.ogg` containers remain valid and are reused unchanged — only the
expectations built on top of them were wrong. See
[the evidence](evidence/2026-07-26-the-audio-gate-decodes-an-api-the-game-never-calls.md).

## Fixture set

| Fixture | Bytes | SHA-256 | Expected result |
| --- | ---: | --- | --- |
| `mono-22050.ogg` | 4,285 | `2743d710c5df780d381664097a747bd4baf949f9721fbfa8a6e6c14477658b07` | Vorbis, mono, 22,050 Hz, blocks 512/1,024 |
| `mono-22050-reference.s16le` | 3,584 | `bbe3d4cb25eb77c157a77091202dd0f4458aa18e50a4b59be018f22be8dc62e5` | 1,792 signed 16-bit little-endian frames |
| `stereo-44100.ogg` | 6,843 | `83c01b0343243bbff24d9b6de9619a476ccdf4b8993db13805f9a86f191031c0` | Vorbis, stereo, 44,100 Hz, blocks 256/2,048 |
| `stereo-44100-reference.s16le` | 15,872 | `ada77fe8b369053d7dd1b1ec9430bfec15886ece0be5768dcc4c8e2b17f9fbf8` | 3,968 stereo signed 16-bit little-endian frames |
| `mono-22050-opus.ogg` | 484 | `d799bf51d2f8e4b81db45c636db59eabc619de2bf97256f8cb7709a35ca06831` | valid Ogg/Opus, ineligible for the Vorbis path |

Binary fixtures are stored as Base64 text so repository connector writes remain
text-only. Both Vorbis containers and the larger stereo PCM reference are split
into numbered parts. The focused workflow verifies each encoded Ogg part against
`encoded-ogg-parts.sha256` before Maven runs; tests then concatenate the encoded
text and verify the final decoded byte count and SHA-256. `.gitattributes` forces
LF line endings for fixture text so raw part identities stay identical on Linux,
macOS, and Windows.

## Reference generation

The current reference files were generated with
`ffmpeg 7.1.3-0+deb13u1` using deterministic raw signed 16-bit little-endian
source samples, fixed Ogg serial offsets, and bit-exact flags. The reference
identity used by the test is:

`fd912b25752d09927e6aac99b3711a856263079f5fbb4ba6457830224687691a`

It is the SHA-256 of the explicit test-only label:

`ffmpeg-7.1.3-0+deb13u1/libvorbis/pcm_s16le/reference-v1`

Representative encoding and reference-decoding commands are:

```text
ffmpeg -fflags +bitexact -f s16le -ar 22050 -ac 1 -i mono-source.s16le \
  -c:a libvorbis -q:a 4 -flags:a +bitexact -serial_offset 1001 mono-22050.ogg
ffmpeg -fflags +bitexact -i mono-22050.ogg -f s16le -acodec pcm_s16le \
  -flags:a +bitexact mono-22050-reference.s16le

ffmpeg -fflags +bitexact -f s16le -ar 44100 -ac 2 -i stereo-source.s16le \
  -c:a libvorbis -q:a 4 -flags:a +bitexact -serial_offset 1002 stereo-44100.ogg
ffmpeg -fflags +bitexact -i stereo-44100.ogg -f s16le -acodec pcm_s16le \
  -flags:a +bitexact stereo-44100-reference.s16le
```

## Production gate

`OggVorbisIdentification` reads only the bounded first Ogg page. It validates:

- capture pattern, stream version, beginning-of-stream state, and packet lacing;
- bounded first-page body length;
- the Ogg page CRC;
- the exact 30-byte Vorbis identification packet;
- version, channel count, sample rate, block sizes, and framing bit; and
- Ogg/Opus as an explicit unsupported codec.

It returns metadata only. It never decodes PCM.

The golden test constructs `PreparedAudio` values from the committed reference
PCM and verifies source identity, decoder-reference identity, PCM identity,
format, frame count, and sample count. It also exercises truncation, checksum
corruption, malformed channel/block/framing fields, non-Ogg input, missing paths,
and directory paths.

## Adapter boundary

A future Starsector/JOrbis-facing adapter must decode these same Ogg fixtures and
match the expected PCM bytes and metadata before it can write production SPAU
blobs. Its decoder-policy identity must include the exact shipped decoder bytes,
implementation/source suffix, defining loader identity, options, and policy as
described in `prepared-audio-cache.md`.

These fixtures are a test oracle for one FFmpeg/libvorbis reference path. They
are not evidence that Starsector's decoder produces identical PCM. Startup-time
impact remains unmeasured.
