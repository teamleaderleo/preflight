# Desktop redesign brief

**Status:** implementation in progress; launch console and primary navigation landed

**Updated:** 2026-08-08

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

- **Home** — launch, common game controls, active profile, cache footprint and installation;
- **Preflight** — optimization policy, preparation resources, texture storage and cache cleanup;
- **Run reports** — bounded diagnostic export, explicit sending and the automated compatibility
  test;
- **Profiles** — save, switch and inspect exact mod profiles;
- **Settings** — application updates and removal.

Resolution, fullscreen, sound, battle size and game RAM sit beside the launch action. Antialiasing
and UI scaling remain one direct click away in the complete game-settings view. Preflight follows
the selected launcher to the file that actually owns `-Xmx`, refuses ambiguous layouts and keeps an
exact backup before changing it.

The home console and full game-settings page share one typed control model, including the effective
launcher-owned heap setting. The desktop shell now owns navigation, viewport reset and responsive
chrome separately from workflow state; narrow layouts switch to accessible icon navigation without
wrapping, while short desktop windows regain bounded vertical scrolling instead of clipping content.
Preparation, profiles, run reports, updates, and removal now render through independent workflow
components while discovery, process state, and destructive actions remain coordinated by the app root.
Installation-scoped reads are request-fenced so an older result cannot replace a newer selection.
Native event streams share one lifecycle helper that also unregisters subscriptions resolved after a
component has already closed.

## Visual direction

Use a clean flight-instrument feel without copying Starsector's assets or interface. A restrained
grid, one cyan accent and small orbital/trajectory details are enough. The app should feel like an
independent utility made for the game, not an unofficial replacement launcher pretending to be the
game.

Orbitron stays limited to the Preflight wordmark, major numeric readouts and occasional short
labels. Inter handles every control, paragraph, status and table. Body copy starts at 16 px, small
metadata at 13–14 px, and controls keep a 44 px minimum target. Dense evidence uses aligned rows
instead of smaller type.

The working palette uses near-black, desaturated navy surfaces with muted steel-blue borders. Cyan
marks focus and movement; amber is reserved for warnings and waypoint details. The paper-plane mark
sits inside a clipped technical frame with enough texture to read as equipment instead of a glossy
consumer-app badge. Light mode keeps the same drafting-grid hierarchy on blue-grey paper.

## Copy and disclosure

The first layer states the result and the action. Safety explanations move into disclosures placed
beside the feature they qualify.

Examples:

- `Ready · Recommended · 15 MB` beside **Launch**;
- `Needs 3.1 GB · 18.4 GB available` beside **Prepare and launch**;
- `Using vanilla fallback for 2 plans` with **Details**;
- `Report ready · 3.6 MB` with **Review and send**.

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
- an active-profile selector, measured Preflight footprint and installation location.

Update notices and recoverable warnings appear between the status and main action. Support,
cleanup and removal never appear as equal-weight cards on this screen.

## Interaction rules

- Keep Recommended and Balanced selected by default.
- Show predicted disk growth before preparation and actual storage afterward.
- Preserve preview-before-apply for profile changes, cleanup, report sending and removal.
- Keep Conservative and Off visible in the Preflight workspace and in targeted error states.
- Never hide a declined optimization or vanilla fallback; summarize it first and expose exact
  adapter evidence on demand.
- Don't add automatic telemetry, automatic report sending or surprise updates.
- Don't open a workflow with a card that restates the page title.
- Keep the desktop shell fixed to its window. Advanced workspaces may scroll inside their bounded
  content region; Home shouldn't scroll at the standard desktop size.
- Keep labels readable before trying to fit another card above the fold.

## Implementation sequence

1. Extract update and preparation behavior from `App.tsx`.
2. Build the new Start page against those stable hooks using fixture snapshots only.
3. Move profile and settings views without changing their bridge contracts.
4. Run keyboard, contrast, scaling and minimum-size checks.
5. Exercise the packaged first-run, prepared launch, low-disk refusal, failed preparation, update,
   report and removal states before replacing the current UI.

The redesign doesn't change the engine, cache formats or adapter plans. It can ship independently
once both versions pass the same behavior tests.

The first extraction, visual-foundation and information-architecture passes are complete. Home is a
compact launch console rather than an illustrated landing page. Primary navigation exposes
Preflight and Run reports as real workspaces, while Profiles remains directly reachable and Settings
contains only application maintenance. The whole desktop window stays fixed; longer work happens
inside the active workspace.
