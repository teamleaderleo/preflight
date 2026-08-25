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

The injected JVM publishes `runtime-state.json` from a complete same-directory staged file. The
replacement is atomic when the filesystem supports it and falls back to replacing the destination
with that completed staged file otherwise. Its PID and process start instant bind it to the same
lifetime as `runtime-process.json`; its state advances through `starting`, `main-menu-ready`,
`campaign-ready`, `combat-ready`, and `stopped`. The main-menu marker is the exact reviewed
resource-initialization return already used by startup measurement. Campaign and combat come from
exact reviewed game-loop entry points. Only transitions touch disk; an unchanged campaign or combat
frame performs one volatile comparison. Unknown class bytes leave the game untouched and the state
absent, so semantic automation becomes unavailable instead of guessing from pixels.

Every injected game JVM publishes `runtime-process.json` in its run directory from a complete
same-directory staged file before normal game initialization. The replacement is atomic when the
filesystem supports it and falls back to replacing the destination with the completed staged file
otherwise. The versioned record contains the JVM's PID, parent PID, available process start instant,
observation instant, and `running|stopped` state. A driver attaches only by that PID after confirming
the live process start instant matches the record. It never resolves an application by display name,
and a PID without a matching start instant isn't attachable. Orderly shutdown changes the state to
`stopped`; a crash can leave `running`, so liveness still comes from the operating system.
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

The runner publishes this document from a complete same-directory staged file inside its run
directory. Replacement is atomic when the filesystem supports it and falls back to replacing the
destination with the completed staged file otherwise. `failed` means an assertion or action failed;
`skipped` means a required driver capability or OS permission was unavailable. A driver failure must
not be reported as a game regression. Audio and visual comparisons record their thresholds and
reference identities in additive artifact fields rather than hiding them in driver code.

Drivers don't write the accepted evidence document directly. They write a bounded
`starsector-preflight-smoke-driver-result-v1` request containing the same driver, outcome, step, and
diagnostic fields; each step artifact contains only its `kind` and an absolute or run-relative
`path`. The engine seals it with:

```bash
java -jar preflight.jar desktop evidence collect \
  scripts/scenarios/campaign-roam.json \
  /absolute/run/driver-result.json \
  /absolute/run/smoke-evidence.json
```

Collection requires an exact scenario-order prefix, monotonic timestamps, a terminal outcome that
agrees with the last step, and every step for a pass. A driver lacking a required capability can
only report `skipped`. Each artifact must resolve to a regular file inside the real run directory;
the collector caps individual and total bytes, rejects duplicates and changing files, calculates
SHA-256 itself, and publishes the final document from a complete same-directory staged file. That
replacement is atomic when supported and falls back to replacing the destination with the completed
staged file otherwise. The output filename is fixed so a driver can't redirect the write onto
unrelated run evidence.

The driver-neutral runner now owns the state machine that produces this request. It probes
capabilities before attachment, reloads and validates `runtime-process.json` before every step,
waits on the process-bound semantic state itself, executes remaining actions in exact order, and
obtains a fresh observation after every window or input action. The first failure ends the run;
permission/capability loss becomes `skipped`; a successful
`quit` must leave the recorded JVM non-attachable. Driver calls run on a monotonic global deadline,
with tighter probe, attachment, observation, and semantic-wait bounds. An interrupted adapter is
required to stop input, and a subprocess-backed adapter must terminate its child. Mock-driver tests
cover pass, skip, and mid-scenario failure without opening the game.

## Current macOS status

The packaged desktop app owns a short-lived native automation bridge. It binds only an ephemeral
loopback port, generates a per-process 256-bit capability, and gives both values directly to its
bundled engine child. The protocol accepts a closed list of reviewed operations: permission probe,
exact-PID activation and observation, the relative Continue target, seven keys, release, quit, and
a window-bounded capture into the run directory. It accepts no AppleScript source, arbitrary
coordinate, arbitrary key, host, or output path. The bridge ends with the engine child and never
writes its capability into evidence.

The native host runs the reviewed System Events scripts through `/usr/bin/osascript`. Every window,
click, key, observation, and quit script resolves `application process whose unix id is <pid>` from
the injected JVM's runtime record. No script contains the game's application name or asks Launch
Services to open an application. The 2026-08-06 direct-launch probe showed why this matters: the
live game window belongs to Azul's generic `com.azul.zulu.java` process while Launch Services also
registers the dormant `Starsector.app` bundle. Display-name attachment launched that dormant bundle
and briefly created a second instance. A standalone CLI invocation retains the earlier direct-Java
driver for development, while the supported packaged path attributes the permission request to
Preflight.

The driver checks the live process start instant in Java before every action and again resolves the
same numeric PID inside System Events. Its current reviewed coordinate asset covers only
`main-menu.continue`; the point is relative to freshly queried game-window bounds. Unknown targets
and keys fail closed. A held key has a `finally` key-up path, child commands have hard timeouts and
bounded output, screenshots cover only fresh game-window bounds, and orderly Command-Q has a
bounded fallback that can terminate only the same PID/start-instant lifetime. The runner now calls
that shutdown path after every attached terminal outcome, including adapter failure and timeout.

`--desktop-smoke` is an internal launch switch. It enables frame-time instrumentation and a
one-second, smoke-only publisher for `runtime-frame-report.json` and
`runtime-adapter-health.json`; regular launches create neither the thread nor the files. The hidden
bridge can run the checked scenario against an already launched smoke run:

```bash
java -jar preflight.jar desktop smoke run \
  scripts/scenarios/campaign-roam.json \
  /absolute/run/runtime-process.json \
  /absolute/run
```

The bridge can also own both processes from one command:

```bash
java -jar preflight.jar desktop smoke launch \
  scripts/scenarios/campaign-roam.json \
  /absolute/new-empty-run-directory \
  --game /absolute/Starsector
```

It probes permissions before launching, refuses a nonempty evidence directory or another attachable
tracked runtime, and starts a packaged direct smoke path without a shell. The checked optimized
scenario uses `run --fast --direct --desktop-smoke`. The paired benchmark's measurement-only
scenario uses `run --optimization-preset off --direct --desktop-smoke`: the exact semantic and
runtime markers remain, while the optimization adapter and prepared caches stay off. It waits for the
process record, runs the scenario, and waits for bounded postprocessing. Its
`finally` path rereads the identity and can terminate only the same PID/start-instant lifetime.
The launch result and bounded launcher output remain in the run directory even when startup fails.
`passed`, `skipped`, and `failed` map to exit codes `0`, `3`, and `1`, respectively. A cooperative
stop creates a run-owned `cancel.requested` marker. The launch owner watches that marker, closes only
the recorded PID/start-instant lifetime, seals a `cancelled` receipt, and returns a non-success exit
code so scripts can't mistake a partial run for a pass.

The desktop app's benchmark bundles matching measurement-only and optimized `startup` scenarios.
Each scenario waits only for Preflight's PID-bound `main-menu-ready` marker. It doesn't probe a
desktop driver, request Accessibility or screen-capture permission, send input, load a save, or take
a screenshot. One Java coordinator validates the pair before either game starts, runs the normal
launch first, closes that exact PID/start-instant lifetime, repeats with optimizations, compares the
sealed installation/profile/launcher/runtime/settings identity, and writes
`benchmark-result.json`. The Tauri host treats that coordinator as the active game owner and reports
the evidence directory when the pair ends. A running benchmark has an explicit safe-stop action. If
the app closes during a benchmark, it requests the same cooperative stop and waits for both the
launcher and exact game process to finish cleanup. Closing Preflight during ordinary play still
leaves the game running. App exit also cancels an owned cache-preparation child before leaving.

The product comparison contains process-to-main-menu time, its delta and percentage change, exact
run identities, and post-run prepared-data disk usage. The separate `campaign-roam` scenarios remain
available for development measurements of campaign readiness, a short movement sample, FPS,
frame-time tails, runtime cache health, a screenshot, and a bounded log tail. They do not save,
reload, or prove save compatibility. Those interactive scenarios use a platform driver and may
require the operating system's automation permissions; they aren't part of the benchmark users run.
Save/reload remains a human-operated check with a disposable save copy in
`scripts/run-gameplay-pilot.sh`. Its create-once attestation binds the human result to the exact
before/after save-boundary report, tested engine, run and exact mod-profile reports, adapter and
adapter-health evidence, source state, probe configuration, and process outcome instead of leaving
a transferable yes/no note beside the run. When the adapter is enabled, a complete receipt
additionally requires recorded frames in campaign warm-up, settled campaign, and combat; the
operator's reload confirmation cannot stand in for route evidence.

The macOS command probes current Accessibility permission before attachment. Screen Recording is
proved by the first bounded capture; a denial becomes `skipped`. Preflight's Info.plist explains the
System Events use, and the native package verifier requires that exact disclosure. The generated
scripts, authorization protocol, PID-only boundary, coordinate math, key release, bounded
screenshot, live evidence, and failure cleanup have isolated tests that don't open the game. One
live isolated action test is still required before calling the macOS driver production-ready.

Windows has an exact-PID `MainWindowHandle` adapter backed by PowerShell and User32. Linux has an
exact-PID X11 adapter backed by `xdotool` and ImageMagick `import`; Wayland and missing helper tools
produce an explicit unavailable result. Both adapters compile and have offline boundary tests, but
neither is labelled live-validated until a beta run happens on that platform.

A development-driver probe remains available as `desktop smoke probe`. The product benchmark
readiness check instead validates both packaged `startup` scenarios and requires their capabilities
to be exactly `process-control` and `semantic-state`. The native install exercise starts the actual
packaged host and rejects any benchmark probe that names an input driver. That exercise also moves
the app before probing; the native host resolves its engine from a canonical path confined to the
moved bundle's own `Contents/Resources` tree. Granting permission to a different terminal, editor, application bundle,
or Java binary isn't treated as sufficient. macOS tracks protected access through code identity, so
an unsigned or differently signed development build may still need permission granted again after
replacement.

Every native install exercise also validates the packaged `campaign-roam` scenario and seals an
intentional no-game driver result through `desktop evidence collect`. That evidence must say
`skipped`, contain no fabricated steps or artifacts, and match the published document. This proves
the packaged parser and sealer while leaving live input, visuals, audio, and gameplay claims for the
licensed-installation gate.
