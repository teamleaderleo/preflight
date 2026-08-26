# Internal game-control protocol

Status: live 0.98a-RC8 proof; deterministic title, campaign, simulation-deployment, and combat-state
controls implemented for developer smoke runs

Preflight's developer automation needs a small semantic control protocol inside the launched game
JVM. Native desktop control remains useful for bounded screenshots, but it is not a reliable input
boundary for this LWJGL game on macOS. The protocol is also the base layer for future agent-driven
gameplay: agents should choose reviewed actions, not receive an arbitrary reflection console.

## Live-proven actions

The 2026-08-26 protected-copy run proved each of these against one existing Preflight-launched JVM:

| Semantic action | Internal boundary | Proof |
| --- | --- | --- |
| Continue | title callback `menuItemSelected(Continue)` on `advanceImpl` | existing title changed to Loading; stage 39/39; `campaign-ready` |
| unpause (temporary proof) | `CampaignEngine.setPaused(false)` on campaign `advance` | receipt changed `true` to `false`; simulation resumed |
| pause (temporary proof) | `CampaignEngine.setPaused(true)` on campaign `advance` | receipt changed `false` to `true`; simulation stopped |
| dismiss/cancel | Escape key down/up in `CampaignState.processInput` | supply-consumption warning disappeared |
| choose option 4 | `4` key down/up in the same input list | hostile encounter opened its story-point confirmation |
| confirm | `G` key down/up in the same input list | special disengagement spent one story point and granted bonus XP |
| leave | Escape key down/up in the same input list | encounter result closed and campaign resumed |
| save current | `CampaignState.cmdSave()` on the still-running render boundary | returned `true`; normal staged save completed on disk |
| prepare simulation fleet | exact-gated Console Commands 4.0.9 `AddShip`/`Repair` calls plus computed minimum crew | 24 added ships settled deployable at maximum CR while campaign remained paused |
| deploy simulation | exact stock simulator dialog callbacks | 8 allied and 25 opposing ships deployed, then `combat-ready` |
| ensure combat unpaused | `CombatEngine.isPaused()`/`setPaused(false)` at the exact reviewed combat advance seam | receipt proved `afterPaused=false`; already-unpaused runs are left unchanged |
| combat zoom out | bounded player-equivalent wheel input in the exact game window | semantic viewport receipt proved `viewMult` 1.250 → 4.250 and visible width 1800 → 6120 |
| prepare symmetric stress combat | exact-gated public combat/fleet APIs on the combat advance boundary | replaces both deployed simulator sides in memory with the same reviewed 24-ship, 520-DP fast high-tech recipe and leaves combat paused |

Selecting encounter choice 2 also reached the normal `Move in to engage` button path, but the campaign
then failed before deployment because `BattleAPI.getSideFor(playerFleet)` returned null. The cause was
the temporary controller's raw engine resume: it let `Battle.advance()` run while encounter composition
had moved ships into transient combined fleets, so the battle removed both now-empty source sides. This
is a proved action delivery and a controller-induced invalid scenario outcome, not a proved combat-start
action. The [dated evidence](evidence/2026-08-26-internal-action-automation.md#hostile-encounter-failure)
records the bytecode/postmortem proof, screenshot identity, and extra-launcher process found during
capture.

The event implementation constructor takes `(eventClass, eventType, x, y, eventValue, char)`. An
initial proof accidentally placed Escape's LWJGL key code in `x`; the event entered the list but did
nothing. Rebuilding it with key code 1 in `eventValue` dismissed the modal immediately. This exact
failure belongs in regression coverage because a transport receipt is not proof that the game acted.

The save action also established an execution-boundary distinction. Campaign `advance` does not run
while the pause menu owns the screen, so a save request armed there never fired. Campaign `render`
continues and safely hosted the one-shot `cmdSave()` call. Each semantic action needs an explicit
reviewed game-thread boundary rather than one generic callback.

## Control compendium

These are protocol concepts, not public arbitrary keys or coordinates.

| Area | Closed actions | Observation needed before and after |
| --- | --- | --- |
| title | `continue` | interactive title, then loading/campaign state |
| time | `pause`, `resume`, `toggle-pause`, `normal-speed`, `double-speed` | engine paused and speed state |
| dialogs | `choice-1` through `choice-9`, `confirm`, `cancel`, `dismiss`, `leave` | dialog identity, visible option labels, resulting dialog/state |
| campaign | `move-to-world`, `stop`, `interact`, `toggle-free-look`, `zoom-in`, `zoom-out` | location identity, player position, destination, interaction target |
| map/UI | `open-map`, `open-intel`, `open-fleet`, `open-cargo`, `open-refit`, `return` | active tab/surface identity |
| abilities | `ability-0` through `ability-9` | bound ability id, availability, activation result |
| saves | `save-current-copy`, later `save-as-checkpoint`, `load-checkpoint` | exact campaign directory, stable before/after hashes, game state |
| lifecycle | `return-to-title`, `quit` | semantic state and exact PID/start lifetime |

`resume` must not be implemented as an unconditional `CampaignEngine.setPaused(false)`. Its preconditions
include the active campaign surface, no current interaction dialog, and no battle in a combined-fleet
composition phase. It should use the game's reviewed pause input/state transition, then keep a per-frame
guard ahead of campaign and battle advancement. A newly opened dialog terminates movement before another
simulation tick. The hostile-encounter failure proved that checking only `engine.isPaused()` after the
fact is insufficient.

Starsector's published default campaign controls agree with the inspected game behavior: Space
pauses or resumes, Shift changes time speed, Escape opens or closes the campaign menu, left-click
selects a campaign destination, right-click toggles free look, and the number row activates the
ability bar. Map navigation has separate click/drag semantics. WASD is combat piloting, not campaign
travel. The installed runtime's control mapping, not a web table, remains authoritative for a real
request.

Keyboard actions should be resolved from semantic control ids wherever the game exposes them.
Literal LWJGL codes are acceptable only for exact-pinned vanilla dialog shortcuts whose labels were
observed in the same state. A key-down that changes screens can prevent a later frame from receiving
key-up, so both transitions must be added atomically to one input list or the reviewed semantic callback
must be invoked directly. Mouse events must use the game's logical coordinate space and its own
input objects. Host Retina pixels, menu-bar offsets, Dock position, and window focus must never leak
into a scenario.

## Request and receipt

The implemented transport uses one create-once request/receipt pair at a time in the run directory:

```text
runtime-action-request.json
runtime-action-receipt.json
```

The request carries a format version, monotonically increasing sequence, exact PID and process-start
instant, semantic action, bounded arguments, expected before-state, and deadline. The game-thread
runtime accepts each sequence once, rejects unknown fields/actions and stale states, and atomically
publishes a receipt with accepted time, execution boundary, before/after observations, terminal
status, and a bounded failure. After validating a receipt, the runner archives both files under their
six-digit sequence so the next closed action can run without leaving a polling file behind. The
runner must still wait for the expected semantic state or verify the requested pause state.
`executed` is never synonymous with `succeeded`.

Only `--desktop-smoke` enables current request polling and
the exact target plans. A normal player launch neither watches request files nor exposes game actions.

The current closed catalog contains `main-menu.continue`, `campaign.pause`, `campaign.unpause`,
the in-memory combat fixture prepare/verify pair, the stock simulator side/select/deploy/engage
actions, `combat.pause`, `combat.unpause`, the viewport capture/verification pair, and
`combat.begin-frame-window`, plus the simulation-only
`combat.prepare-symmetric-1000dp-fixture` stress action.
Campaign pause actions resolve the
installed `GENERAL_PAUSE` binding, synthesize an atomic
key-down/key-up pair, add it to Starsector's real `CampaignState.processInput` batch, and verify the
engine's result after normal input processing. They reject an unknown class shape, non-campaign state,
active dialog or menu, non-keyboard pause binding, stale PID/start identity, or expired deadline. The
transport does not expose arbitrary keys, reflection names, or coordinates.

Combat pause is state-setting rather than a Space toggle: it reads the exact combat engine first and
only calls the reviewed setter when the requested state differs. This matters because the stock
simulator may already be running after Engage; an unconditional Space press can pause an otherwise
valid sample. Combat zoom-out sends a bounded player-equivalent wheel gesture only after exact-PID
activation and window-bounds lookup. The game-side protocol captures the public viewport before the
gesture and then requires both `viewMult` and visible world width to increase by at least 5%. A sent
host event is not success: an unchanged camera fails the scenario before measurement. On the tested
macOS profile, positive CoreGraphics line-scroll values produced Starsector zoom-out; the opposite
sign left the viewport unchanged and was rejected. A pure game-batch experiment also failed because
the existing `CombatEngine.advance` control seam runs after `CombatState` consumes that frame's input;
it was discarded rather than weakening the verifier. The frame-window action resets a separate
steady-state distribution after command-map, speed, pause, and camera setup have settled; cumulative
session telemetry remains intact. Unknown engine or viewport shapes fail the action.

The symmetric stress action is deliberately separate from the historical campaign fixture. The old
fixture preserves earlier measurement identity; its stock DP-limited simulator selection can favor the
first slow capital ships in fleet order. The stress action runs only after stock simulator deployment,
validates every reviewed variant against installed settings, spawns both mirrored fleets before it
removes either original side, checks 24 ships and at least 500 DP per side, and leaves combat paused for
camera and autopilot setup. It uses no campaign save and exposes no arbitrary variant argument. The v1
recipe is four each of Odyssey, Aurora, Fury, Medusa, and Hyperion plus two each of Tempest and Scarab:
520 DP per side and 1,040 DP total. This is a repeatable performance workload, not a claim that the
recipe is an optimal fleet.

## Save and learning boundary

Agent-driven play should begin from a named checkpoint copy. Before launch, Preflight records stable
hashes of every campaign directory; during play it permits writes only to the selected copy; after
exit it verifies sibling campaigns and global boundaries. A reset is a fresh copy from the immutable
checkpoint, not an in-memory rollback.

This makes repeated strategy evaluation possible:

1. restore checkpoint;
2. launch exact game/profile/Preflight identities;
3. observe a structured state plus bounded screenshot;
4. choose one closed action;
5. execute and verify its effect;
6. score progress, survival, time, resources, and frame behavior;
7. retain the trace and reset.

The evaluator can compare routes and support self-improvement without allowing an agent to rewrite
its safety boundary or train on an untracked mutable save. Strategy state belongs outside campaign
serialization. A reusable Codex skill may eventually orchestrate this loop, but it should call the
repository protocol rather than contain obfuscated game members itself.

## Java and game-version matrix

Current Preflight artifacts target Java 17 classfiles and therefore run on supported Java 17 and
newer JVMs, including Java 21. The live game's classes are also version 61 (Java 17). The temporary
Java 21-compiled attach agent failed before its `agentmain` ran; recompiling to release 17 succeeded.

Java 8 cannot load a Java 17 agent. Supporting older Starsector releases that actually run on Java 8
requires a separate release-8 controller artifact with no newer library or language dependencies,
plus its own exact archive/class/method matrix. It does not mean injecting Java 8 bytecode into the
current Java-17-only game. Each matrix row must fail closed on an unknown JVM, game archive, class
hash, method shape, or loader identity.

See the dated [live breakthrough record](evidence/2026-08-26-internal-action-automation.md) for the
initial Continue proof and native-input failure boundary.
