# Installed JOrbis equivalence

> **Superseded on 2026-07-26. This gate decodes through an API the game never calls.**
> It drives `com.jcraft.jorbis.VorbisFile`; the shipped `sound/void` uses the low-level
> `SyncState`/`StreamState`/`DspState`/`Block` sequence, and no JAR in the installation references
> `VorbisFile` at all. On the same bytes the two paths produce different audio — the clipping-stress
> fixture decodes to complete silence through `VorbisFile` and to full-scale audio through the real
> path. **The expected-PCM table below is not a description of anything Starsector produces**: it was
> derived from libvorbis and compared against a third implementation. See
> [the evidence](evidence/2026-07-26-the-audio-gate-decodes-an-api-the-game-never-calls.md). The
> pinned archive identities, the loader-identity checks, and the malformed-input cases are unaffected
> and all still pass.

This gate decodes committed deterministic Ogg/Vorbis fixtures through the exact Jogg and JOrbis JARs shipped with the reviewed Starsector installation. It produces an evidence report only. Prepared-audio writes, cache reads, and live sound-loader transformations remain disabled.

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

| Fixture | Encoded bytes | Encoded SHA-256 | Expected PCM bytes | Expected PCM SHA-256 | Format |
| --- | ---: | --- | ---: | --- | --- |
| `mono-22050.ogg` | 4,285 | `2743d710c5df780d381664097a747bd4baf949f9721fbfa8a6e6c14477658b07` | 3,584 | `bbe3d4cb25eb77c157a77091202dd0f4458aa18e50a4b59be018f22be8dc62e5` | mono, 22,050 Hz |
| `stereo-44100.ogg` | 6,843 | `83c01b0343243bbff24d9b6de9619a476ccdf4b8993db13805f9a86f191031c0` | 15,872 | `ada77fe8b369053d7dd1b1ec9430bfec15886ece0be5768dcc4c8e2b17f9fbf8` | stereo, 44,100 Hz |
| `silence-mono-8000.ogg` | 2,671 | `fe0202cd86957a1c6af4eb37d7dc540e266f1a9d81aff9a56274dd36cd8bbab3` | 3,584 | `6cf1b57d59e7111bc218dfb01dda93ac0f776715599a1c69f89035bd20c16a10` | mono silence, 8,000 Hz |
| `clipping-stereo-48000.ogg` | 8,139 | `2ad023bf52f6cc160cec003bdb63c93e2c82065efe9bd29b8e8019400c6ac41a` | 15,872 | `f5eba24d0166fadf4ac02cc423810afb596564615984b063c11e7740f4258e3d` | stereo clipping stress, 48,000 Hz |
| `packet-boundary-mono-44100.ogg` | 5,840 | `3718112dc664b61bf6467eaf68d5c30a7b5884ee1540ce3e1866f59c7a35d70c` | 16,640 | `d4f78542dcdbe1774072343805516076f38fe6ba9edb8f1c36a60dfbbdb26d43` | mono uneven final packet, 44,100 Hz |

The output contract is signed 16-bit little-endian PCM. Each case compares PCM length and SHA-256, channels, sample rate, frame count, sample count, complete source consumption, and stream close ownership. A successful case constructs an in-memory `PreparedAudio` value as an additional arithmetic and metadata check. It writes no `SPAU` file.

The PCM reference identities come from the documented FFmpeg 7.1.3/libvorbis fixture path. A mismatch against installed JOrbis is evidence that the prepared-audio oracle or normalization policy needs revision before any live reuse work.

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
