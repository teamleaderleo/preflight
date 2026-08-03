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

The startup recording is dumped by the JVM's own JFR shutdown hook (`dumpOnExit`), never by the agent's shutdown hook. Both hooks run concurrently, and the JVM's hook wipes the JFR chunk repository as its last step, so an agent-side `Recording.stop()` can be caught between stopping the recording and transferring its chunks and dump a zero-byte file over the destination. The agent's own hook is therefore limited to best-effort evidence and adapter-report finalization.

The current live texture plans are:

- `texture-compatibility-v2`: reconstructs a decoded image from a verified prepared blob while preserving Starsector's asynchronous preloader and the rest of the original texture path. It passed bounded behavioral acceptance on Starsector 0.98a-RC8 on 2026-07-19. Repeated timing remains pending.
- `texture-prepared-pixels-v2`: retains Starsector's upload and lifetime path while aiming to bypass decode and pixel conversion. It remains fail-closed until the installed color-transfer dataflow is represented exactly.

The generated-bytecode wrapper remains fail-open: incomplete evidence calls the original generator
and bypasses storage. Its explicit-beta live Janino plan uses a conservative whole-profile identity,
then rechecks the live compiler policy before serving a complete mutable class map.

Mod-owned adapters use the same boundary as game-owned ones. The two GraphicsLib 1.12.1 pilots pin
the exact class, whole mod archive, and URL classloader. Compact replay replaces one reviewed class;
the insignia pilot splices one accessor while preserving the original render body. Neither is part
of `--fast` until its separate live startup or combat acceptance gate passes.

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

Adapter mode is OFF by default. Cache preparation alone never enables a transformation. A live texture run requires explicit adapter activation plus validated matching artifacts. The environment/property kill switch remains authoritative.

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

Content-addressed blobs may be shared by multiple profiles. Fingerprint-named manifests and indexes bind a launch to one exact profile. Corrupt or identity-mismatched artifacts are bounded and quarantined; missing, stale, ambiguous, unsupported, or escaped paths use the original game path.

## Current evidence boundary

Compatibility-v2 has a real behavioral acceptance result and no performance claim. The Janino cache
has installed-JAR compilation/replay evidence and remains explicit beta pending one clean real launch
and repeated OFF-versus-ENABLED timing. Performance claims still require one stable profile and
comparable JVM/run identities.
