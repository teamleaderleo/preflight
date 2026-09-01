# Codebase tour

## TL;DR

If you can read code, or you're willing to learn while reading it, this is the shortest useful map:

```text
React desktop UI
       ↓
Rust/Tauri native host
       ↓
Java CLI/orchestration
       ↓
shared Java formats, identity, caches, analysis
       ↓
Java agent inside Starsector
       ↓
reviewed adapters + original game/mod code
```

Pick one user-visible action and follow it downward. Don't try to learn the repository by reading every directory.

For *why* the optimizations exist, read [Engineering overview](engineering-overview.md). This page is about finding the code.

## The main pieces

### `preflight-core`

Portable definitions shared by preparation and runtime:

- cache/artifact formats;
- profile/content identity;
- validation and reports;
- atomic publication;
- reusable prepared-data models.

If a thing must mean the same thing offline and later inside the game process, its durable definition probably lives here.

### `preflight-cli`

The orchestration layer. Start here for `prepare`, `run`, benchmarks, lint/census commands, cache planning, child-process launch, agent injection, and run evidence.

Useful anchors:

- `PreflightCli.java` — command entry;
- `RunCommand.java` — game launch;
- `AgentInjection.java` / `AgentLaunchConfig.java` — agent setup;
- `AssetLint.java` / `AssetLintCommand.java` — linter;
- `AudioCensus.java` / `AudioCensusCommand.java` — audio analysis.

### `preflight-agent`

What can change inside the Starsector JVM.

Useful anchors:

- `AdapterTargetRegistry.java` — reviewed targets;
- `AdapterRuntime.java` — runtime coordination;
- `AdapterSourceIdentity.java` — exact source binding;
- `AdapterPlanCatalog.java` — reviewed plan surface;
- `AdapterReport.java` — what actually activated.

Read the eligibility checks and tests before the bytecode manipulation. They usually explain the adapter faster.

### `preflight-synthetic-startup`

Controlled child-JVM fixtures for testing agent startup, target selection, fallback, and reporting without shipping Starsector code.

### `preflight-desktop`

```text
src/            React/TypeScript UI
src-tauri/src/  Rust native host
```

React renders. Rust owns bounded OS authority. Java owns Starsector-facing behavior.

## Follow one launch

```text
React action
   ↓
preflight-desktop/src/bridge.ts
   ↓
fixed Tauri command
   ↓
Rust validation/process ownership
   ↓
Java RunCommand
   ↓
child Starsector JVM + javaagent
   ↓
eligible adapters
   ↓
run reports back to desktop
```

Useful Rust files:

- `lib.rs` — native command surface;
- `engine.rs` — Java engine interaction;
- `operations.rs` — process/operation coordination;
- `preparation.rs` — preparation work;
- `reports.rs` / `report_transport.rs` — support reports;
- `updates.rs` — updates.

If you're looking for game-specific optimization logic in Rust, you're probably in the wrong layer.

## Follow preparation

```text
installation/profile discovery
      ↓
identity + census
      ↓
storage/work plan
      ↓
build or reuse artifacts
      ↓
validate
      ↓
atomically publish
```

Start with [Preparation](prepare.md), then search `preflight-cli` for `Prepare` plus the artifact family you care about.

When the path jumps into `preflight-core`, it has usually reached a durable format, identity calculation, validator, or publication primitive.

Preparation alone doesn't activate an optimization. Runtime still checks the exact current target and inputs.

## Follow one runtime optimization

Use this order:

1. readable explanation in [Optimization history](optimization-history.md);
2. target/plan entry in `preflight-agent`;
3. identity and eligibility checks;
4. transformation/runtime implementation;
5. focused and synthetic tests;
6. old evidence only if you need the history.

The recurring pattern is:

```text
recognize exact target
      ↓
validate artifact/state
      ↓
activate narrow shortcut
      ↓
record result

check fails → original path remains available
```

## Follow a lint finding

```text
AssetLintCommand.java
      ↓
AssetLint.java
      ↓
header/config readers + profile identity
      ↓
finding
```

Then read [Asset lint](asset-lint.md) for the public rule name, threshold, severity, cost kind, and false-positive policy.

The linter is read-only. A good lint rule can be justified from the file/profile data it actually sees without guessing artistic intent.

## Why there is so much identity code

Preflight sits between an obfuscated game, independently changing mods, ordered resource overlays, generated code, persisted artifacts, and multiple launcher/runtime configurations.

Hashes, provider order, class/source identity, format versions, profile fingerprints, and target fingerprints all answer the same question:

**Is this still the exact situation for which this shortcut was proven?**

Treat that checking as functional code, not ceremony.

## How to learn the repository without drowning

Good first traces:

1. `audio-oversampled` from CLI command to lint finding;
2. desktop Launch from `bridge.ts` to Rust to `RunCommand`;
3. one prepared texture artifact from planner to core format to runtime reader;
4. one adapter from target registry to eligibility gate to tests.

Search for the production class under `src/test` immediately. Tests often explain the intended boundary more clearly than the implementation.

You can postpone dated evidence, release rehearsals, old handoffs, rejected experiments, and packaging/signing internals until a current code path links you there.

## Suggested reading order

1. [Preflight in 256 KB](how-preflight-works-256kb.md)
2. [How Preflight works](how-preflight-works.md)
3. **this page**
4. one concrete trace through the code
5. [Engineering overview](engineering-overview.md)
6. [Architecture](architecture.md) when you need exact module/persistence boundaries

Following one real action teaches the repository faster than reading it top to bottom.