# Automatic launch and discovery

Preflight is designed to run without editing Starsector or Fast Rendering files.

## Commands

```bash
java -jar preflight.jar doctor
java -jar preflight.jar scan
java -jar preflight.jar prepare
java -jar preflight.jar run --optimization-preset recommended
java -jar preflight.jar install
```

`doctor` performs discovery and prints every candidate. `scan` writes a workload census for the enabled mod profile. `prepare` builds and validates reusable caches without launching the game. `run` performs the same census, then launches the selected candidate with trace capture. `install` copies the runnable JAR into the user's Preflight directory and creates a convenient platform launcher.

## Launch relationship

Preflight is an additional wrapper entry point. It does not replace the Starsector launcher and does not patch that launcher on disk.

For an unattended launch that skips the launcher UI entirely, use the game's own direct path:

```bash
java -jar preflight.jar run --direct --optimization-preset recommended
```

`--direct` reads resolution, fullscreen, sound, and registration state from Starsector's saved
launcher preferences. It refuses to launch when those values are unavailable or malformed rather
than guessing a different configuration or leaving a modal error dialog behind.

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
java -jar preflight.jar run --optimization-preset recommended \
  --launcher "/absolute/path/to/custom-launcher"
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

### Optimization presets and optional adapter probe

A raw developer `run` remains custom/off unless a preset or individual adapter option is selected.
The installed launcher and desktop product select **Recommended** by default. The equivalent CLI is:

```bash
java -jar preflight.jar run --optimization-preset recommended
```

`--fast` is retained as a compatibility alias for Recommended. **Conservative** enables only the
broad, immutable-input startup caches and omits mod-specific and gameplay-runtime shortcuts.
**Off** retains wrapper/process reporting while disabling transforms, profiling, scan, and summary
work. Every preset is still subject to exact adapter identity and runtime validation.

Probe mode is a separate developer tool. Without a selected preset or adapter, a raw `run` installs
no adapter transformer and writes no `adapter.json`.

```bash
java -jar preflight.jar run --adapter-probe
```

Probe mode installs a read-only class observer. It records candidate class hashes and method signatures while retaining every original class byte. `--adapter` selects the fail-closed enabled mode, which still requires an exact allowlisted target and a registered transformation plan.

The normal optimized launch enables every reviewed startup and gameplay cache that has passed its
live gate:

```bash
java -jar preflight.jar run --direct --optimization-preset recommended
```

This includes the campaign entity and deployment-icon caches, GraphicsLib's compact startup replay,
per-render insignia memo, and event-invalidated hot-settings cache when the exact reviewed
GraphicsLib is installed, the Janino generated bytecode cache unless Fast Rendering owns that
compiler path, the prepared spec, merged-read, texture, and audio caches, and the exact vanilla
streaming-source OpenAL error-order repair. Every adapter remains exact-version-gated and retains
original behavior on drift or internal failure. The
directory-listing resource-probe cache is deliberately excluded:
both its whole-root shortcut and its narrower `File.exists()` memo produced live false negatives
for unchanged files.

The campaign entity cache can still be selected separately for isolation:

```bash
java -jar preflight.jar run --adapter --campaign-entity-index
```

It puts a mutation-tracked index in front of `BaseLocation.getEntityById`. Exact-gated generations
on the repository's entity list and reviewed base entity id setter make same-size list, iterator,
sub-list, and `setId()` edits invalidations too. A custom entity overriding the reviewed setter
retains complete snapshot validation; any validation or reflection failure delegates to the
preserved original method. The adapter report exposes answer, rebuild, fast/deep validation,
mutation, tracked/untracked-list, and validated-reference counters under `campaignEntityIndex` for
a long-session review.
A second exact vanilla campaign optimization is enabled by the same flag. `Market.advance` calls
`CommodityOnMarket.reapplyEventMod` for every commodity every frame; the shipped method removes and
recreates the same `eMod` even when its trade quantity, available value, prior event value, and
commodity econ unit are unchanged. Preflight stores those four post-vanilla values in transient
per-commodity fields, also guards the exact event-mod object and description, and skips only an
exact match. The first call and every changed input delegate
to the preserved method. On a valid memo entry, an exact companion rewrite adds a read-only dirty
accessor to the shipped `MutableStat`; clean backing stats are checked through their object identity
and authoritative public value without calling four getters or recomputing the combined quantity.
The same exact rewrite exposes the current flat-mod map reference. When the JVM permits the same
`java.util` access already present in Starsector's launcher, Preflight retains the map entry and
structural generation so unchanged hits also avoid a hash lookup. Same-key replacement, direct
entry replacement, removal/reinsertion, map replacement, and direct value/description mutation are
all still detected. A launcher without that module access simply keeps the exact hash lookup; if
either transformed accessor is unavailable, the memo disables itself and keeps vanilla.
`commodityEventModMemo` reports hits, delegations, accessor fallback, snapshot capability,
captures, unavailable captures, and invalidations; and
`-Dpreflight.campaign.eventModMemo.disabled=true` disables this memo alone.
A pilot has not proved activation unless
`installed` and `enabled` are both true and either `served` or `missingServed` is nonzero. The flag
also enables a positive-only deployment member-icon cache for the exact reviewed vanilla UI class;
that cache invalidates answers after the class's reviewed add, remove, and clear methods, delegates
once to the preserved scan to obtain each new authoritative answer, and still verifies the cached
icon's current member before reuse. Hits no longer traverse either backing list. Its `additions`,
`removals`, `clears`, `fallbackRecords`, and `validationStrategy` counters report separately under
`deploymentIconCache`; the legacy `snapshots` and `validatedReferences` fields remain present at
zero for report-schema continuity. The flag is rejected in adapter-off and probe modes and is
implied by `--fast`.

Enabled adapter mode also protects the exact reviewed refit simulator from stale merged
`sim_opponents.csv` rows. Immediately before vanilla constructs either simulator fleet, Preflight
asks Starsector's own loaded ship or fighter registry whether each id exists. It returns the
original shared list unchanged when every row is valid, a filtered copy when a row is authoritatively
absent, and the original list on any reflection, list, or registry uncertainty. Results appear under
`simOpponentSafety`; `-Dpreflight.simOpponentSafety.disabled=true` is the narrow kill switch.

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

The wrapper uses the Java runtime that executed the installer and invokes the copied Preflight JAR
with `--fast`. The original Starsector app remains unchanged.

### Linux

`install` creates:

```text
~/.local/bin/starsector-preflight
~/.local/share/applications/starsector-preflight.desktop
```

### Windows

`install` creates a command launcher under Local AppData with `--fast`. Desktop and Start Menu
shortcut generation can be added after native Windows validation.

## Troubleshooting

Run:

```bash
java -jar preflight.jar doctor --game "/path/to/game"
```

Then use `--launcher` with the exact file shown by the relevant vanilla or Fast Rendering installation. `--dry-run` prints the complete command, selected working directory, trace destination, adapter mode, and injected Java option without starting the game.
