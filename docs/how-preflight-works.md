# How Preflight works

A plain-language tour of what the project does, why it does it, and which code is responsible.

This is the document to read when the repository feels like a pile of Java, Rust, React, tests,
benchmark scripts, caches, launch wrappers, and very specific safeguards.

The short version: **Preflight tries to stop Starsector from doing expensive deterministic work over
and over again, while keeping the original game path available whenever Preflight cannot prove that
its shortcut still applies.** Everything else in the project grows out of that idea.

For exact guarantees and edge cases, the authoritative documents remain the
[product contract](product-contract.md), [engineering overview](engineering-overview.md),
[UI design guide](ui-design.md), and retained [evidence](evidence/). This guide is the tour, not the
contract.

## The five-second mental model

Think of Preflight as five cooperating pieces:

```text
Player
  ↓
React desktop UI
  ↓
small Rust/Tauri native host
  ↓
Java command engine
  ↓
Starsector child JVM + Preflight Java agent

             ↘ Preflight-owned prepared data, history, reports, profiles
```

The UI shows the player what is happening. The Rust host owns the small set of things a desktop app
must ask the operating system to do. The Java command engine understands Starsector, mods,
preparation, profiles, launches, history, and diagnostics. The Java agent lives inside the game JVM
for the duration of one launch and applies exact runtime shortcuts.

The game installation remains the game installation. Preflight does most of its writing under its
own home directory. The two important exceptions are explicit player actions: activating a named mod
profile and changing supported Starsector launch settings. Those paths preview and back up what they
change.

## What problem are we actually solving?

A heavily modded Starsector installation repeats an enormous amount of work every time it starts.
Examples include:

- decoding the same textures;
- reading and merging the same JSON and CSV data;
- compiling the same generated Java code;
- decoding the same audio;
- repeatedly scanning or recomputing data during campaign simulation.

If the game, enabled mods, file contents, ordering, and relevant code are unchanged, much of the
answer is also unchanged.

So the recurring Preflight question is:

> Can we calculate this answer once, identify exactly what inputs produced it, and safely reuse it
> while those inputs still match?

When the answer is yes, Preflight prepares or remembers the result. When the answer becomes unclear,
it lets the game's original code run.

That is the central performance trick. The difficult engineering is proving when reuse is valid.

## What happens the first time I open the desktop app?

### 1. React draws the interface

The desktop UI is React. It is responsible for presentation: Home, setup, profiles, settings,
benchmark results, storage information, recovery, Help, and so on.

The browser layer does **not** get a generic shell or arbitrary filesystem API. That keeps the
presentation layer from quietly becoming an all-powerful desktop process.

### 2. The Rust/Tauri host handles native operations

The React app talks to a small Rust host through a fixed set of typed commands.

The host can do specific things the product needs, such as:

- resolve the packaged Java engine;
- ask it for a versioned snapshot;
- start a tracked preparation or game process;
- open a native folder/save dialog;
- manage a signed application update;
- enforce native process ownership rules.

It does not accept an arbitrary command string from the frontend.

Why have this middle layer? Because the UI should be easy to render and change, while native process
and filesystem access should stay narrow and reviewable.

### 3. The Java engine finds Starsector and reads the current setup

The Java side discovers the installation, launcher, enabled mods, preferences, and Preflight-owned
state. It turns those into versioned data for the desktop.

At this point most work is observation. Preflight can tell the player what it found before asking to
write or launch anything.

### 4. Preflight calculates preparation and storage needs

Before building prepared data, the engine works out what the current profile needs and how much disk
space the operation may require.

The important idea is that preparation belongs to an **exact profile identity**, not merely to a
folder called `cache`.

The identity can include things such as the game build, enabled mod order, source file contents,
relevant implementation versions, and representation format. A changed input selects a different
answer instead of silently reusing the old one.

## What does “prepare” actually mean?

Preparation is simply doing reusable work before the game needs it.

Instead of waiting for the game to ask:

> Decode this texture now.

Preflight can say:

> I already decoded the exact source this profile uses, validated the result, and stored a form the
> reviewed runtime shortcut can consume.

Different domains use different prepared representations, but the philosophy is the same.

### Textures

Texture work was one of the largest startup costs.

Preflight can prepare texture data ahead of launch and package it in a form intended for efficient
startup access. The physical order of that prepared data can also follow observed game access order,
which reduces unnecessary I/O waiting.

This is more than “save a PNG cache.” It includes deciding *where in the loading path* the prepared
answer must be consulted. An early implementation had correct cache hits while the loading thread had
already waited behind the expensive queue. The cache was right and still too late.

### Merged game/mod data

Starsector and mods repeatedly ask for resolved JSON/CSV data. Several apparently different loaders
ultimately repeated work at a shared lower read boundary.

Preflight can memoize the resolved result there and rehydrate it in a form compatible with the
installed JSON runtime.

The lesson is useful beyond this project: when five callers need the same expensive answer, the best
cache often belongs below all five callers.

### Generated Java bytecode

Some mods compile Java code at runtime. If the same source and compilation inputs are unchanged,
recompiling it every launch is repeated work.

Preflight can remember generated output and replay it. Later profiling showed that even the stored
output contained huge duplication, so equivalent generated classes were deduplicated too.

There are therefore two separate wins:

1. avoid compiling the same thing again;
2. avoid storing the same generated result hundreds of times under different requests.

### Audio

Prepared audio follows the same general rule: inspect the exact source and codec, establish an
eligible path, and retain a validated reusable result.

Unsupported or malformed inputs stay on the original path.

## Where does the prepared data live?

Under Preflight-owned storage, separate from Starsector's game and mod files.

That distinction is important. Prepared data is disposable in the sense that it can be rebuilt from
its inputs. Game files, mod files, and saves are user/game-owned source material.

Preflight therefore treats a cache failure differently from game corruption:

- a missing prepared entry is a cache miss;
- a damaged prepared entry is rejected and can be rebuilt;
- a changed mod selects a new identity;
- the original loader remains available.

The storage UI exists because prepared data can still be large. It calculates the expected cost,
shows cleanup before deletion, and keeps profile reachability in mind.

## Okay, then what happens when I click Launch Starsector?

This is the most important end-to-end path.

### Step 1: the desktop asks the Java engine to launch

The Tauri host starts the packaged Preflight Java engine with one of the supported optimization
presets.

The Java engine owns the launch semantics. The frontend does not assemble JVM flags or adapter lists
itself.

### Step 2: the engine resolves the actual launcher command

Preflight finds the selected Starsector launcher path and works out the command that would start the
game.

It wraps that launch instead of permanently editing the launcher script or installing a machine-wide
Java hook.

This is why Preflight can only accelerate launches that pass through Preflight.

### Step 3: the game starts as a child JVM with the Preflight Java agent

The Java agent is attached to that child process. Runtime changes therefore live inside this one game
process and disappear when it exits.

The agent sees selected classes as the JVM loads them and asks whether a reviewed optimization plan
applies.

### Step 4: every runtime shortcut proves its target before touching it

A runtime shortcut can care about details such as:

- which class was loaded;
- which JAR or source supplied it;
- which classloader loaded it;
- expected bytecode or method descriptors;
- the prepared-data identity it expects;
- the exact mod/game version it was reviewed against.

If those checks succeed, the agent can transform the in-memory class so the expensive operation uses
the prepared/memoized path.

If those checks fail, that shortcut declines.

### Step 5: a declined shortcut leaves the original behavior available

This is one of the most important ideas in Preflight.

The project does not try to make one giant claim that every mod and future version is equivalent.
Each optimization has its own eligibility boundary.

A new mod version can therefore mean:

```text
specific optimization unavailable
              ↓
original code runs
              ↓
game can still launch
```

That is why fallback is part of the optimization design instead of a later compatibility patch.

## Are we replacing Starsector code?

Temporarily, inside the launched JVM, some reviewed class behavior can be transformed.

Permanently, on disk, the game/mod JARs remain untouched.

That distinction sounds subtle but explains a lot of the repository. Preflight is willing to make
deep runtime changes because they are narrow, checked, session-local, and independently disableable.
It avoids silently rewriting the user's installed binaries to make those changes stick.

## What happens while the game is running?

Startup was the first target, but profiling found repeated work during campaign simulation too.

Some high-frequency runtime paths repeatedly scan or recompute data whose underlying state has not
changed. Preflight can maintain indexes or memoized answers and invalidate them when the relevant
state changes.

Again, the goal is the same:

> Do the expensive work when the answer changes, instead of every time somebody asks for the answer.

Preflight also tracks the process it launched so it can record playtime and launch outcomes without
requiring the desktop window to stay open the whole session.

## What is the launch ledger / playtime system doing?

Preflight tracks launches it owns and derives play history from those sessions.

It does not need a second magical counter hidden in the UI. The durable launch records are the source
from which totals and session history can be calculated.

That makes restart and recovery behavior easier to reason about: the UI can be rebuilt from retained
records instead of trusting one mutable number.

## What changes Starsector files on purpose?

Most Preflight actions read Starsector and write only to Preflight's own directories.

Two player-requested features deliberately write game-owned preferences.

### Named mod profile activation

A saved Preflight profile is a description of which mods should be enabled.

Activating one can replace `mods/enabled_mods.json`, so the flow is intentionally more careful than a
plain file write:

1. preview the requested change;
2. make sure required mods are present;
3. re-read the current source before committing;
4. save a backup;
5. stage the complete replacement;
6. publish it with the strongest safe same-directory move available;
7. refuse or recover when the reviewed state changed underneath the operation.

The profile contains references to mods; duplicating a profile does not copy the mods themselves.

### Launch settings

The desktop exposes selected Starsector preferences such as resolution, fullscreen, sound,
antialiasing, UI scale, memory, and battle size.

When the player confirms Apply, Preflight updates the supported existing preferences while preserving
unrelated data and retaining a bounded backup/recovery path.

These are explicit settings features, not hidden side effects of launching.

## What is the operation lease for?

Preparation, launch, profile activation, settings changes, cleanup, and similar mutations should not
step on each other.

Preflight therefore has a cross-process ownership mechanism for Preflight operations.

A simple example:

```text
Preparation owns profile A
        ↓
second Preflight launch request arrives
        ↓
second operation waits/refuses instead of mutating the same state concurrently
```

The operating system releases the underlying ownership when a process dies. A small record lets a
later run explain and clean up incomplete Preflight-owned temporary work.

This lease coordinates Preflight processes. It cannot magically lock unrelated programs such as a
mod manager, which is why some explicit settings operations still ask the player to close programs
that can edit the same file.

## What do Doctor, setup analysis, and the mod linter do?

These are mostly read-only ways to understand an installation.

### Discovery / Doctor

This answers questions such as:

- where is Starsector?
- which launcher will be used?
- what does Preflight think the current profile is?
- is the installation usable for the requested action?

### Deep setup analysis

This goes further into the enabled mod set and can find problems such as missing dependencies,
duplicate IDs, broken metadata, or selected references that point at unavailable content.

It does this without needing to start the game.

### Mod linting

The linter looks for measurable asset/configuration problems: suspiciously expensive textures,
problematic encodings, broken configuration relationships, and similar issues.

It reports findings. It does not “fix” somebody else's mod behind their back.

That choice is deliberate. Analysis can be broadly useful while automatic correction would require
much stronger assumptions about author intent.

## What are diagnostics and support reports doing?

There are two different ideas here.

### Copy setup / small summaries

These are meant for normal human conversation. They produce bounded, useful facts without dumping
private paths or arbitrary logs.

### Support ZIP

This is a separately disclosed package assembled from an allowlist. The engine chooses what can enter
it; the frontend cannot point at arbitrary files and ask Preflight to upload them.

If report sending is enabled in a release build, the native host rechecks the exact ZIP before
sending it to the fixed configured intake origin.

Ordinary launches are not an ambient telemetry channel.

## Why React + Rust + Java instead of one language?

Because each layer has a different job.

### React: presentation

Good at interactive UI, layouts, themes, forms, previews, and fast iteration.

### Rust/Tauri: narrow native authority

Good place to own OS process launching, native dialogs, package/update behavior, and the small trusted
bridge between the UI and the engine.

### Java: Starsector-facing logic

Starsector is a Java game. The performance engine needs to understand JVM launch behavior, bytecode,
classloading, game/mod data, and Java-side runtime instrumentation.

Keeping the core product logic in Java also means the CLI and desktop share the same implementation
instead of having two independent versions of “prepare” or “launch.”

## What are the Java modules?

The root Maven reactor currently has four modules.

### `preflight-core`

Reusable engine logic and data types. This is where code tends to live when it is useful to more than
one command or adapter and does not need to be a CLI entry point.

### `preflight-agent`

The Java-agent side that runs inside the Starsector child JVM. It owns runtime instrumentation and
reviewed in-memory transformations.

### `preflight-cli`

The command/application layer: discovery, preparation, launch orchestration, profiles, settings,
history, reports, analysis commands, and the packaged executable JAR.

The desktop ultimately calls this same engine instead of reimplementing those operations in Rust or
TypeScript.

### `preflight-synthetic-startup`

A lab for synthetic workloads and cross-process experiments. It helps test performance mechanisms
without requiring a real Starsector launch for every experiment. Player-facing product logic should
live elsewhere.

## What lives outside the Java reactor?

### `preflight-desktop/`

The React/Tauri desktop application, native packaging scripts, release-contract tests, and platform
configuration.

### `report-intake/`

The optional support-report intake service and its server-side validation/deployment logic.

### `scripts/`

Benchmarking, profiling, release verification, evidence processing, repository checks, and operator
tools.

Some scripts exist because they interrogate the real game or package environment and do not belong
inside the shipped application.

### `docs/`

Product contracts, design guidance, evidence, measurement history, release notes, and explanatory
material such as this guide.

## When somebody says “we optimized X,” what work actually happened?

A healthy performance change usually looks like this:

### 1. Measure the real system

Profile or instrument an actual launch/gameplay path.

Do not optimize the thing that merely *looks* slow on a loading screen.

### 2. Identify repeated work

Find a boundary where the same expensive answer is being recomputed.

### 3. Decide what makes that answer valid

List the inputs whose change would make reuse wrong.

### 4. Build a reusable representation

This might be a prepared pack, memoized data tree, generated-class store, lookup index, or another
purpose-built result.

### 5. Put the shortcut before the cost

A perfect cache consulted after the expensive queue still loses.

### 6. Make failure decline cleanly

Changed code, malformed data, a missing cache entry, or an unsupported provider should select the
original behavior or an explicit recovery path.

### 7. Prove equivalence

Use unit tests, integration tests, synthetic tests, installed-code inspection, or real-game pilots as
appropriate.

### 8. Measure the whole result again

A micro-benchmark can improve while startup gets worse. End-to-end measurements decide whether the
change earned its complexity.

### 9. Keep the useful regression boundary

Once a failure teaches something reusable, keep the smallest test or contract that prevents that
specific mistake from quietly returning.

This last step is why the repository has many unusual tests. Recent cleanup moved ordinary
regressions back into normal `mvn verify` and kept dedicated workflows only when the environment or
release boundary is itself part of the test.

## What happens after someone changes the code?

The default development loop is intentionally less exotic than the product internals.

### Java changes

`mvn verify` runs the normal reactor tests, including packaged integration tests where applicable.
Error Prone separately catches selected Java portability mistakes such as locale-sensitive case
conversion and implicit default charset use.

The scheduled broad Java run supplies additional operating-system coverage.

### Desktop changes

Desktop verification builds/tests the frontend, prepares the verified Java engine used by the
native app, checks release contracts, and runs Rust/native validation when the changed files require
it.

### Why some workflows remain separate

A dedicated workflow earns its keep when the environment changes the answer. Examples include:

- filesystem/process behavior that differs by OS;
- native package install/update/rollback/removal;
- release signing and exact artifact identity;
- real-game/operator exercises;
- deliberately large stress workloads.

A normal JUnit test does not get a personal GitHub Actions workflow merely because the bug that
created it was memorable.

The compact policy is in [CI policy](ci-philosophy.md).

## What happens when we make a release?

This path is intentionally more suspicious than normal development because these are the bytes users
will install.

At a high level:

```text
reviewed source
   ↓
verified Java engine + frontend
   ↓
native Windows/macOS/Linux packages
   ↓
package/content/install/remove checks
   ↓
release/update artifacts + manifests + capability receipt
   ↓
private/accepted candidate evidence
   ↓
exact tagged draft revalidated
   ↓
explicit publication
```

Important ideas:

- native packages are built on the platform they target;
- packages carry their own minimal Java runtime;
- update artifacts use the project updater signature path;
- release checks care about the exact bytes, tag, source revision, and artifact identity;
- install, upgrade, rollback, and removal behavior are exercised separately from “the build command
  exited zero”;
- publication reuses and revalidates the reviewed tagged draft instead of rebuilding a surprise set
  of bytes at the final moment.

The package also has a machine-readable capability receipt describing the native commands, writes,
child processes, links, and network endpoints available to that exact package.

This is one of the places where extra ceremony is justified: a release mistake reaches somebody
else's machine.

## Why does the repository keep failed experiments?

Because failed experiments answer questions future contributors would otherwise ask again.

Examples include optimizations that had great local counters and worse end-to-end results,
measurement methods that turned out to be wrong, visually broken prepared-texture paths, and AppCDS
work that never established a safe enough shipped win.

The useful split is:

- dead implementation code can be deleted;
- the evidence explaining why an approach lost can remain.

That gives future work the lesson without making the product carry the abandoned experiment forever.

## Why are there so many exact checks?

Because this project sits next to an old game, obfuscated Java, third-party mods, mutable files, and
user installations that Preflight does not own.

An optimization that is 99% right can produce a game failure far away from the actual cause.

So Preflight often prefers:

```text
I recognize this exact situation → use the shortcut
I do not recognize it             → use the original path
```

That can look conservative in code. It is what makes aggressive performance work tolerable in a
large mod ecosystem.

## What should I edit if I want to change something?

| Goal | Start here |
| --- | --- |
| Change shared preparation/discovery/data logic | `preflight-core/` |
| Change runtime bytecode behavior | `preflight-agent/` plus the exact adapter/target code |
| Change a CLI command or launch/profile/settings orchestration | `preflight-cli/` |
| Add a synthetic performance experiment | `preflight-synthetic-startup/` |
| Change desktop screens/interactions | `preflight-desktop/src/` and [UI design guide](ui-design.md) |
| Change native desktop authority | `preflight-desktop/src-tauri/` |
| Change package/release verification | `preflight-desktop/scripts/`, `.github/workflows/`, release docs |
| Change benchmark/profiling/operator tooling | `scripts/` |
| Change repeated public benchmark facts | `project-facts.json`, then run `scripts/sync_project_facts.py --write` |
| Understand why an optimization exists | [Engineering overview](engineering-overview.md) and [Optimization history](optimization-history.md) |
| Understand an exact safety/product guarantee | [Product contract](product-contract.md) |

## The one-sentence version of the whole repository

**Preflight measures repeated work, moves reusable answers to earlier or cheaper boundaries, proves
when those answers are still valid, launches Starsector through a session-local Java agent that can
use them, and keeps the original game behavior available whenever that proof stops holding.**

Everything around that core idea, including the desktop UI, storage planner, profile manager, linter,
diagnostics, tests, packaging, update system, and release process, exists to make that performance
work usable on real installations instead of only on the developer's machine.
