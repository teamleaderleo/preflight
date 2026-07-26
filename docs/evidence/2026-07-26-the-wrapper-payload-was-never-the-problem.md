# The wrapper payload was never the problem (2026-07-26)

The [2026-07-18 sound-wrapper observation](2026-07-18-real-sound-wrapper-observation.md) recorded
`wrapperPayloadMatchesDirectJorbis: false` against the reviewed installation and set
`requiresHumanReview: true`. That negative result has blocked the wrapper half of M5 since.

It was an artifact of the same defect as the equivalence gate. `SoundWrapperObservationChild` decoded
its comparison PCM through `com.jcraft.jorbis.VorbisFile`, which
[no JAR in the installation references](2026-07-26-the-audio-gate-decodes-an-api-the-game-never-calls.md).
It was comparing the real wrapper against a decoder the game never runs.

## The tell was in the original report

> The four non-silence wrapper buffers had the same byte length as direct PCM but different hashes.

Same length, different contents. Two decoders producing the same frame count and different samples is
exactly what a near-silent decode next to real audio looks like. Read at the time as evidence that the
wrapper transforms its payload; it was evidence about the comparison, not about the wrapper.

## Re-run with the installed decode path

`SoundWrapperObservationChild` now decodes through `LowLevelVorbisDecoder`, and checks the identity of
`SyncState`, `Info`, `DspState` and `Block` rather than `VorbisFile`. Same installation, same fixtures,
same wrapper seam `sound/J.o00000(Ljava/io/InputStream;)Lsound/F;`:

| Fixture | direct PCM bytes | wrapper payload matched |
| --- | ---: | --- |
| `mono-22050` | 4,096 | **yes** |
| `stereo-44100` | 16,384 | **yes** |
| `clipping-stereo-48000` | 16,384 | **yes** |
| `packet-boundary-mono-44100` | 16,640 | **yes** |
| `silence-mono-8000` | 4,096 | no |

Four of five, where none matched before. For `mono-22050` the `sound.F` ByteBuffer field reports
`limit: 4096` and

```text
remainingSha256 efea1650fcb78994d7458db25aadd3fc1aba6a9ccdd506fc8620c69ea3139e3c
directPcmSha256 efea1650fcb78994d7458db25aadd3fc1aba6a9ccdd506fc8620c69ea3139e3c
```

The wrapper's payload is the decoder's output, byte for byte. It does not transform, resample, or
re-scale.

## Silence takes a different path, and that is a real constraint

`silence-mono-8000` still does not match, and this one is not an artifact. The wrapper's buffer has
`limit: 1`, and the single byte is `0xff`. The decoder produced 4,096 bytes of zeros.

So the installed wrapper detects a fully silent stream and stores a one-byte sentinel instead of PCM.
That is the game's behaviour, not a disagreement about decoding, and it constrains prepared audio
directly: **a cache that stores 4,096 zero bytes for a silent effect and hands them back is not
equivalent to what the game would have built.** Silent effects need either the same sentinel or
exclusion from the cache.

`wrapperPayloadMatchesDirectJorbis` remains `false` because it is an AND across all five fixtures, and
that is the honest answer — full equivalence across the fixture set is not established. The per-case
`payloadMatched` field carries the distinction. No permissive aggregate was added to make the flag look
better than the evidence.

## What this changes

The wrapper half of M5's first bullet is much further along than the record showed. The remaining gap
is a specific, understood behaviour rather than an unexplained byte mismatch.

Still false, deliberately: `candidateEquivalence`, `equivalenceEstablished`, prepared-audio reads and
writes, live transformation, activation eligibility. This run establishes what the wrapper does with
the payload; it does not approve reusing one.
