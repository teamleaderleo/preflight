# 2026-07-18 real Starsector sound-wrapper observation

> **Superseded on 2026-07-26.** The comparison PCM in this run was decoded through
> `com.jcraft.jorbis.VorbisFile`, which no JAR in the installation references. Re-run against the
> decode path the game actually uses, four of the five wrapper payloads match byte for byte, and
> the fifth is explained by the wrapper storing a one-byte sentinel for fully silent streams. The
> negative payload result below is an artifact of the comparison, not a property of the wrapper.
> See [the follow-up](2026-07-26-the-wrapper-payload-was-never-the-problem.md). Identity, metadata
> candidates, and malformed-input stability are unaffected.

Related: #75, #94.

## Evidence identity

- Installation: Starsector 0.98a-RC8 at `/Applications/Starsector.app`
- Runtime: bundled OpenJDK 17.0.10, selected automatically at `Contents/Home/bin/java`
- Runtime executable SHA-256: `3c3119b298602ae84e1fa3175a21279b5827cca4fc7c17f27f6ac0abc642a9ff`
- Launch profile: `starsector-bytecode-verification-disabled-v1`
- Bounded report bytes: `13,986`
- Bounded report SHA-256: `9eccb56c09127bdbcbcbcf70dd26074820968d2c99950815c4934cf35b8f9573`
- Fixture profile: `full` (five valid and five malformed inputs)

The official macOS launcher runs the bundled JVM with `-noverify`, diagnostic VM options unlocked, and local and remote bytecode verification disabled. The exact `sound/*` classes raise `ClassFormatError` before consuming input when those flags are omitted. With the official verification profile, the direct observation child completes normally.

## Exact identity gates

All reviewed classes were defined by `jdk.internal.loader.ClassLoaders$AppClassLoader`, loader name `app`, from the expected exact archives.

- `fs.sound_obf.jar` SHA-256: `79e5bc71236333541674e2b9093642ac5a2d68d9e55cb8a71f299fd389ba1573`
- `sound.J` class SHA-256: `d99e37bfedd0510418fa171ae1861918f8ef72d0c0c3084df669f1d195b18733`
- `sound.F` class SHA-256: `5d03d4031ee2b7cac51ec6838730b91824489128fd3b538bcb41d2f6208b13e2`
- `jogg-0.0.7.jar` SHA-256: `ed7946260897d97c468a4749b3d9d5e436a268fa948bc32e75a7487130e89379`
- `jorbis-0.0.15.jar` SHA-256: `d049b2a1c6ddefde3a5cbff320c96fdd5aefa09b0d3bbea3fe44839f7e6713f9`
- `com.jcraft.jorbis.VorbisFile` class SHA-256: `945b0b8486d35e4a8c4a265c3f99240a44ff41c45899e54d9a021b0a30ffdad0`
- `com.jcraft.jorbis.Info` class SHA-256: `ddea1b76c814a70fc996cafd2cbba0747fd79f7e18bac023bd561c9f1f216d03`

## Observation result

The exact primary seam was `sound/J.o00000(Ljava/io/InputStream;)Lsound/F;`. It is an instance method.

All five valid wrapper calls returned `sound.F`. The reflected scalar candidates matched the expected channel count and sample rate for mono 22,050 Hz, stereo 44,100 Hz, silence mono 8,000 Hz, clipping stereo 48,000 Hz, and packet-boundary mono 44,100 Hz.

The wrapper's direct `ByteBuffer` payload did not hash-match direct installed-JOrbis PCM for any fixture. The four non-silence wrapper buffers had the same byte length as direct PCM but different hashes. The silence wrapper buffer contained one byte while direct JOrbis produced 4,096 PCM bytes. No raw PCM was retained.

All five malformed-input cases returned on both invocations with stable behavior: unsupported Opus, non-Ogg bytes, truncated header, truncated packet, and corrupt packet.

## Decision

- `observationComplete`: `true`
- `identityExact`: `true`
- `wrapperMetadataCandidatesPresent`: `true`
- `invalidWrapperBehaviorStable`: `true`
- `wrapperPayloadMatchesDirectJorbis`: `false`
- `candidateEquivalence`: `false`
- `equivalenceEstablished`: `false`
- `requiresHumanReview`: `true`

The real observation does not approve a prepared-audio wrapper or any decoder-output allowlist. It proves that the exact wrapper can be observed under the official runtime profile, and it produces negative byte-equivalence evidence for the tested direct-JOrbis mode.

Prepared-audio reads and writes, cache reads and writes, live transformation, automatic allowlist generation, identity pinning for activation, fully decoded effect eligibility, and streamed music eligibility all remained false. The report contains no decoded audio, class bytes, or literal failure messages.
