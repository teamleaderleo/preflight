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

Peekaboo 3.9.7 is installed and suitable for a first development driver, but Screen Recording,
Accessibility, and event-synthesis permissions are not currently granted. Granting those is a
security-sensitive OS setting, so the runner is not wired or exercised until the operator approves
that permission change at the point of use. The scenario and evidence contracts require no such
permission and are testable on every platform now.
