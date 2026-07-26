# Installed JOrbis equivalence

> **Rebuilt on 2026-07-26.** The first version of this gate decoded through
> `com.jcraft.jorbis.VorbisFile`, which no JAR in the installation references, and compared the result
> against libvorbis output. It failed all five fixtures for reasons that were entirely its own. See
> [the evidence](evidence/2026-07-26-the-audio-gate-decodes-an-api-the-game-never-calls.md). The gate
> now decodes through the low-level sequence `sound/void` actually drives and **passes against the
> reviewed installation**. Report format is `…-v2`; the expected-PCM table is gone, replaced by the
> checks described below.

This gate decodes committed deterministic Ogg/Vorbis fixtures through the exact Jogg and JOrbis JARs shipped with the reviewed Starsector installation. It produces an evidence report only. Prepared-audio writes, cache reads, and live sound-loader transformations remain disabled.

## What it asserts, and why in two ways

Each valid fixture is checked **exactly, against the installed decoder itself**:

- the same bytes decode to the same PCM twice;
- a `PreparedAudio` built from that decode survives a serialisation round trip unchanged;
- channel count, sample rate, frame count and sample count agree with the fixture;
- the stream is fully consumed, closed exactly once, and never closed during the decode;
- the decode reaches end of stream.

These are the properties a cache depends on, and byte-for-byte is reachable here because the same
implementation is on both sides.

They are not sufficient on their own. **Silence passes every one of them** — it is perfectly
deterministic and round trips perfectly — which is exactly how the superseded gate could decode a
clipping-stress fixture to digital silence and report only a hash mismatch. So each fixture is also
checked **against an external oracle**:

- where a libvorbis reference exists, no sample may differ by more than `2` of 65,536, and the decode
  may not be shorter than the reference;
- every fixture declared to contain audio must decode to something other than silence.

The second rule exists because two fixtures have no reference. Without it they could go silent
unnoticed — verified by making the decoder emit zeros, which the reference check catches for three
fixtures and this check catches for all four that carry audio.

### Tolerances

| Bound | Value | Why |
| --- | ---: | --- |
| `maxReferenceSampleDelta` | 2 | Vorbis does not require bit-exact decoding. Measured disagreement with libvorbis is at most two steps, symmetric about zero. |
| `maxUntrimmedTailFrames` | 8,192 | libvorbis trims the final block against the last granule position; JOrbis does not. Measured excess is 256 mono / 128 stereo frames. The bound is the Vorbis maximum block size. |

## Pinned decoder identity

The command accepts only these archive identities:

```text
jogg-0.0.7.jar
ed7946260897d97c468a4749b3d9d5e436a268fa948bc32e75a7487130e89379

jorbis-0.0.15.jar
d049b2a1c6ddefde3a5cbff320c96fdd5aefa09b0d3bbea3fe44839f7e6713f9
```

The child JVM also requires `com.jcraft.jogg.SyncState`, `com.jcraft.jorbis.VorbisFile`, and `com.jcraft.jorbis.Info` to come from those exact JARs through the application classloader:

```text
loader class: jdk.internal.loader.ClassLoaders$AppClassLoader
loader name:  app
```

Any archive or loader identity change fails the gate.

## Run the gate

Use the JAR paths from the reviewed installation:

```bash
java -jar preflight.jar audio jorbis-equivalence \
  --jogg "/path/to/Starsector/jogg-0.0.7.jar" \
  --jorbis "/path/to/Starsector/jorbis-0.0.15.jar" \
  --output installed-jorbis-equivalence.json
```

The command launches a separate JVM with this classpath order:

```text
preflight.jar
jogg-0.0.7.jar
jorbis-0.0.15.jar
```

Exit code `0` means the complete equivalence gate passed. Exit code `6` means the report was written and at least one identity, PCM, metadata, stream-ownership, or malformed-input check differed.

## Valid PCM fixtures

The full profile contains five fully decoded effect cases:

| Fixture | Encoded bytes | Encoded SHA-256 | libvorbis reference | Format |
| --- | ---: | --- | --- | --- |
| `mono-22050.ogg` | 4,285 | `2743d710c5df780d381664097a747bd4baf949f9721fbfa8a6e6c14477658b07` | yes | mono, 22,050 Hz |
| `stereo-44100.ogg` | 6,843 | `83c01b0343243bbff24d9b6de9619a476ccdf4b8993db13805f9a86f191031c0` | yes | stereo, 44,100 Hz |
| `silence-mono-8000.ogg` | 2,671 | `fe0202cd86957a1c6af4eb37d7dc540e266f1a9d81aff9a56274dd36cd8bbab3` | yes | mono silence, 8,000 Hz |
| `clipping-stereo-48000.ogg` | 8,139 | `2ad023bf52f6cc160cec003bdb63c93e2c82065efe9bd29b8e8019400c6ac41a` | no | stereo clipping stress, 48,000 Hz |
| `packet-boundary-mono-44100.ogg` | 5,840 | `3718112dc664b61bf6467eaf68d5c30a7b5884ee1540ce3e1866f59c7a35d70c` | no | mono uneven final packet, 44,100 Hz |

The output contract is signed 16-bit little-endian PCM. A successful case constructs an in-memory
`PreparedAudio` value and round-trips it. It writes no `SPAU` file.

**No expected PCM hash is pinned here.** The installed decoder is the oracle, and pinning a hash taken
from one run of it would record a fact about this machine rather than a property of the decoder. What
the fixtures pin is their *encoded* identity; what the gate checks is behaviour.

## Measured against the reviewed installation (2026-07-26)

| Fixture | PCM bytes | packets | max reference delta | untrimmed frames |
| --- | ---: | ---: | ---: | ---: |
| `mono-22050` | 4,096 | 7 | 2 | 256 |
| `stereo-44100` | 16,384 | 19 | 2 | 128 |
| `silence-mono-8000` | 4,096 | 9 | 0 | 256 |
| `clipping-stereo-48000` | 16,384 | 33 | — | — |
| `packet-boundary-mono-44100` | 16,640 | 24 | — | — |

`equivalent: true`, all four checked class identities exact and loaded by the application loader, all
five malformed-input cases stable.

## The wrapper observation was corrected too

`SoundWrapperObservationChild` had the same defect and now decodes through the same path. Re-run
against the installation, four of five wrapper payloads match the decode byte for byte; the fifth is
the wrapper's one-byte sentinel for fully silent streams. See
[the follow-up](evidence/2026-07-26-the-wrapper-payload-was-never-the-problem.md).

## Malformed and unsupported inputs

The child runs each of these twice:

- Ogg/Opus input
- non-Ogg bytes
- truncated Ogg header
- truncated Vorbis packet stream
- corrupted packet bytes

The report records whether decoding returned PCM or failed, the root failure class, bytes consumed, read counts, and stream closure. The two observations must match. These cases remain ineligible for prepared audio regardless of their stable installed-decoder behavior.

## Report acceptance

Review these top-level fields:

```text
identityExact: true
validPcmEquivalent: true
invalidBehaviorStable: true
equivalent: true
fullyDecodedEffectsEligible: true
streamedMusicEligible: false
preparedAudioWritesEnabled: false
liveTransformEnabled: false
```

Every case includes its own `equivalent`, PCM identity, metadata, source-read, and stream-ownership fields. Preserve the complete report when a mismatch occurs; it is the evidence needed to adjust the oracle or reject the decoder path.

## CI boundary

Repository CI builds separate synthetic Jogg and JOrbis JARs, launches the shaded Preflight JAR in a child JVM, and proves archive identity, application-classloader binding, reflective API compatibility, three packaged PCM cases, malformed repeats, and report generation on Linux, macOS, and Windows. CI also pins all five encoded Ogg fixture identities.

The synthetic decoder proves harness behavior. The real installed JAR run is the equivalence decision for Starsector.
