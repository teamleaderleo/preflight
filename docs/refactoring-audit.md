# Refactoring audit

**Updated:** 2026-08-08

The current structure reflects the order in which performance probes became production features.
Several files now own too many unrelated lifecycles. Refactoring should make release behavior easier
to review without changing adapter bytecode, cache identities, UI copy or command contracts at the
same time.

## Current concentration points

| Area | Current size | What is mixed together | Direction |
| --- | ---: | --- | --- |
| Tauri `lib.rs` | 3,221 lines | process ownership, updates, reports, settings, profiles, removal, preparation and automation | Split by command family after introducing one shared operation coordinator |
| React `App.tsx` | 1,382 lines after two extractions | installation, cache, preparation, profiles, settings, removal and page rendering | Extract behavior into feature hooks before splitting page components |
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

The diagnostics/report and signed-update hooks are complete. Together they removed fifteen state
variables, five effects and seven actions from the root component while retaining the existing
browser and native transport tests. The next hooks should keep the same rule: state, bridge calls
and event subscriptions move together. Presentational page components follow once their behavior
has a narrow interface. Splitting JSX first would create large prop lists and make the later
redesign harder.

### 2. One native operation coordinator

The native host currently keeps game, preparation, report-upload and update state in one private
structure. That shared state is valuable: it prevents an update, deletion or second game launch
from racing an owned operation. Module extraction should begin by naming that boundary rather than
duplicating locks in separate modules.

After the coordinator has focused transition tests, move command families into `report.rs`,
`updates.rs`, `automation.rs` and `engine.rs`. Each module should receive the coordinator and an
`AppHandle`; none should create another global process tracker. Exit cleanup remains centralized.

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

The visual redesign should start after the first three desktop hooks exist. At that point the page
hierarchy can change without moving asynchronous behavior in the same patch. The redesign can then
focus on information order, typography, density and progressive disclosure while the feature hooks
remain stable.

## Verification rule

A structural refactor makes no performance claim. It must preserve public JSON formats, cache keys,
adapter plan identities, Tauri command names, UI actions and exact fallback behavior. Use the full
existing suite for its affected module, then `mvn verify` and the native package smoke before the
refactor joins a release candidate.
