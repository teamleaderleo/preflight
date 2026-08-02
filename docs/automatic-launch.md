# Automatic launch and discovery

Preflight is designed to run without editing Starsector or Fast Rendering files.

## Commands

```bash
java -jar preflight.jar doctor
java -jar preflight.jar scan
java -jar preflight.jar prepare
java -jar preflight.jar run
java -jar preflight.jar install
```

`doctor` performs discovery and prints every candidate. `scan` writes a workload census for the enabled mod profile. `prepare` builds and validates reusable caches without launching the game. `run` performs the same census, then launches the selected candidate with trace capture. `install` copies the runnable JAR into the user's Preflight directory and creates a convenient platform launcher.

## Launch relationship

Preflight is an additional wrapper entry point. It does not replace the Starsector launcher and does not patch that launcher on disk.

```text
Starsector Preflight
  -> discovers or receives the existing launcher path
  -> starts that launcher as a child process
  -> adds one process-local JAVA_TOOL_OPTIONS value
  -> the existing launcher starts Starsector normally
```

The child may be the vanilla launcher, a Fast Rendering launcher, or another explicitly selected compatible wrapper. Preflight's process-local environment disappears when the child exits.

## Discovery order

Preflight considers:

1. `--launcher`
2. `--game`
3. `STARSECTOR_HOME`
4. `STARSECTOR_DIR`
5. The current directory
6. Common platform locations

macOS candidates include app bundles in `/Applications`, `~/Applications`, and `~/Games`. Linux candidates include common home, games, local-share, and `/opt` directories. Windows candidates include Program Files, Local AppData, and a home Games directory.

Within a game directory, Preflight recognizes common Starsector and Fast Rendering shell scripts, command files, executables, and macOS app-bundle executables. Fast Rendering names receive a higher selection score than vanilla launcher names.

Use an explicit launcher whenever an unusual port or custom wrapper receives the wrong score:

```bash
java -jar preflight.jar run --launcher "/absolute/path/to/custom-launcher"
```

## Enabled mod profile

Preflight locates `mods/enabled_mods.json`, resolves each enabled ID through the installed mods' `mod_info.json`, and scans enabled directories in profile order.

The resulting `profile.json` reports:

- Enabled and missing mod IDs
- Total files and compressed bytes
- Images, sounds, JARs, loose Java source, and data-file totals
- Per-extension and per-mod totals
- Largest mods and assets
- Duplicate logical paths and probable enabled-order winners
- A profile fingerprint for comparing benchmark runs

The duplicate-path result is an early census. The persistent resource index provides the complete ordered provider list and winning provider used by Preflight's current lookup model.

Run only the scan with:

```bash
java -jar preflight.jar scan --game "/path/to/game" --json profile.json
```

Normal launches scan automatically. `run --no-scan` disables it for one launch. Scan failures are reported and the game launch continues.

## Injection model

Preflight passes one additional value to the child process through `JAVA_TOOL_OPTIONS`:

```text
-javaagent:/path/to/preflight.jar=dest64=ENCODED_TRACE_PATH
```

The encoded destination avoids parsing problems with spaces and commas. Existing `JAVA_TOOL_OPTIONS` content is preserved. A second Preflight agent is rejected to avoid duplicate recordings.

This environment change exists only for the launched child process. Preflight leaves all original launchers and VM parameter files untouched.

### Timestamp-coherent startup recording

Use the sampling profile with an intentionally single-chunk JFR when the question depends on
*when* an event happened during startup:

```bash
java -jar preflight.jar run --profile --single-chunk-recording
```

This gives JFR a 256 MiB memory area and maximum chunk size and disables Preflight's periodic
sidecar dumps, because each dump rotates the active chunk. The run receipt records the policy, and
postprocessing reports whether the resulting recording actually contains one chunk. The trade is
256 MiB of profiling headroom and no sidecar recovery after a crash or force-quit. Ordinary runs
keep the bounded-memory, periodically flushed policy; `--no-record` is incompatible with this mode.

### Optional vanilla adapter probe

The adapter is OFF by default. A normal run installs no adapter transformer and writes no `adapter.json`.

```bash
java -jar preflight.jar run --adapter-probe
```

Probe mode installs a read-only class observer. It records candidate class hashes and method signatures while retaining every original class byte. `--adapter` selects the fail-closed enabled mode, which still requires an exact allowlisted target and a registered transformation plan.

The campaign entity-lookup pilot is separately gated even when the adapter is enabled:

```bash
java -jar preflight.jar run --adapter --campaign-entity-index
```

It puts a size-invalidated index in front of `BaseLocation.getEntityById`, validates hits against
the game's live repository set, and delegates every miss or failure to the preserved original
method. The adapter report exposes `served`, `declined`, `rebuilds`, and `indexedEntities` counters
for a long-session review. The flag is intentionally rejected in adapter-off and probe modes.

See [vanilla runtime adapter](vanilla-adapter.md) for the activation gate, report format, kill switch, and the point where a real Starsector installation is required.

## Run output

Each ordinary profiling run receives a directory containing:

- `run.json` — selected launcher, command, Java version, timestamps, effective and raw launcher exit codes,
  bounded fatal-log lifecycle evidence, and profile report path
- `profile.json` — enabled-mod workload census
- `startup.jfr` — raw Java Flight Recorder data
- `summary.json` — aggregate and attributed startup metrics

Probe or enabled adapter runs additionally write:

- `adapter.json` — observed candidate signatures, allowlist evaluations, transformations, and contained failures

The default location is `~/.starsector-preflight/runs/`.

## Installed launchers

### macOS

`install` creates:

```text
~/Applications/Starsector Preflight.app
```

The wrapper uses the Java runtime that executed the installer and invokes the copied Preflight JAR. The original Starsector app remains unchanged.

### Linux

`install` creates:

```text
~/.local/bin/starsector-preflight
~/.local/share/applications/starsector-preflight.desktop
```

### Windows

`install` creates a command launcher under Local AppData. Desktop and Start Menu shortcut generation can be added after native Windows validation.

## Troubleshooting

Run:

```bash
java -jar preflight.jar doctor --game "/path/to/game"
```

Then use `--launcher` with the exact file shown by the relevant vanilla or Fast Rendering installation. `--dry-run` prints the complete command, selected working directory, trace destination, adapter mode, and injected Java option without starting the game.
