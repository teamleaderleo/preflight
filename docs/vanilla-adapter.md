# Vanilla runtime adapter

Preflight uses a wrapper-launch model. It does not replace or edit the Starsector launcher.

```text
Preflight launcher
  -> selects the existing Starsector or Fast Rendering launcher
  -> starts that launcher as a child process
  -> adds one process-local -javaagent option
  -> the selected launcher starts the game normally
```

The process-local environment disappears when the child exits. Preflight does not modify the launcher, application bundle, VM parameter files, mods, or saves.

## Modes

The runtime adapter has three activation modes:

- `OFF` — default. JFR profiling may run, but no adapter transformer is installed and no `adapter.json` file is written.
- `PROBE` — observes candidate game classes and writes their class, source, loader, and method identities. It always retains the original class bytes.
- `ENABLED` — permits an exact allowlisted target to reach a registered transformation plan. Unknown builds, partial matches, missing source bindings, missing plans, malformed classes, and transformer errors retain the original bytes.

`ENABLED` accepts one texture consumer per launch:

- `compatibility` — reconstructed decoded images; original Starsector pixel conversion stays active.
- `prepared-pixels` — upload-ready pixels and stored colors; original Starsector OpenGL upload and lifetime stay active.

The texture plans remain inactive unless `ENABLED` receives a validated texture cache, manifest, and matching resource index.

## Wrapper commands

Probe the selected launcher without enabling transformations:

```bash
java -jar preflight.jar run --adapter-probe
```

The run directory contains:

```text
startup.jfr
summary.json
profile.json
run.json
adapter.json
adapter-analysis.json
adapter-code-loader-signatures.json
adapter-audio-decoder-signatures.json
```

`adapter-analysis.json` is generated automatically after the child process exits. It joins exact classes observed by the agent with methods that appeared on JFR stacks during image reads.

`adapter-code-loader-signatures.json` retains exact bounded Janino and compiler-loader identities. `adapter-audio-decoder-signatures.json` retains exact bounded JOrbis, Jogg, and Slick OpenAL identities that were actually loaded. Both are evidence reports only; neither creates an allowlist or enables a cache path. See [audio decoder evidence collection](audio-decoder-evidence.md) for the real-install protocol.

Use an explicit installation or launcher when automatic discovery needs help:

```bash
java -jar preflight.jar run --game "/path/to/Starsector.app" --adapter-probe
java -jar preflight.jar run --launcher "/path/to/starsector" --adapter-probe
```

To measure the work that occurs after the loading bar reaches 100%, run the enabled adapter with
the direct phase probe:

```bash
java -jar preflight.jar run --adapter --texture-auto --no-record --startup-phase-probe
```

The probe writes `adapter-startup-phases.json` beside `adapter.json` as each boundary completes,
including one timing for every enabled mod's `onApplicationLoad()` callback. It is opt-in because
persisting those callback boundaries adds diagnostic I/O; ordinary accelerated launches do not
weave the probe or pay that cost.

`--adapter` selects `ENABLED` mode. Exact target matching and runtime configuration still decide whether a class changes.

A custom allowlist file may be supplied with:

```bash
java -jar preflight.jar run --adapter --adapter-targets targets.txt
```

## Exact TextureLoader target

Both texture consumers activate only when all reviewed identities match:

```text
class:           com/fs/graphics/TextureLoader
class SHA-256:   d8fcb4cb90d457fc3075e711b6293940774dcf990ea66a7584c231bd96898b50
archive SHA-256: 10d89e113f6d1627cc7bc90b692e8a7f450fdd820c5a4ac5edaecd6710afe708
source kind:     STARSECTOR_CORE
source suffix:   contents/resources/java/fs.common_obf.jar
loader class:    jdk/internal/loader/ClassLoaders$AppClassLoader
loader name:     app
```

The target also requires the complete manually reviewed nine-method descriptor set recorded by the texture contract report. The target is compiled by hand; probe output never creates it automatically.

Build a texture cache first. The build command prints the exact index and manifest paths:

```bash
java -jar preflight.jar texture build \
  --game "/path/to/Starsector.app" \
  --cache-dir "$HOME/.starsector-preflight/cache" \
  --paths-file startup-images.txt
```

A full build may place the index under `indexes/` while a subset build uses `resource-indexes/`. Use the paths printed by the build result.

## Compatibility consumer

The compatibility consumer intercepts the reviewed decoded-image method:

```text
com/fs/graphics/TextureLoader.Ô00000(Ljava/lang/String;)Ljava/awt/image/BufferedImage;
```

Launch it with the default texture mode:

```bash
java -jar preflight.jar run \
  --game "/path/to/Starsector.app" \
  --adapter \
  --texture-mode compatibility \
  --texture-cache-dir "$HOME/.starsector-preflight/cache" \
  --texture-manifest "$HOME/.starsector-preflight/cache/manifests/PROFILE.spfm" \
  --texture-index "$HOME/.starsector-preflight/cache/resource-indexes/PROFILE.spfi"
```

A valid hit reconstructs a compatible `BufferedImage` from the existing bottom-up SPFT pixel payload. Starsector continues through its original texture-object creation, pixel conversion, OpenGL upload, cleanup, and lifetime path.

For a previously prepared full-profile cache, the explicit pilot can resolve the current exact artifacts without copying fingerprint paths into the command:

```bash
java -jar preflight.jar run \
  --game "/path/to/Starsector.app" \
  --adapter \
  --texture-auto \
  --texture-cache-dir "$HOME/.starsector-preflight/cache"
```

`--texture-auto` is compatibility-only. It rebuilds the current read-only resource identity, selects only matching fingerprint-named artifacts, compares the stored roots and provider map with the selected installation, fully validates provider metadata, and hashes the selected manifest and index into `run.json`. It does not build textures. A missing or changed profile fails before launch and asks for a new `preflight prepare`; it never falls back to an older cache.

The `texture-compatibility-v2` plan preserves Starsector's asynchronous image-preloader handoff before consulting Preflight. A preloaded image remains authoritative and is returned without a cache lookup. Only when the original preloader returns `null` does Preflight attempt a prepared hit; a miss then continues into the original direct `ImageIO` branch in the same method. A missing or ambiguous preloader shape declines transformation. Original exceptions propagate unchanged.

## Prepared-pixel consumer

The lower consumer also binds:

```text
com/fs/graphics/TextureLoader.o00000(
  Ljava/awt/image/BufferedImage;
  Lcom/fs/graphics/Object;
)Ljava/nio/ByteBuffer;

com/fs/graphics/TextureLoader.o00000(
  Ljava/nio/ByteBuffer;
  Ljava/lang/String;
)V
```

Launch it explicitly:

```bash
java -jar preflight.jar run \
  --game "/path/to/Starsector.app" \
  --adapter \
  --texture-mode prepared-pixels \
  --texture-cache-dir "$HOME/.starsector-preflight/cache" \
  --texture-manifest "$HOME/.starsector-preflight/cache/manifests/PROFILE.spfm" \
  --texture-index "$HOME/.starsector-preflight/cache/resource-indexes/PROFILE.spfi"
```

The bytecode plan requires a reviewed raster read and exactly one color-sink model. It accepts either three distinct `java.awt.Color` writes on `com/fs/graphics/Object`, or three declared non-static `TextureLoader` color fields that each flow through an instance method into one distinct, exactly typed texture-object setter. Mixed, incomplete, ambiguous, static, and incorrectly typed flows decline transformation.

A valid hit supplies a fresh direct ByteBuffer containing the stored bottom-up SPFT bytes and writes the three stored derived colors. It bypasses ImageIO decode, raster traversal, row reversal, RGB/RGBA conversion, transparent-texel normalization, and derived-color calculation. Starsector retains its original texture allocation, OpenGL upload, filtering, mipmaps, cleanup call, flags, and texture lifetime.

The `texture-prepared-pixels-v2` plan also executes the original asynchronous preloader first. It retains a direct-decode-only clone for a prepared-carrier failure, plus the original conversion and cleanup bodies under private synthetic names. Misses and unsupported cases call direct decode and conversion once, without repeating or bypassing the preloader handoff. Original cleanup always executes, and Preflight releases its buffer accounting afterward, including the exceptional path. Original exceptions propagate.

The installed-style staged color flow is implemented and synthetic transformation coverage passes. Prepared pixels remain fail-closed until the exact installed class passes the offline contract check and a real opt-in lifecycle run completes.

### Offline prepared-pixel contract check

Build the runnable JAR, then inspect either an extracted class or the installed archive:

```bash
mvn --batch-mode --no-transfer-progress -pl preflight-cli -am package

java -cp preflight-cli/target/preflight.jar \
  dev.starsector.preflight.agent.PreparedPixelContractCheck \
  /path/to/TextureLoader.class

java -cp preflight-cli/target/preflight.jar \
  dev.starsector.preflight.agent.PreparedPixelContractCheck \
  "/path/to/Starsector/Contents/Resources/Java/fs.common_obf.jar"
```

JAR input defaults to `com/fs/graphics/TextureLoader.class`. Use `--entry <path>` for a different safe archive entry.

The command writes one bounded JSON report to standard output. It includes the whole-input and class-entry SHA-256 values, all nine required methods, direct and staged color-sink evidence, reviewed sink fields, full transformation status, and the transformed-byte SHA-256 when successful. Exit status `0` means the supplied class completed the exact offline transform; status `6` means the contract declined.

This report supplies offline bytecode evidence. Live acceptance also requires the compiled class, archive, source, and loader identities plus a successful opt-in lifecycle run on the same installation.

Prepared direct-buffer ownership is bounded:

- 32 MiB maximum per texture;
- 64 MiB maximum active and pending bytes;
- 1,024 maximum active and pending buffers;
- identity-tracked release at the existing cleanup seam.

## Validation and telemetry

Before transformer installation, the texture runtime verifies:

- cache, manifest, and index paths remain inside the supplied cache directory;
- manifest and resource-index fingerprints match;
- every indexed provider still matches its recorded size and modification time;
- manifest and provider counts remain within fixed ceilings.

For each image request, it additionally verifies:

- normalized logical path and winning provider;
- current encoded source SHA-256;
- prepared blob checksum, source identity, transformation, dimensions, channels, and pixel length;
- identity transformation and equal original/upload dimensions.

Changed sources, stale indexes, absent entries, corrupt or mismatched blobs, unsupported textures, direct-memory pressure, circuit-breaker state, and internal errors execute the retained original path. Corrupt or identity-mismatched blobs are quarantined up to a fixed per-session limit.

`adapter.json` contains bounded shared texture telemetry:

- attempts, hits, misses, fallbacks, and bytes served;
- corruption and quarantine counts;
- internal-error count and circuit-breaker state;
- fixed fallback and disable-reason counters.

Its nested `textureCompatibility.preparedPixels` object records:

- carriers, direct attempts, hits, and fallbacks;
- image-decode, conversion-call, and derived-color bypass counts;
- bytes bypassed and released;
- active, pending, and peak direct bytes;
- active and pending buffers;
- releases and internal errors.

After every probe or enabled run, the wrapper also converts this raw telemetry into
`adapter-health.json`. Its status is one of `ACTIVE`, `PARTIAL`, `SAFE_FALLBACK`, `DISABLED`,
`PROBE_ONLY`, `NO_TARGETS`, or `ERROR`. In particular, a game update, modified source archive, or
another agent owning a target becomes a visible `PARTIAL`/`SAFE_FALLBACK` result instead of a
silent loss of acceleration. The report records whether any acceleration remained active, whether
original bytecode was retained, the bounded exact-match problems, and the two files needed for
compatibility review. Its `evidenceKinds` separates version/target mismatch, source rejection,
unavailable or declined plans, shadowing, ordinary cache misses, cache rejection, wrapper fallback,
contained adapter failure, and runtime-integrity failure. Misses remain informational; rejection
and failure signals make an otherwise active result `PARTIAL` and add a specific next action.
Generating this verdict is post-processing: failure to write it cannot turn a successful game run
into a failed one.

## Manual acceptance sequence

A useful real-install check exists once the prepared manifest contains startup images:

1. Launch with `--texture-mode compatibility` and complete main menu, campaign load, first combat, save, and clean exit.
2. Launch with `--texture-mode prepared-pixels` and repeat the same route.
3. Inspect `adapter.json` for one applied transformation, successful hits, zero active or pending buffers after clean exit, and contained fallback reasons.
4. Repeat with the kill switch and confirm `transformationsApplied` remains zero.
5. Run OFF-versus-ENABLED timing only after the normal-behavior checks succeed repeatedly.

Synthetic tests prove exact identity acceptance/rejection, strict bytecode-pattern selection, direct pixel/color equivalence, cold miss, warm hit, changed source, corrupt fallback, buffer release, kill switch, and packaged child-JVM behavior across Linux, macOS, and Windows.

## Kill switch

Either setting disables adapter transformer installation:

```text
PREFLIGHT_DISABLE_ADAPTER=1
-Dpreflight.adapter.disabled=true
```

JFR profiling remains independent and may continue.

One or more exact plans can also be disabled without losing the rest of the adapter. Use a
comma-separated plan-ID list in either setting:

```text
PREFLIGHT_DISABLE_ADAPTER_PLANS=prepared-audio-v1,graphicslib-hot-settings-cache-v1
-Dpreflight.adapter.disabledPlans=prepared-audio-v1,graphicslib-hot-settings-cache-v1
```

The filter applies to direct targets and to plans composed onto a class owned by another target.
The same composition gate enforces the selected adapter plan scope. The adapter transformation
cache includes the effective scope, disabled-plan set, and complete `preflight.*` property set in
its identity, so differently gated bytecode can't share a cached result. The environment form is
inherited by an ordinary Preflight launch and is the practical emergency switch for a packaged
build.

The shutdown adapter report records the effective scope and disabled-plan set, plus the number of
registered exact targets for each plan. Its bounded evaluations and transformation timings use the
same plan IDs, so an exact match, a declined rewrite, an applied rewrite, and a cached rewrite remain
distinguishable after the run. Prepared-pixel and padding reports include their runtime safety state
even when the corresponding plan never becomes effective.

## Target records

Every target includes class identity:

- an internal JVM class name
- an exact classfile SHA-256
- required method names and JVM descriptors
- a transformation plan ID

A target is eligible for a live plan only when it also includes all required source bindings:

- `source-kind`
- a portable `source-suffix`
- `loader-class`

Optional stricter bindings are:

- `loader-name`
- `source-sha256`

The source suffix is installation-prefix independent. For example:

```text
source-kind STARSECTOR_CORE
source-suffix starsector-core/starfarer_obf.jar
loader-class jdk/internal/loader/ClassLoaders$AppClassLoader
```

Archive hashing is performed only when a target or specialized evidence report requests it. A missing, unreadable, non-file, oversized, or changed source archive fails closed. The hash result is cached by real path, size, and modification time within the process.

An exact class hash without the required live source bindings is rejected before the transformation registry is consulted. A copied identical class in another mod, JAR, or classloader therefore remains unmodified.

See `docs/adapter-targets.example.txt` for the complete line-oriented format.

## When a real Starsector installation is required

Synthetic fixtures verify parsing, matching, source binding, report generation, concurrency, fallback behavior, corruption handling, bytecode rewriting, direct-buffer ownership, and packaged-agent operation. A real installation confirms the supported build and normal game behavior under the selected texture plan.

The consumers remain exact-version-gated, source-bound, opt-in, and covered by the global kill switch. A performance result requires repeated successful OFF-versus-ENABLED runs.
