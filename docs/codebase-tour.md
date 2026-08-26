# Codebase tour

## TL;DR

This page is for someone who can read code, or is willing to learn while reading it, and wants to answer questions such as:

- What actually happens after I press **Launch Starsector**?
- Where does preparation live?
- Where do the runtime optimizations live?
- Where does a lint finding come from?
- Which layer is allowed to touch the filesystem or start a process?

The shortest useful mental model is:

```text
React desktop UI
       ↓
small Rust/Tauri native host
       ↓
Java CLI/orchestration layer
       ↓
shared Java formats, identity, caches, analysis
       ↓
Java agent inside the Starsector child JVM
       ↓
reviewed runtime adapters + original game/mod code
```

You do not need to understand the whole repository before following one feature. Pick a user-visible action and trace it down through those layers.

For the engineering story behind the optimizations, read [Engineering overview](engineering-overview.md). This page is about finding the code.

## The repository in one screen

The Maven reactor has four Java modules:

```text
preflight-core/
preflight-agent/
preflight-cli/
preflight-synthetic-startup/
```

The desktop product sits beside that reactor:

```text
preflight-desktop/
  src/                 React/TypeScript UI
  src-tauri/src/       Rust native host
```

There are also release/support pieces such as `report-intake/`, scripts, workflows, and the evidence archive. Those are important, but they are usually the wrong place to start when learning the runtime.

## What each Java module owns

### `preflight-core`

Start here when the question is about **data that needs a durable definition**.

Core owns portable concepts such as:

- cache and artifact formats;
- content/profile identity;
- validation and reports;
- atomic publication helpers;
- prepared texture/audio-related representations;
- reusable model types shared by the CLI and agent.

A useful rule of thumb: if a format must mean the same thing during offline preparation and later inside the game process, its durable definition probably belongs here.

The package is broad because the project has accumulated several independent cache families. Do not read it alphabetically. Search for the noun you care about: `Texture`, `Classpath`, `Profile`, `Spec`, `Audio`, `Report`, `Manifest`, `Fingerprint`, and so on.

### `preflight-cli`

Start here when the question begins with **“Preflight is about to do something.”**

The CLI is the orchestration layer. It owns commands and workflows such as:

- discovering the installation/profile;
- `prepare`;
- `run`;
- benchmarking;
- lint/census/analysis commands;
- cache planning and cleanup;
- constructing the child-process launch;
- injecting the Java agent;
- collecting run evidence.

The main command entry is `PreflightCli.java`. For an actual game launch, `RunCommand.java` is a good anchor. Agent setup is split into focused pieces such as `AgentInjection.java` and `AgentLaunchConfig.java`.

For analysis features, the class names are intentionally literal. `AssetLint.java`, `AssetLintCommand.java`, `AudioCensus.java`, and `AudioCensusCommand.java` are good examples.

### `preflight-agent`

Start here when the question is **“what changes inside the Starsector JVM?”**

The agent is injected into the child process. It owns runtime eligibility, target identity, bytecode transformations, adapter state, and evidence about what actually activated.

Useful landmarks include:

- `AdapterTargetRegistry.java` for the catalog of reviewed targets;
- `AdapterRuntime.java` for runtime coordination;
- `AdapterSourceIdentity.java` for exact source binding;
- `AdapterPlanCatalog.java` for the reviewed plan surface;
- `AdapterReport.java` for what the run records about adapter behavior.

This is where the compatibility rule becomes concrete: an optimization only activates when the code/input identity it expects is actually present. Unknown or changed targets leave the original path available.

When an adapter looks mysterious, read its eligibility checks and its tests before reading the bytecode manipulation line by line. The safety boundary usually explains the code better than the transformation itself.

### `preflight-synthetic-startup`

Start here when you want to understand **how the agent is tested without redistributing Starsector**.

This module supplies controlled child-JVM fixtures. It lets tests prove startup, injection, target selection, fallback, and reporting against code the repository is allowed to ship.

It is often easier to learn an adapter from its synthetic fixture and test than from a real obfuscated target.

## Follow one launch

A desktop launch crosses several languages, but the path is simpler than the file count makes it look.

```text
React button/state
   ↓
TypeScript bridge
   ↓
fixed Tauri command
   ↓
Rust process/ownership code
   ↓
Java Preflight command
   ↓
child Starsector JVM + javaagent
   ↓
agent validates/activates eligible adapters
   ↓
run reports flow back to the desktop
```

On the desktop side, start with `preflight-desktop/src/bridge.ts`. It is the browser-facing contract to native code.

Then move into `preflight-desktop/src-tauri/src/`:

- `lib.rs` registers much of the native command surface;
- `engine.rs` owns important Java-engine interaction;
- `operations.rs` owns operation/process coordination;
- `preparation.rs` owns preparation-specific native work;
- `reports.rs` and `report_transport.rs` own support-report handling;
- `updates.rs` owns update installation.

The Rust host is intentionally narrow. If you find yourself looking for game-specific optimization logic in Rust, you are probably in the wrong layer. The host owns OS authority and process/filesystem boundaries; the Java engine owns Starsector-facing behavior.

From there, follow the Java command into `preflight-cli`, then agent injection into `preflight-agent`.

## Follow preparation

Preparation is offline work. It does not itself install a runtime transformation.

The rough path is:

```text
profile/install discovery
      ↓
input census + identity
      ↓
plan storage/work
      ↓
build or reuse independent artifact families
      ↓
validate them
      ↓
atomically publish report/artifacts
```

Start with [Preparation](prepare.md) for the command contract, then search `preflight-cli` for `Prepare` and the artifact family you care about.

When the code jumps into `preflight-core`, that usually means it has reached a reusable format, identity calculation, validation rule, or publication primitive.

A prepared artifact can exist and still be declined at launch. Preparation proves that the artifact is internally ready; the runtime still has to prove that the exact current inputs and adapter target match.

## Follow a runtime optimization

For a specific optimization, use this order:

1. Find its readable explanation in [Optimization history](optimization-history.md) or the evidence archive.
2. Find the target/plan entry in `preflight-agent`.
3. Read the exact identity and eligibility checks.
4. Read the transformation/runtime implementation.
5. Read the synthetic and focused tests.
6. Only then read the older experiments that led to it.

That order keeps an abandoned prototype from looking like current behavior.

The recurring design is:

```text
recognize exact target
      ↓
validate required artifact/state
      ↓
activate narrow shortcut
      ↓
record what happened

anything fails the gate
      ↓
original path remains available
```

## Follow a lint finding

The linter is much easier to read than the runtime adapters because it is intentionally read-only.

Start at:

```text
preflight-cli/.../AssetLintCommand.java
                ↓
preflight-cli/.../AssetLint.java
                ↓
header/config readers + resource/profile identity
                ↓
reported finding
```

Then read [Asset lint](asset-lint.md), which defines the public rule names, severity, cost kind, thresholds, and false-positive philosophy.

A lint rule should be understandable from the source file it examines. If proving a rule requires guessing artistic intent or modifying the game, it is probably outside the linter's intended boundary.

## Follow a desktop feature

Use the direction of authority:

```text
React component/hook
      ↓
bridge.ts type + call
      ↓
Tauri command
      ↓
Rust validation/ownership
      ↓
Java engine command or bounded native action
```

If a React component contains path manipulation, process ownership, arbitrary command construction, or release-signing logic, something has probably crossed the intended boundary.

For visual behavior, pair the source with [UI design](ui-design.md) and the rendered preview scenarios. Source alone cannot tell you whether the interface actually works at the supported window sizes.

## Where identity and compatibility show up

A lot of Preflight code can look like defensive bookkeeping until you know what problem it is solving.

The project accelerates an application assembled from:

- an obfuscated game;
- third-party mods that change independently;
- ordered resource overlays;
- generated code;
- persisted prepared data;
- multiple launcher/runtime configurations.

So identity is part of the optimization. Content hashes, provider order, class/source identity, format versions, profile fingerprints, and target fingerprints answer one question: **is this still the exact situation for which the shortcut was proven?**

When reading a class that appears to spend a lot of effort naming/versioning/checking an input, treat that as functional code, not ceremony.

## How the tests are arranged

A useful reading habit is to search for the production class name under `src/test` immediately.

The test suite roughly has three jobs:

- **pure format/logic tests** for portable code;
- **synthetic integration tests** for process/agent/runtime behavior;
- **real-install evidence** for claims that cannot be established from redistributable fixtures.

The third category lives mostly under `docs/evidence/` rather than in CI. Evidence documents record what happened on a specific installation/profile. They do not automatically redefine current product behavior.

## Good first traces

If you want to learn by following something concrete, these are good exercises:

1. **A lint rule:** `audio-oversampled` or `texture-progressive` from command to finding.
2. **A desktop launch:** React bridge to Rust host to `RunCommand` to agent injection.
3. **A prepared texture artifact:** preparation planner to core format to runtime reader.
4. **One adapter:** target registry entry, identity gate, transformation, report, tests.
5. **One benchmark:** command, child process, endpoint observation, retained report.

Each trace crosses enough of the project to teach the boundaries without requiring a repository-wide reading marathon.

## What to ignore on a first pass

You can safely postpone:

- dated evidence unless the current code links you there;
- old implementation handoffs;
- release rehearsal details;
- rejected experiments;
- packaging/signing internals;
- specialist compatibility probes unrelated to the feature you are tracing.

They exist because the project keeps its engineering record. They are reference material, not prerequisites.

## Reading order for a technically curious newcomer

1. [Preflight in 256 KB](how-preflight-works-256kb.md)
2. [How Preflight works](how-preflight-works.md)
3. **this codebase tour**
4. [Engineering overview](engineering-overview.md)
5. one concrete trace through the code
6. [Architecture](architecture.md) when you need the exact module/persistence boundaries
7. evidence/history only for the feature you are investigating

You will understand the repository faster by following data and authority through one real action than by reading every top-level class.