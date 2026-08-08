# Refactoring audit

**Updated:** 2026-08-09

The current structure reflects the order in which performance probes became production features.
Several files now own too many unrelated lifecycles. Refactoring should make release behavior easier
to review without changing adapter bytecode, cache identities, UI copy or command contracts at the
same time.

## Current concentration points

| Area | Current size | What is mixed together | Direction |
| --- | ---: | --- | --- |
| Tauri `lib.rs` | 2,363 lines after the native lifecycle extractions | report protocol helpers, settings, profiles, removal and preparation | Continue splitting command families through the shared operation coordinator |
| React `App.tsx` | 285 lines after the workflow and page extractions | installation selection, launch orchestration and page composition | Keep it as the application composition boundary |
| `AdapterTargetRegistry` | 2,028 lines | reviewed class fingerprints and method requirements | Keep explicit; size alone isn't a defect |
| `RunCommand` | 1,310 lines | launch orchestration, cache-context selection, metadata and reporting | Extract profile/context selection behind one typed result |
| `AdapterTransformationRegistry` | 1,024 lines | reviewed transformation registrations | Keep explicit until a generated form proves byte-for-byte equivalent output |
| prepared-texture and runtime adapter classes | roughly 500–1,000 lines each | hot-path state, safety gates and fallback behavior | Refactor only alongside focused equivalence and allocation evidence |

The registry files are large because every supported target is visible in source. Turning them into
reflection, annotation scanning or a loose external format would reduce line count while weakening
reviewability. They should stay boring and explicit.

## Order of work

### 1. Desktop behavior before page components

Move each stateful workflow out of `App.tsx` in this order:

1. diagnostics and voluntary reports;
2. signed updates;
3. preparation and storage planning;
4. named profiles;
5. desktop automation;
6. cache cleanup and removal.

The diagnostics/report, signed-update, preparation/storage, named-profile, desktop-automation,
launcher-settings, cache-cleanup and removal hooks are complete. Page components are separate, and
`App.tsx` is now a 285-line composition boundary. State, bridge calls and event subscriptions moved
together, preserving the browser and native transport tests. Further React extraction needs a
concrete ownership problem rather than a line-count target.

### 2. One native operation coordinator

The native host now keeps game, automated-smoke, preparation, report-upload and update-installation
state in `OperationCoordinator`. That shared state prevents an update, deletion or second game
launch from racing an owned operation. The coordinator and its update guard live in
`operations.rs`; desktop automation, signed updates and report-upload ownership now live in
`automation.rs`, `updates.rs` and `reports.rs`. All three receive that same coordinator rather than
creating another lock. The hostile-input HTTP protocol helpers remain beside their focused tests in
`lib.rs` until they can move without obscuring that coverage.

The coordinator's update exclusion, guard release and shutdown cleanup transitions have focused
tests. Move the remaining engine command family into `engine.rs`. It receives the coordinator and
an `AppHandle` without creating another global process tracker. Exit cleanup remains centralized.

### 3. Launch context selection

`RunCommand` spends roughly its final third selecting the texture, audio, Janino and SpecStore cache
contexts. Those selectors already produce typed records and use one bounded identity context. Move
them into a `LaunchCacheContexts` service with serial and parallel implementations behind the same
method. Existing context-identity, kill-switch and command integration tests become the extraction
gate.

### 4. Leave hot adapters alone during release polish

Prepared-pixel ownership, audio wrappers, campaign indexes and exact bytecode plans have failure
paths that aren't obvious from file size. A cleanup that changes control flow can invalidate the
evidence behind them. Work there needs a concrete allocation, correctness or compatibility reason,
then the same synthetic and real-install gates used for an optimization.

## Design dependency

The desktop hooks, page split and responsive visual foundation are complete. Further interface work
can focus on state coverage, accessibility, information order, typography and density while the
feature hooks remain stable.

## Verification rule

A structural refactor makes no performance claim. It must preserve public JSON formats, cache keys,
adapter plan identities, Tauri command names, UI actions and exact fallback behavior. Use the full
existing suite for its affected module, then `mvn verify` and the native package smoke before the
refactor joins a release candidate.
