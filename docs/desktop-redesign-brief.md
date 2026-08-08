# Desktop redesign brief

**Status:** implementation started

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

Reduce the five current destinations to three:

- **Start** — installation, current profile, preparation state, storage choice and launch;
- **Profiles** — save, switch and inspect exact mod profiles;
- **Settings** — game preferences, updates, storage cleanup, support reports, removal and advanced
  troubleshooting.

Preparation becomes a state of Start rather than a separate destination. Launch settings sit in a
compact disclosure beside the launch action. Activity details can expand in place while work is
running.

## Visual direction

Use a clean flight-instrument feel without copying Starsector's assets or interface. A restrained
grid, one cyan accent and small orbital/trajectory details are enough. The app should feel like an
independent utility made for the game, not an unofficial replacement launcher pretending to be the
game.

Orbitron stays limited to the Preflight wordmark, major numeric readouts and occasional short
labels. Inter handles every control, paragraph, status and table. Body copy starts at 16 px, small
metadata at 13–14 px, and controls keep a 44 px minimum target. Dense evidence uses aligned rows
instead of smaller type.

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

The Start screen should contain:

- one status line;
- the current installation and mod-profile name;
- one dominant action;
- a compact preset/storage summary;
- progress or the last completed result;
- one secondary route to details.

Update notices and recoverable warnings appear between the status and main action. Support,
cleanup and removal never appear as equal-weight cards on this screen.

## Interaction rules

- Keep Recommended and Balanced selected by default.
- Show predicted disk growth before preparation and actual storage afterward.
- Preserve preview-before-apply for profile changes, cleanup, report sending and removal.
- Keep Conservative and Off available from an Advanced disclosure and from targeted error states.
- Never hide a declined optimization or vanilla fallback; summarize it first and expose exact
  adapter evidence on demand.
- Don't add automatic telemetry, automatic report sending or surprise updates.

## Implementation sequence

1. Extract update and preparation behavior from `App.tsx`.
2. Build the new Start page against those stable hooks using fixture snapshots only.
3. Move profile and settings views without changing their bridge contracts.
4. Run keyboard, contrast, scaling and minimum-size checks.
5. Exercise the packaged first-run, prepared launch, low-disk refusal, failed preparation, update,
   report and removal states before replacing the current UI.

The redesign doesn't change the engine, cache formats or adapter plans. It can ship independently
once both versions pass the same behavior tests.

The first extraction pass is complete. Primary navigation now contains Start, Profiles and
Settings. Game settings and storage remain full-size drilldowns from Start while its final compact
layout is built against the existing behavior fixtures.
