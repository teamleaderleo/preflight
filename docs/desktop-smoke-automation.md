# Desktop smoke automation contract

The scenario describes *what* a smoke test does without choosing the macOS, Windows, or Linux UI
driver that does it. Validate and normalize a scenario with:

```bash
java -jar preflight.jar desktop scenario validate scripts/scenarios/campaign-roam.json
```

`desktop` remains a host/development bridge rather than a public end-user command. The checked-in
campaign scenario requires process control, semantic state observation, window control, screen
capture, and evidence reads. It does not require audio capture; an audio-transition scenario can add
`audio-window` later and will then declare that capability automatically.

## Semantic boundary

Scenarios use stable names such as `main-menu-ready`, `campaign-ready`, and
`main-menu.continue`. They never contain a PID, window title, accessibility index, screen
coordinate, OCR phrase, or driver command. A driver resolves those details afresh for the process it
launched. Pixel templates and accessibility labels are driver assets, not scenario identity.

Wait states must be backed by Preflight/game telemetry or a reviewed log marker when one exists.
Screenshots are visual evidence, not the sole proof that the game reached a state. Every click or
key action is followed by a fresh observation before the next action. The runner owns exactly one
game process and refuses an already-running or second instance.

Every injected game JVM atomically publishes `runtime-process.json` in its run directory before
normal game initialization. The versioned record contains the JVM's PID, parent PID, available
process start instant, observation instant, and `running|stopped` state. A driver attaches only by
that PID after confirming the live process start instant matches the record. It never resolves an
application by display name, and a PID without a matching start instant isn't attachable. Orderly
shutdown changes the state to `stopped`; a crash can leave `running`, so liveness still comes from
the operating system.
Drivers use the same strict check immediately before attachment or input:

```bash
java -jar preflight.jar desktop process validate /absolute/run/runtime-process.json
```

The result exposes `alive`, `startMatches`, and `attachable`. Unknown fields, symlinks, oversized
records, malformed timestamps, contradictory lifecycle state, dead processes, and PID reuse are
rejected or reported without activating a window.

The first scenario deliberately covers only the repeatable core:

1. launch direct with the selected exact profile and storage policy;
2. wait for the main-menu marker;
3. activate Continue;
4. wait for campaign frame telemetry;
5. hold one movement key briefly;
6. capture screenshot, log tail, adapter health, and frame report;
7. request an orderly quit, with the existing process-tree shutdown as the bounded fallback.

Simulation/refit/combat targets are reserved in the validator, but should be added to checked-in
scenarios only after their state markers and recovery path are equally deterministic.

## Evidence result

Every platform driver must emit one `starsector-preflight-smoke-evidence-v1` document with this
shape. Additive fields are allowed; existing fields do not change meaning.

```json
{
  "format": "starsector-preflight-smoke-evidence-v1",
  "scenario": "campaign-roam",
  "status": "passed",
  "startedAt": "2026-08-06T01:00:00Z",
  "completedAt": "2026-08-06T01:03:00Z",
  "driver": {
    "id": "peekaboo",
    "version": "3.9.7",
    "platform": "mac",
    "capabilities": ["process-control", "semantic-state", "window-control", "screen-capture", "evidence-read"]
  },
  "runDirectory": "/absolute/path/to/run",
  "steps": [
    {
      "id": "menu",
      "status": "passed",
      "startedAt": "2026-08-06T01:00:15Z",
      "completedAt": "2026-08-06T01:00:31Z",
      "detail": "main-menu marker observed",
      "artifacts": []
    }
  ],
  "artifacts": [
    {
      "kind": "screenshot",
      "path": "/absolute/path/to/campaign.png",
      "bytes": 1234,
      "sha256": "64-lowercase-hex-characters"
    }
  ],
  "diagnostics": []
}
```

The runner writes this document atomically inside its run directory. `failed` means an assertion or
action failed; `skipped` means a required driver capability or OS permission was unavailable. A
driver failure must not be reported as a game regression. Audio and visual comparisons record their
thresholds and reference identities in additive artifact fields rather than hiding them in driver
code.

## Current macOS status

The Codex-native accessibility bridge can synthesize input. The 2026-08-06 direct-launch probe
found a targeting ambiguity in display-name attachment. The live game window is
owned by Azul's generic `com.azul.zulu.java` process while Launch Services also registers the dormant
`Starsector.app` bundle under the display name `Starsector`. Resolving the display name selects and
launches the dormant bundle instead of attaching to the already-running direct JVM. That briefly
created a second instance during the probe, so display-name targeting is prohibited.

The runtime now publishes its own PID and start instant, removing the need to discover that window
through Launch Services. The gameplay pilot also watches continuously for a foreign Starsector JVM.
If one appears after the pilot starts, the pilot aborts and terminates only the game process IDs it
observed as descendants of its own wrapper. This contains the duplicate-instance failure without
making UI targeting safe by itself.

Peekaboo 3.9.7 separately reported Screen Recording granted, with Accessibility and event synthesis
denied during that probe. The checked-in driver is still absent, so macOS remains `skipped`, not
`failed`, until PID attachment, current permissions, and click/key execution pass an isolated
driver test. The scenario, process identity, and evidence contracts require no OS permission and
remain testable on every platform.
