# Desktop redesign brief

**Status:** implementation in progress; primary workflows and responsive shell landed

**Updated:** 2026-08-10

Preflight's desktop app has the right capabilities and too much explanation in the primary path.
The redesign should make the ordinary job obvious: find the game, prepare what is useful, launch it
and stay out of the way. Detailed safety and storage evidence remains available when someone asks
for it.

## Product shape

Most people need three states:

1. **Set up** — installation found, storage requirement known, one action prepares and launches.
2. **Ready** — the current mod profile is prepared and the main action launches immediately.
3. **Needs attention** — a specific error, update, disk-space refusal or compatibility fallback has
   one recommended next action.

Named profiles, cleanup, detailed launch settings, diagnostics and automation are supporting tools.
They shouldn't compete with the main action on the first screen.

## Information architecture

The sidebar keeps each power-user workspace visible instead of nesting it inside a general menu:

- **Home** — launch, common game controls, current mod setup, cache footprint and installation;
- **Preflight** — optimization policy, preparation resources, texture storage and cache cleanup;
- **Benchmark** — the checked game sequence, bounded support export, and explicit report sending;
- **Profiles** — save, switch and inspect exact mod profiles;
- **Settings** — application updates and removal.

Resolution, fullscreen, sound, antialiasing, UI size, battle size and game RAM sit beside the launch
action. The complete game-settings view explains the same controls in more detail. Preflight follows
the selected launcher to the file that actually owns `-Xmx`, refuses ambiguous layouts and keeps an
exact backup before changing it.

The home console and full game-settings page share one typed control model, including the effective
launcher-owned heap setting. The desktop shell now owns navigation, viewport reset and responsive
chrome separately from workflow state; narrow layouts switch to accessible icon navigation without
wrapping before labels collide, while short desktop windows regain bounded vertical scrolling instead
of clipping content. Stacked launch layouts keep the primary action full-width down to the supported
320 px minimum. The previous landing page's unused hero and installation-card styles have been removed
so responsive and dark-mode changes apply only to interfaces that can still render.
Vite ignores generated Tauri engine and distribution output, allowing HMR to remain open while the
engine is rebuilt or the full verification gate runs.
Preparation, profiles, benchmark/support, updates, and removal now render through independent workflow
components while discovery, process state, and destructive actions remain coordinated by the app root.
Installation-scoped reads are request-fenced so an older result cannot replace a newer selection.
The home and complete game-settings views share one installation-scoped settings request, preserve
edits made during a save and don't reread the same files when moving between those views. Cache
cleanup previews are bound to the installation that produced them. Installation changes, profile
activation and preparation-policy changes stay disabled while preparation or the game is running.
Profile reviews, saves and activation responses are likewise bound to their installation, run
single-flight and preserve a newer name typed while an earlier save completes.
Rename and delete use the same preview-first contract. Confirmation is bound to the reviewed
profile fingerprint; delete keeps prepared data and writes a recoverable named-profile backup.
Removal review and application are single-flight, and the final destructive action inherits the
same global operation lock as its preview controls.
Native event streams share one lifecycle helper that also unregisters subscriptions resolved after a
component has already closed. If a native subscription fails, the affected workflow polls the
bounded operation coordinator until the window closes, so launch, preparation, automation, report
upload, and update controls don't guess that an operation ended. This snapshot describes the
current desktop process; the engine's durable cross-process lease remains the authority for every
new mutation.

The ordinary cold-profile path now names itself as first-launch setup, shows both predicted growth
and the conservative required-free bound, and keeps preparation progress and **Stop safely** on Home.
The plan shown there is valid only for the exact installation, profile fingerprint, storage policy
and worker count that produced it; changing any of those inputs removes the old plan before another
preparation can begin. The engine still recalculates the bound before writing. Discovery, explicit
installation selection and game launch have separate retry actions, and a failed state refresh can't
be overwritten by a later success notice. Game failures keep a persistent recovery card with a
bounded first useful line, the native detail behind a disclosure, and direct relaunch and support
actions. When an operation owns the global mutation lock, another workspace names that operation
and links back to its progress instead of leaving unrelated controls silently disabled.
The same screen now distinguishes cold prepared data from damaged metadata. Health inspection
opens the exact current profile's index, manifest, pack index, and optional audio manifest. Repair
is bound to the reviewed profile fingerprint, recomputed under the durable lease, and removes only
that profile's broken metadata or pack. Shared content-addressed blobs remain reusable. Canonical
parent checks refuse symlink escapes and unexpected filesystem objects before deletion.

## Visual direction

Use a clean flight-instrument feel without copying Starsector's assets or interface. A restrained
grid, one desaturated blue accent and small orbital/trajectory details are enough. The app should
feel like an independent utility made for the game, not an unofficial replacement launcher
pretending to be the game.

Orbitron stays limited to the Preflight wordmark, major numeric readouts and occasional short
labels. B612 handles controls, paragraphs, status and tables, with B612 Mono reserved for compact
operational labels. Body copy starts at 16 px, small metadata at 13–14 px, and controls keep a 44 px
minimum target. Dense evidence uses aligned rows
instead of smaller type.

The working palette uses near-black, desaturated navy surfaces with muted steel-blue borders. Blue
marks focus and movement; amber is reserved for warnings and waypoint details. The app mark is a
dark navy spacecraft sketch on warm drafting paper. Its bold outer structure survives small
launcher and Dock sizes while the larger versions retain construction lines, a clipped technical
frame and one amber waypoint. Both app themes use the same light-paper mark.

![Named profile management in the light drafting-paper theme](images/desktop-profiles-light.png)

## Copy and disclosure

The first layer states the result and the action. Safety explanations move into disclosures placed
beside the feature they qualify.

Examples:

- **Launch Starsector** when the current profile is ready;
- `Needs up to 3.1 GB free · 18.4 GB available` beside **Prepare and launch**;
- `Using the built-in fallback for 2 plans` with **Details**;
- `Support ZIP ready · 3.6 MB` with **Review send**.

Avoid repeating that nothing has happened yet, that a button is safe, or that a section contains
settings when the surrounding interface already establishes it. Confirmation screens still name
the exact files, bytes, external destination and destructive scope when that information changes a
decision.

## Core screen

The Home screen should contain:

- one status line;
- the current installation and mod-profile name;
- one dominant action;
- a compact preset/storage summary;
- progress or the last completed result;
- common game controls and one direct route to the remaining controls;
- a read-only current mod-setup summary, measured Preflight footprint and installation location.

Update notices and recoverable warnings appear between the status and main action. Support,
cleanup and removal never appear as equal-weight cards on this screen.

## Interaction rules

- Keep Recommended and Balanced selected by default.
- Show predicted disk growth before preparation and actual storage afterward.
- Preserve preview-before-apply for profile changes, cleanup, report sending and removal.
- Start profile switching on the Profiles screen, where the exact mod-list diff is visible; Home
  only identifies the current setup.
- Keep Conservative and Off visible in the Preflight workspace and in targeted error states.
- Never hide a declined optimization or vanilla fallback; summarize it first and expose exact
  adapter evidence on demand.
- Don't add ambient telemetry or surprise updates. Failed-run reports remain a separate,
  remembered, default-off choice using the same disclosed support ZIP.
- Don't open a workflow with a card that restates the page title.
- Keep the desktop shell fixed to its window. Advanced workspaces may scroll inside their bounded
  content region; Home shouldn't scroll at the standard desktop size.
- Keep labels readable before trying to fit another card above the fold.
- Use brief productive motion for navigation and disclosure. Decorative movement remains bounded,
  never carries meaning alone and follows the system reduced-motion preference.

## Implementation sequence

1. ~~Extract update and preparation behavior from `App.tsx`.~~
2. ~~Build the new Home page against stable hooks and fixture snapshots.~~
3. ~~Move profile and settings views without changing their bridge contracts.~~
4. ~~Run keyboard, contrast, scaling and minimum-size checks.~~
5. Exercise the packaged first-run, prepared launch, low-disk refusal, failed preparation, update,
   report and removal states before replacing the current UI.

The redesign doesn't change the engine, cache formats or adapter plans. It can ship independently
once both versions pass the same behavior tests.

The first extraction, visual-foundation and information-architecture passes are complete. Home is a
compact launch console rather than an illustrated landing page. Primary navigation exposes
Preflight and Benchmark as real workspaces, while Profiles remains directly reachable and Settings
contains only application maintenance. The whole desktop window stays fixed; longer work happens
inside the active workspace.
