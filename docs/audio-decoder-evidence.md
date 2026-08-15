# Audio decoder and sound-loader evidence collection

Prepared-audio reuse for Ogg/Vorbis remains disabled until the exact decoder path used by a real Starsector build has been observed, the Starsector `sound/*` wrapper contract has been reviewed, and the installed decoder has matched the committed PCM oracle.

This evidence step is read-only. It does not decode or replace audio, does not modify the launcher or installation, and does not make Ogg/Vorbis inputs eligible for SPAU writes.

## Decoder identity report

A normal `--adapter-probe` launch writes `adapter-audio-decoder-signatures.json` beside `adapter.json` when the report carries a finding; set `PREFLIGHT_FULL_EVIDENCE=1` in the launch's environment to collect it every time, which is what this protocol assumes. The report observes only classes that were actually loaded during that process and whose internal names begin with one of these bounded prefixes:

- `org/newdawn/slick/openal/`
- `com/jcraft/jorbis/`
- `com/jcraft/jogg/`

For every retained source-and-loader identity it records:

- exact classfile SHA-256 and classfile major version;
- complete bounded method names, JVM descriptors, and access flags;
- raw and normalized code-source location;
- source kind and exact source archive SHA-256 when the source is a regular bounded file;
- source-hash failure detail when an archive cannot be hashed safely;
- defining classloader class and optional loader name.

The report is deterministic and bounded to 256 identities. It explicitly states:

```text
originalClassBytesRetained: true
automaticAdapterGenerated: false
decoderEquivalenceEstablished: false
preparedAudioWritesEligible: false
requiresHumanReview: true
```

An empty decoder report means only that no matching decoder class was loaded during the observed process.

## Starsector sound-loader contract report

The same probe writes `adapter-sound-loader-contract.json`. It observes only these exact internal names:

- `sound/J`
- `sound/F`
- `sound/ooOO`
- `sound/D`
- `sound/Sound`
- `sound/void`
- `com/fs/starfarer/loading/A`

The primary seam is `sound/J.o00000(Ljava/io/InputStream;)Lsound/F;`. The report retains exact class/source/archive/loader identity plus bounded field accesses, call and constructor edges, same-class calls, try/catch regions, maximum stack and locals, redacted string identities, and selected ASM dataflow frames. Literal strings appear only as length plus SHA-256.

See `docs/sound-loader-contract-evidence.md` for the complete report contract and review gate.

## Build the evidence branch

Use JDK 17 and Maven 3.9 or newer:

```bash
mvn --batch-mode --no-transfer-progress verify
```

The runnable agent and wrapper are produced at:

```text
preflight-cli/target/preflight.jar
```

## Run the read-only probe

Use the exact installation and launcher that will later be evaluated:

```bash
java -jar preflight-cli/target/preflight.jar run \
  --game "/path/to/Starsector" \
  --adapter-probe
```

An explicit launcher may be used instead:

```bash
java -jar preflight-cli/target/preflight.jar run \
  --launcher "/path/to/the/existing/launcher" \
  --adapter-probe
```

Let the launch reach the main menu and allow menu music or another known Ogg/Vorbis sound to begin playing before exiting normally. Use ordinary campaign and UI activity. The old decoder-identity probe already completed successfully; request a new real-install run only after the sound-loader collector is merged and a rebuilt self-contained kit is available.

The run directory should contain:

```text
run.json
profile.json
startup.jfr
summary.json
adapter.json
adapter-analysis.json
adapter-code-loader-signatures.json
adapter-audio-decoder-signatures.json
adapter-sound-loader-contract.json
```

## Data to return for review

Return these text files from one run:

- `adapter-sound-loader-contract.json`
- `adapter-audio-decoder-signatures.json`
- `adapter-code-loader-signatures.json`
- `adapter.json`
- `adapter-analysis.json`
- `summary.json`
- `run.json`
- `profile.json`

The JFR recording is useful when method-level behavioral correlation is needed, but it may be substantially larger. Do not send Starsector JARs, game assets, mod files, saves, credentials, decoded sound data, or other proprietary binaries. The reports contain hashes, metadata, and bounded structural evidence.

Before sharing, inspect the JSON for local paths or mod names you consider sensitive. Installation prefixes are evidence for local review but are not required in public issue text.

## Review gate after collection

The installed-JOrbis equivalence phase remains blocked until review establishes all of the following:

1. The exact decoder implementation classes, source archives, and defining loaders are present without truncation.
2. The exact `sound/J` primary seam and its direct consumers are present with complete descriptors.
3. The `sound/F` field and metadata contract is understood.
4. Stream ownership, close behavior, fully decoded versus streamed policy, and object lifetime are understood.
5. Source archive hashing succeeded or every failure is understood and resolved safely.
6. A controlled harness can invoke the exact shipped Jogg/JOrbis path without guessing constructor, stream, buffer, or error semantics.
7. The exact path matches the committed mono and stereo Ogg fixtures for PCM bytes, encoding, byte order, sample rate, channels, frame count, and malformed or unsupported behavior.
8. Human review selects one fail-open interception seam.

Only a later reviewed gate may make exact identities eligible for prepared-audio cache writes. Unknown, missing, changed, ambiguous, partially observed, truncated, or corrupt contexts continue through the original decoder path.
