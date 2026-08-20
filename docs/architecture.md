# Architecture

## Principles

1. Source mods, saves, launcher files, and VM parameter files remain untouched except for explicit,
   confirmed preference/launcher-setting operations owned by the product contract.
2. Preflight-owned acceleration data is disposable and rebuildable.
3. Cache failures return to the original Starsector path.
4. Every persisted format and runtime integration interface is versioned.
5. Content identity, enabled-mod order, source bindings, and transformation configuration
   participate in invalidation.
6. Existing files are resolved through canonical roots; links that escape an approved root are
   rejected.
7. First preparation and repeat-launch performance are reported separately.
8. A performance claim requires repeated successful real-install comparisons with its exact context.

The current behavioral boundary is [product-contract.md](product-contract.md). Current beta blockers
live in [#652](https://github.com/teamleaderleo/preflight/issues/652) and are mirrored in
[release-readiness.md](release-readiness.md).

## Modules

### `preflight-core`

Portable identity, format, validation, and report code. Persisted families include resource-provider
indexes, classpath profile/archive indexes, texture manifests and prepared blobs/packs, SpecStore
profile/data stores, generated-bytecode records, and bounded evidence formats. Format changes use
explicit versioning and, where required, version-qualified cache directories.

### `preflight-agent`

A Java 17 agent injected into the selected child launcher through process-local `JAVA_TOOL_OPTIONS`.
It starts the configured JFR recording, records bounded evidence, applies exact source-bound adapter
targets, and leaves unknown or changed installations on the original path.

Ordinary rotating recordings use the JVM's JFR shutdown behavior. Single-chunk benchmark recordings
use the live request/ack path so the benchmark can require the final agent event before accepting the
recording.

The texture runtime keeps two reviewed paths: compatibility preserves Starsector's asynchronous
preloader contract, while prepared pixels can serve validated upload-ready data and retain the
game's upload/lifetime path. Exact current behavior and accepted history are recorded in
[optimization-history.md](optimization-history.md).

Generated-bytecode and mod-owned adapters use the same fail-open rule: incomplete or changed
identity evidence returns to the original implementation. Each optimization remains independently
disableable through its reviewed plan/preset boundary.

### `preflight-cli`

The runnable wrapper and preparation engine. It discovers the existing launcher, inventories the
enabled profile, prepares and validates eligible artifacts, injects the agent, records run evidence,
summarizes JFR, and exposes deterministic benchmark and analysis commands.

`prepare` is an offline preparation command. It currently coordinates the profile census,
resource-provider index, classpath profile/archive indexes, exact SpecStore profile identity, and
prepared texture pack/blob work. Other Recommended acceleration data is learned or materialized at
its own exact runtime boundary. See [prepare.md](prepare.md) for the operator-facing command.

### `preflight-synthetic-startup`

Packaged child-JVM fixtures verify agent startup, exact target selection, fallback behavior,
reporting, and cross-platform launch behavior without distributing Starsector binaries.

## Launch flow

```text
preflight.jar run
  -> discover the existing Starsector or supported alternate launcher
  -> resolve the selected profile and eligible prepared artifacts
  -> create an isolated run directory
  -> add the same JAR as a process-local javaagent
  -> start the original launcher as a child process
  -> preserve the child's result and bounded fatal-log evidence
  -> write final run, adapter, profile, and JFR-derived reports
```

A raw CLI `run` is unoptimized unless a preset or individual adapter option is selected. The
installed launcher and desktop product select **Recommended**; **Conservative** limits acceleration
to its reviewed portable subset; **Off** retains process ownership and bounded outcome reporting.
Preparation alone never activates a runtime transformation. Exact identity, artifact validation,
and environment/property kill switches remain authoritative.

## Current prepared-data layout

The cache root contains several independent, versioned families. Directory names can gain version
suffixes when an on-disk format changes, so operator code should use the owning directory helper
instead of constructing paths from this diagram.

```text
~/.starsector-preflight/
  cache/
    resource-indexes[/version-qualified]/
      PROFILE.spfi
    classpath[/version-qualified]/
      profiles/PROFILE.spfc
      archives/HH/SOURCE_HASH.spfj
    spec-store/
      profiles/
      variant-json[/version-qualified]/
      weapon-json[/version-qualified]/
      projectile-json[/version-qualified]/
      hull-json[/version-qualified]/
      rules-csv[/version-qualified]/
      rule-command-classes[/version-qualified]/
      merged-reads[/version-qualified]/
    manifests[/version-qualified]/
      PROFILE.spfm
    packs/
      PROFILE.spfp
    blobs/...
    quarantine/...
    reports/preparation-latest.json
  runs/YYYYMMDD-HHMMSS-SSS-NONCE/
    run.json
    profile.json
    startup.jfr
    summary.json
    adapter.json
    adapter-analysis.json
```

The profile texture pack is a single indexed `.spfp` file for the profile's prepared texture blobs;
its header carries the pack format version. SpecStore data uses its own cache family beneath
`spec-store/`. Classpath profiles and content-addressed archive indexes share their co-versioned
classpath namespace. These families were missing from the older cache diagram and are part of the
current implementation.

Content-addressed artifacts may be reused across profiles where their identities allow it.
Fingerprint-named profile artifacts bind launch-time selection to exact inputs. Corrupt,
identity-mismatched, missing, stale, ambiguous, unsupported, or escaped paths decline to the
original game behavior or a rebuild path according to the owning component.

## Current evidence boundary

The development profile has accepted live evidence for the Recommended stack across the reviewed
game/mod identities. That evidence establishes the tested profile and exact targets; broader
compatibility claims continue to require their own runs. Unknown class, source, loader, artifact, or
profile identities decline the affected optimization and are reported.

Performance history is chronological. Earlier gates include the retained 15.88-second warm record;
the newer controlled same-profile comparison measured an 89.00-second ordinary median and a
15.53-second Preflight median, with a 15.25-second low. All of those are development evidence on the
reviewed machine/profile. The remaining release task is the benchmark on the exact packaged
candidate bytes. [Optimization history](optimization-history.md) owns the readable chronology and
[startup-benchmark.md](startup-benchmark.md) owns the measurement protocol.
