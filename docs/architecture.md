# Architecture

## Principles

1. Source mods, saves, launcher files, and VM parameter files remain untouched.
2. Cache deletion is always safe.
3. Cache failures return to the original Starsector loading path.
4. Every persisted format and runtime integration interface is versioned.
5. Content identity, enabled-mod order, source bindings, and transformation configuration participate in invalidation.
6. Existing files are resolved through canonical roots; links that escape an approved root are rejected.
7. First-build and repeat-launch performance are reported separately.
8. A performance claim requires repeated successful real-install comparisons.

## Modules

### `preflight-core`

Portable identity, format, validation, and report code. Current persisted formats include the resource-provider index, texture manifest, prepared texture blob, classpath profile/archive indexes, and generated-bytecode records. Files use explicit versions, bounds, checksums, and atomic replacement where applicable.

### `preflight-agent`

A Java 17 agent injected into the selected child launcher through process-local `JAVA_TOOL_OPTIONS`. It starts JFR in `premain`, records bounded evidence, applies exact source-bound adapter targets, and leaves unknown or changed installations untouched.

Ordinary rotating recordings are dumped by the JVM's own JFR shutdown hook (`dumpOnExit`), never
stopped by the agent's shutdown hook. Both hooks run concurrently, and HotSpot wipes the JFR chunk
repository as its last step, so a competing agent-side stop can race into an empty destination.
Single-chunk recordings instead use a file request/ack protocol while the JVM is still live: the
agent commits `preflight.AgentStopping`, stops and closes the recording synchronously, then writes
the acknowledgement. The benchmark refuses evidence without that acknowledgement. The agent's
shutdown hook remains a last-chance non-empty-file fallback, but a JVM already entering shutdown
can't promise the final custom event and isn't treated as the deterministic boundary path.

The prepared-texture path is split into exact stages:

- `texture-compatibility-v2` preserves Starsector's asynchronous preloader and decoded-image
  contract for conservative compatibility.
- `texture-prepared-pixels-v2` serves validated upload-ready pixels while retaining Starsector's
  upload and lifetime path. Its accepted integration gates, packed storage, index snapshot, and
  visual regressions are recorded in the [optimization history](optimization-history.md).

The generated-bytecode wrapper remains fail-open: incomplete evidence calls the original generator
and bypasses storage. Its live-gated Janino plan uses a conservative whole-profile identity, then
rechecks the live compiler policy before serving a complete mutable class map.

Mod-owned adapters use the same boundary as game-owned ones. The two GraphicsLib 1.12.1 pilots pin
the exact class, whole mod archive, and URL classloader. Compact replay replaces the reviewed class;
the insignia pilot splices the reviewed accessor while preserving the original render body. Both
passed
their separate live startup or combat acceptance gates and are included by `--fast`.

### `preflight-cli`

The runnable wrapper and cache builder. It discovers the existing launcher, inventories the enabled profile, builds and validates caches, injects the agent, records run evidence, summarizes JFR, and exposes deterministic benchmark scenario records.

Important commands include `doctor`, `scan`, `prepare`, `run`, `index`, `texture`, `classpath`, `audio`, `analyze`, and `benchmark`.

### `preflight-synthetic-startup`

Packaged child-JVM fixtures used to verify agent startup, exact target selection, fallback behavior, reporting, and cross-platform launch behavior without distributing Starsector binaries.

## Launch flow

```text
preflight.jar run
  -> discover the existing Starsector or Fast Rendering launcher
  -> resolve the selected profile and optional exact prepared artifacts
  -> create an isolated run directory
  -> add the same JAR as a process-local javaagent
  -> start the original launcher as a child process
  -> preserve the child's result and bounded fatal-log evidence
  -> write final run, adapter, profile, and JFR-derived reports
```

A raw CLI `run` is unoptimized unless a preset or individual adapter option is selected. The
installed launcher and desktop product select **Recommended**; **Conservative** restricts plans to
portable startup work; **Off** retains only process ownership and bounded outcome reporting. Cache
preparation alone never enables a transformation. Exact identity, artifact validation, and every
environment/property kill switch remain authoritative.

## Current cache and run layout

```text
~/.starsector-preflight/
  cache/
    resource-indexes/PROFILE.spfi
    classpath/profiles/PROFILE.spfc
    manifests/PROFILE.spfm
    blobs/HH/SOURCE_HASH-identity.spft
    quarantine/
    reports/preparation-latest.json
  runs/YYYYMMDD-HHMMSS-SSS-NONCE/
    run.json
    profile.json
    startup.jfr
    summary.json
    adapter.json
    adapter-analysis.json
```

Content-addressed blobs may be shared by multiple profiles. Fingerprint-named manifests and indexes
bind a launch to an exact profile. Corrupt or identity-mismatched artifacts are bounded and
quarantined; missing, stale, ambiguous, unsupported, or escaped paths use the original game path.

## Current evidence boundary

The development profile has clean live gates for the Recommended stack, including prepared
textures, merged/spec data, generated Janino bytecode, prepared audio, exact vanilla gameplay
indexes, and reviewed mod-specific adapters. That doesn't establish universal compatibility.
Unknown class, source, loader, artifact, or profile identities decline to the original path and are
reported. The shutdown report carries a bounded catalog for all 58 direct and composed plans,
including each exact host boundary, filter state, and original-bytecode fallback. A wrapper or
discovery change can still require a Preflight update.

Performance claims require comparable game, mod-profile, JVM, launcher, cache, machine-load, and
measurement identities. The development history runs from a roughly 101-second observed worst case
and an 88.13-second five-run median to a retained 15.88-second warm record.
The release candidate benchmark adds the packaged result without replacing that development
history.
