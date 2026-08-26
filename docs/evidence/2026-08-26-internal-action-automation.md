# Internal action automation breakthrough

Date: 2026-08-26

Status: live Starsector 0.98a-RC8 title, campaign input, encounter, and save proofs completed against
a protected campaign copy

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

The same live run subsequently proved pause and resume through the campaign engine, Escape and labeled
dialog shortcuts through the game's own input-event list, and `CampaignState.cmdSave()` through a
one-shot render-thread action while the pause menu was open. The save returned success, both campaign
files received the new timestamp, and the game log showed its normal in-progress, backup, and promotion
sequence. The maintained [control compendium](../internal-game-control.md) records those boundaries.

## Hostile encounter failure

The protected-copy route later reached a Fang Society Smuggler encounter. A one-shot `2` key-down event
selected the visible `Move in to engage` option through the normal UI button path. The game then stopped
with this fatal error before deployment:

```text
Cannot invoke "java.util.List.iterator()" because the return value of
"com.fs.starfarer.api.campaign.BattleAPI.getSideFor(CampaignFleetAPI)" is null
```

The stack begins in `TacticalModule.pickEncounterOption`, passes through
`ModularFleetAI.pickEncounterOption`, `FleetInteractionDialogPluginImpl.fleetWantsToDisengage`, and the
normal option/button handlers, then returns to `CampaignState.processInput`. This establishes that the
semantic choice reached the game. A read-only postmortem of the still-alive JVM established the cause:

- the sector player fleet was temporarily empty while the dialog's combined player fleet held its one
  ship, which is normal during encounter composition;
- both battle side lists were empty, all `getSideFor(...)` calls returned null, and `joinedBattle` was
  still false;
- `Battle.advance(float)` clears the transient combined references and then removes empty source fleets;
- the bounded movement experiment had forced `CampaignEngine.setPaused(false)` and later reported a
  normal timeout completion even though the encounter dialog had already opened.

The raw engine resume therefore let the battle advance during the transient combined-fleet phase and
destroyed the membership state that the encounter UI expected. This was a controller-induced invariant
violation, not an unsupported one-ship fleet and not evidence of a Preflight save/cache defect. The base
game's unchecked null made the invalid state fatal, but normal UI play keeps campaign time stopped there.
The unmatched key-up was not causal: the membership had already been destroyed before choice 2 was
selected. Production dialog actions should still inject an atomic down/up pair or invoke an exact
reviewed semantic callback and verify the resulting dialog state.

The failure was captured at `2026-08-26T19:45:29+0800`. Its ignored run artifact is
`benchmark-results/continue-click-proof-20260826-01/failures/fatal-battle-null-20260826-1945.png`, SHA-256
`c77bd15b686a4ee9e931491de0b9b3ae4ca66d5411574baf9092564ec8c27d34`. The original Preflight-targeted
game PID was 18037, started at 19:01:51. A second launcher PID 30861, started at 19:35:19, was also alive
when the failure was captured. The controller remained attached to PID 18037, but future routes should
report and reject ambiguous extra game/launcher processes before accepting actions.

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
9. an atomic keyboard transition or exact semantic callback for dialog choices;
10. rejection or explicit operator acknowledgement when another game/launcher PID is alive.
11. no raw engine resume: require the campaign surface, no current interaction dialog, no active battle
    composition, and use the game's reviewed pause input/state transition;
12. a per-frame guard that stops movement before any dialog or battle script can advance.

## Follow-up

`main-menu.continue` is now an exact-pinned internal action with PID/start-bound create-once
request/receipt evidence and a same-process `campaign-ready` requirement. Native drivers remain for
bounded screen capture and actions that do not yet have a reviewed internal seam.
Then establish movement and pause as separate exact-pinned actions, rerun the same disposable campaign
route under measurement-only and optimized conditions, and retain the sealed comparison. The failed
macOS click must not continue to report success merely because System Events accepted the command.
