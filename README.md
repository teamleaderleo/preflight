# Starsector Preflight

Asset preprocessing, startup profiling, and cache tooling for heavily modded Starsector.

Preflight aims to shorten repeat launch times through measured, independently switchable subsystems:

- **Preflight Trace** — launch-time JFR profiling and reports
- **Preflight Index** — persistent resource-provider index
- **Preflight Textures** — prepared texture cache and exact-gated runtime consumers
- **Preflight Scripts** — persistent Janino bytecode records and fail-open reuse wrapper
- **Preflight Scheduler** — bounded loading concurrency and memory limits
- **Preflight Builder** — cross-platform cache generation and diagnostics

## Quick start

Build or download the single runnable `preflight.jar`, then run:

```bash
java -jar preflight.jar run
```

Preflight will:

1. Discover a Starsector or Fast Rendering launcher.
2. Read the enabled mod profile and inventory its startup workload.
3. Inject the same JAR as a launch-time Java agent through the child process environment.
4. Start the selected existing launcher without editing the game, mods, saves, launcher, or VM parameter files.
5. Capture a JFR startup recording in an isolated run directory.
6. Write workload, lifecycle, adapter, and startup summaries after the game process exits.

Preflight is an additional wrapper entry point. The selected vanilla or Fast Rendering launcher remains the child process that starts Starsector. Adapter mode is OFF by default.

Run discovery without launching anything:

```bash
java -jar preflight.jar doctor
```

Build and validate every reusable profile cache without launching the game:

```bash
java -jar preflight.jar prepare
```

Create a convenient local launcher:

```bash
java -jar preflight.jar install
```

On macOS this creates `~/Applications/Starsector Preflight.app`. Linux receives a command and desktop entry. Windows receives a local command launcher.

## Vanilla runtime adapter

Collect read-only class signatures from the selected Starsector build with:

```bash
java -jar preflight.jar run --adapter-probe
```

Probe mode writes `adapter.json` and always retains the original class bytes. `--adapter` selects fail-closed enabled mode. A class changes only when its exact class hash, source archive, loader identity, required descriptors, and registered transformation plan all match.

The exact-gated `texture-compatibility-v2` plan passed bounded behavioral acceptance on Starsector 0.98a-RC8 on 2026-07-19. That run applied one reviewed transformation, served 4,926 prepared decoded-image hits, used the original path three times, reached the main screen, and exited normally. This is a behavioral acceptance result; repeated OFF-versus-ENABLED timing remains pending.

After `prepare`, launch the exact current prepared profile explicitly with:

```bash
java -jar preflight.jar run \
  --adapter \
  --texture-auto \
  --texture-cache-dir "$HOME/.starsector-preflight/cache"
```

`--texture-auto` selects only fingerprint-named artifacts that exactly describe the current installation. Missing, changed, stale, corrupt, escaped, unsupported, or ambiguous inputs use the original game path or fail before launch with a preparation instruction.

The lower `prepared-pixels` consumer remains fail-closed until the exact installed class passes the offline contract check and a real opt-in lifecycle run completes. Preparation alone never enables either consumer.

See [vanilla runtime adapter](docs/vanilla-adapter.md) for target identities, texture modes, telemetry, kill switches, and acceptance rules.

## Workload census

Every normal run writes `profile.json` before starting the game. It includes:

- Ordered enabled mod IDs and missing-mod diagnostics
- Counts and compressed bytes for images, sounds, JARs, loose Java source, and data files
- Per-extension and per-mod totals
- Largest mods and individual assets
- Duplicate logical paths with probable enabled-order winners
- A repeatable profile fingerprint based on mod order, paths, sizes, and modification times

Run the census by itself:

```bash
java -jar preflight.jar scan --game "/path/to/Starsector" --json profile.json
```

Use `run --no-scan` to skip this step for a single launch.

## Resource provider index

Build a checksummed binary index of core and enabled-mod resource providers:

```bash
java -jar preflight.jar index build --game "/path/to/Starsector"
```

Inspect, query, or validate it without launching the game:

```bash
java -jar preflight.jar index inspect ~/.starsector-preflight/indexes/PROFILE.spfi
java -jar preflight.jar index query ~/.starsector-preflight/indexes/PROFILE.spfi graphics/ships/example.png
java -jar preflight.jar index query ~/.starsector-preflight/indexes/PROFILE.spfi graphics/ships/example.png --all
java -jar preflight.jar index validate ~/.starsector-preflight/indexes/PROFILE.spfi
```

The index stores ordered providers, a direct winning provider, and complete negative lookup results. It validates version, bounds, path rules, provider ordering, and SHA-256 payload checksum when read. Validation checks current provider metadata and resolved real paths. Resource links may target files inside their canonical root; links that escape the root are skipped or rejected.

See [resource provider index](docs/resource-index.md) for the format and resolution semantics.

## Prepared textures

Prepare one encoded image into upload-ready pixels and derived loader colors:

```bash
java -jar preflight.jar texture prepare graphics/ships/example.png
```

Inspect, verify, or benchmark the resulting blob:

```bash
java -jar preflight.jar texture inspect example.spft
java -jar preflight.jar texture verify example.png example.spft
java -jar preflight.jar texture benchmark example.png example.spft --runs 10
```

Version 1 stores raw bottom-up RGB/RGBA bytes, original and upload dimensions, three loader-derived colors, transformation metadata, and the source SHA-256. The checksummed blob is a correctness baseline for compression, pack-file, memory-mapping, and bulk-raster experiments.

See [prepared texture blobs](docs/prepared-textures.md) for the conversion semantics and binary format.

## Overrides

Automatic discovery checks explicit arguments, `STARSECTOR_HOME`, `STARSECTOR_DIR`, the current directory, and common platform install locations.

```bash
java -jar preflight.jar run --game "/path/to/Starsector.app"
java -jar preflight.jar run --launcher "/path/to/fr.sh"
java -jar preflight.jar run --dry-run
```

Arguments following `--` are passed to the selected launcher:

```bash
java -jar preflight.jar run -- --some-launcher-option
```

Run data defaults to:

```text
~/.starsector-preflight/runs/YYYYMMDD-HHMMSS-SSS-NONCE/
  run.json
  profile.json
  startup.jfr
  summary.json
  adapter.json           probe/enabled runs only
  adapter-analysis.json  when adapter and JFR reports are available
```

`run.json` is finalized for successful child exits, nonzero exits, fatal lifecycle evidence, launch failures, and bounded postprocessing failures.

See [automatic launch and discovery](docs/automatic-launch.md) for the full behavior and troubleshooting path.

## Benchmark records

Inspect the available benchmark commands with:

```bash
java -jar preflight.jar benchmark --help
```

The campaign commands collect run directories, validate identities, preserve failed runs, report every run, and compute OFF-warm versus ENABLED-warm-hit median deltas. A primary campaign completes after five successful runs per mode. See [benchmarking](docs/benchmarking.md).

## Build

Requires JDK 17 and Maven 3.9 or newer:

```bash
mvn verify
```

Optional static analysis is available with:

```bash
mvn -Panalysis verify
```

The self-contained executable and agent JAR is produced at:

```text
preflight-cli/target/preflight.jar
```

The verification suite launches that packaged file as a real `-javaagent` on Linux, macOS, and Windows.

## Other commands

Summarize an existing recording:

```bash
java -jar preflight.jar summarize preflight-startup.jfr --json report.json
```

Fingerprint a mod or file:

```bash
java -jar preflight.jar fingerprint /path/to/mod
```

Advanced manual agent usage remains available:

```bash
java -javaagent:preflight.jar=dest=preflight-startup.jfr -jar your-application.jar
```

Agent options are comma-separated. `dest64` is used internally for paths containing spaces or commas.

Run `preflight <command> --help` (or `preflight help <command>`) for a single command's usage. Set `PREFLIGHT_DEBUG=1` to print a full stack trace when a command fails unexpectedly.

## Project documents

- [Roadmap](docs/roadmap.md)
- [Architecture](docs/architecture.md)
- [Desktop app and distribution research](docs/desktop-app-research.md)
- [Optimization North Star](docs/optimization-north-star.md)
- [Benchmarking](docs/benchmarking.md)
- [Automatic launch and discovery](docs/automatic-launch.md)
- [Vanilla runtime adapter](docs/vanilla-adapter.md)
- [Resource provider index](docs/resource-index.md)
- [Prepared texture blobs](docs/prepared-textures.md)
- [What generalizes](docs/what-generalizes.md) — what this project is an instance of, and what does
  not port
- [ADR 0001: measurement first](docs/adr/0001-measurement-first.md)

## Status

Experimental. Compatibility-v2 has bounded real-install behavioral acceptance and no timing claim. Prepared pixels, audio reuse, and Janino reuse remain exact-evidence gated. No Starsector or Fast Rendering binaries are included.

## License

[MIT](LICENSE). Starsector, Fast Rendering, and mod content remain the property of their respective owners and are never included in this repository or its releases.
