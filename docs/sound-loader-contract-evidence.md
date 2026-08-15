# Starsector sound-loader contract evidence

Issue #75 requires one read-only evidence pass before the installed-JOrbis equivalence harness can be designed against Starsector's own wrapper object contract.

The collector emits `adapter-sound-loader-contract.json` beside `adapter.json` during `--adapter-probe` and `--adapter` sessions, when the pass carries a finding. Collecting the routine ones too — which is what this protocol wants — needs `PREFLIGHT_FULL_EVIDENCE=1` in the environment of the launch. It observes loaded classes only and retains these exact internal names:

- `sound/J`
- `sound/F`
- `sound/ooOO`
- `sound/D`
- `sound/Sound`
- `sound/void`
- `com/fs/starfarer/loading/A`

The primary seam is:

```text
sound/J.o00000(Ljava/io/InputStream;)Lsound/F;
```

## Evidence retained

For each distinct class/source/loader identity, the report records:

- a typed, length-prefixed SHA-256 identity key;
- internal class name, class SHA-256, and classfile major version;
- complete method names, JVM descriptors, and access flags within the declared ceiling;
- code source, normalized source, source kind, and portable source suffix;
- source archive SHA-256 or bounded source-hash failure detail;
- defining loader class and loader name;
- deterministic truncation fields and bounded diagnostics.

Every retained target method also has a bounded structural section containing:

- field reads and writes by owner, name, descriptor, and opcode;
- method calls and same-class calls by owner, name, descriptor, opcode, and interface flag;
- constructor calls;
- try/catch start, end, handler, and caught type;
- maximum stack, maximum locals, and executable instruction count;
- redacted string constants as length plus SHA-256;
- selected ASM dataflow frames.

Dataflow frames are retained only at:

- field writes;
- calls into `com/jcraft/jogg/*` or `com/jcraft/jorbis/*`;
- calls whose return type is `sound/F`;
- constructor calls with a `sound/F` argument;
- returns;
- throws.

Methods are marked as the exact primary seam or as consumer candidates when their descriptors, fields, or call edges use `sound/F`, or when they call the primary seam.

## Fixed safety boundary

The report states these conditions directly:

```text
originalClassBytesRetained: true
classBytesIncluded: false
bytecodeListingsIncluded: false
literalStringsIncluded: false
decodedAudioIncluded: false
automaticAllowlistGenerated: false
transformationPlanGenerated: false
transformRegistered: false
cacheReadsEnabled: false
cacheWritesEnabled: false
preparedAudioWritesEligible: false
requiresHumanReview: true
```

The collector returns the untouched class definition path to the JVM. It exports no class bytes, game archives, asset contents, decoded audio, or literal proprietary strings.

## Verification

Run the full reactor:

```bash
mvn --batch-mode --no-transfer-progress verify
```

Run the focused agent and packaged child-JVM gate:

```bash
mvn --batch-mode --no-transfer-progress \
  -pl preflight-agent,preflight-cli -am \
  -Dtest=AudioDecoderSignatureReportTest,SoundLoaderContractReportTest,AgentOptionsTest,AgentInjectionTest,CommandLineAdapterTest \
  -Dit.test=AdapterAgentIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  verify
```

The packaged test uses repository-owned synthetic classes with the exact internal package and class names. Those fixtures exercise field effects, direct consumers, constructor consumers, JOrbis call edges, exception regions, dataflow frames, and string redaction. They reproduce no Starsector implementation.

## Real-install collection protocol

Build and merge the collector before asking for another real-install run. Then produce the self-contained macOS ZIP through the existing audio probe-kit workflow. The kit contains a bundled `preflight.jar` and a double-clickable `.command` runner, and requires no Git checkout or Maven installation.

The result ZIP must include:

- `adapter-sound-loader-contract.json`
- `adapter-audio-decoder-signatures.json`
- `adapter-code-loader-signatures.json`
- `adapter.json`
- `adapter-analysis.json`
- `summary.json`
- `run.json`
- `profile.json`
- bounded console and kit metadata

The packager excludes `startup.jfr`, Starsector binaries, mod JARs, assets, decoded sound data, and saves from the upload ZIP.

## Human review gate

Phase 2 begins only after review establishes:

1. the exact `sound/J` primary method identity and source/loader tuple;
2. the complete `sound/F` fields and metadata semantics needed for equivalence;
3. direct consumer and constructor paths for `sound/D`, `sound/Sound`, and related wrappers;
4. stream ownership, close behavior, and object lifetime;
5. the fully decoded versus streamed policy;
6. original malformed, unsupported, and exception behavior;
7. one reviewed fail-open interception seam.

Any truncation, ambiguity, missing identity, source-hash failure, analysis diagnostic, or unexplained consumer path blocks equivalence and live prepared-audio work.
