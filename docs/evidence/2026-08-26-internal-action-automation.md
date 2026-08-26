# Internal action automation breakthrough

Date: 2026-08-26

Status: live Starsector 0.98a-RC8 proof completed against a protected disposable campaign

## Finding

The macOS desktop-smoke driver reported successful window activation and a relative Continue click,
but the live LWJGL title screen did not receive that input. Repeating coordinate, focus, PID-targeted,
HID, keyboard, cursor-movement, and window-activation variants did not make the action reliable. One
display-name automation attempt also activated the dormant application wrapper and started a second
launcher, confirming that display identity is not a safe game-process identity.

The working boundary is inside the already running JVM. The reviewed Starsector 0.98a-RC8 title-menu
class maps its Continue control to a menu action and forwards that action to the title callback's
`menuItemSelected` method. A temporary attach agent retransformed that exact loaded class, scheduled a
one-shot call on its normal `advanceImpl(float)` thread, retrieved the existing callback and Continue
action, and invoked the same callback used by the game's own button.

This was not a simulated mouse click and did not start a launcher or second game. The one-shot agent
reported `fired`; the existing window immediately displayed `Loading...`; the game log reached campaign
load stage 39 of 39 and started campaign music; and Preflight's PID-bound runtime state advanced to
`campaign-ready`. The game remained running afterward.

## Why this matters

Preflight can make developer gameplay routes deterministic at a narrower and more trustworthy boundary
than host desktop input:

- actions are addressed by reviewed game semantics rather than pixels, window focus, Dock state, or an
  accessibility label;
- the call runs on the game's own main-loop thread rather than racing an external event queue;
- one exact PID and process-start identity remains authoritative throughout the run;
- existing semantic state telemetry proves the action's effect instead of treating a successful driver
  return as successful game input;
- unknown game bytes, fields, methods, or action types can fail closed without sending fallback input.

The same pattern may support a deliberately small developer action catalog: Continue, reviewed campaign
movement controls, pause/resume, opening a test surface, and orderly return or quit. It should not become
an arbitrary reflection console or a public macro engine.

## Product boundary

This finding does not by itself make arbitrary save mutation safe. Preflight can already manage campaign
directories outside the game with snapshot, copy, hash, restore, and sibling-save containment. That is a
filesystem transaction and remains useful even if the game cannot launch. An internal action can later
request the game's own save or load path for a disposable copy, but it must not edit a live serialized
object graph, silently overwrite a player's selected campaign, or bypass the existing save-boundary
attestation.

A production implementation should therefore be development-only at first and require all of these:

1. exact archive and class-byte identity for the supported Starsector release;
2. an explicit `--desktop-smoke` or narrower test-action capability;
3. a closed semantic action list with no arbitrary class, method, field, key, or argument input;
4. one-shot or bounded-duration actions executed on a reviewed game-loop boundary;
5. a PID/start-bound request and an atomic action receipt in the run directory;
6. before-state, after-state, timeout, and duplicate-request rejection;
7. disposable-save selection and complete before/after hashing for any campaign route;
8. no coordinate-click fallback when the internal target is unavailable.

## Follow-up

Replace `main-menu.continue` in the development smoke path with an exact-pinned internal action and keep
the native drivers only for bounded screen capture and any action that has no reviewed internal seam.
Then establish movement and pause as separate exact-pinned actions, rerun the same disposable campaign
route under measurement-only and optimized conditions, and retain the sealed comparison. The failed
macOS click must not continue to report success merely because System Events accepted the command.
