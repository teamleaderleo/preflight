# Main-menu interactive v2 semantic correction

Date: 2026-09-02  
Status: semantic-contract correction; no new performance claim

This record amends the timing interpretation added to
`docs/evidence/2026-09-01-windows-vm-startup-tuning.md` by PR #1222 and the earlier records that used
the same runtime-state v1 field. The bytecode investigation remains useful and is preserved. Its
conclusion changes because the old event was the disappearance of the `Preloading...` label, while
the title/menu can already accept useful interaction before that label disappears.

## Root cause

Runtime-state v1 named its title timestamp `mainMenuInteractiveAt`, but the reviewed macOS and
Windows transforms published that event immediately after the unique UI `remove(...)` call that
removed the title's `Preloading...` label. The name and benchmark use therefore claimed a stronger
semantic than the instrumented bytecode seam supplied.

PR #1222 made the Windows mismatch exact. On the reviewed 0.98a-RC8 Windows core:

- `starfarer_obf.jar` is SHA-256
  `5dd222b9e266d2ac2d63b3dad4983eb05caaf5a247d7dfb82aaeba47ea774cc8`;
- the reviewed title class is `MainMenuInteractivePlan.WINDOWS_TARGET_CLASS`, SHA-256
  `7a034024de849f2829ad5e41dbb0e58f5979a6e7a81e55527f6839055db3d4c6`;
- its constructor creates `Preloading...` and calls `blink(3.0f, 10.0f)`;
- its `advanceImpl(float)` advances the UI, tests `isBlinking()`, and reaches the unique
  `remove(Lcom/fs/starfarer/ui/c;)V` only after blinking ends;
- `TitleScreenState.advance()` caps the delta passed into title advancement at approximately
  `1/30f` per call;
- runtime-state v1 published `mainMenuInteractiveAt` immediately after that remove call.

Those facts prove what the old clock measured: deterministic overlay removal. They do not prove that
the menu was unusable until that event. Direct user observation establishes the missing semantic
fact: normal title/menu interaction, including Continue, is already useful while `Preloading...` is
still blinking.

The original macOS transform had the same conceptual contract: its source comment identified the
instrumented target as removal of Starsector's `Preloading...` label. Linux was already different:
its exact target published immediately before the unique return from `show()`.

## Corrected platform boundary

Runtime-state v2 uses one conceptually equivalent earliest reviewed title boundary across the three
supported targets: successful completion of the exact title object's `show()V` method.

| Platform | Exact title target | v1 boundary | v2 `mainMenuInteractiveAt` boundary |
| --- | --- | --- | --- |
| macOS | `com/fs/starfarer/title/B`, SHA-256 `a07eb94f8229ac0bb42139cebc6450518e8fe036023bd7687fb1a76347079f22` | unique `Preloading...` removal inside `advanceImpl(F)V` | immediately before the unique return from `show()V` |
| Linux | `com/fs/starfarer/title/OoOO`, SHA-256 `fcc26761e5ab5896bd100f0b99d02bb008bf07cd2565418daee7409c1d1dafc7` | immediately before the unique return from `show()V` | retained: immediately before the unique return from `show()V` |
| Windows | `MainMenuInteractivePlan.WINDOWS_TARGET_CLASS`, SHA-256 `7a034024de849f2829ad5e41dbb0e58f5979a6e7a81e55527f6839055db3d4c6` | unique `Preloading...` removal inside `advanceImpl(F)V` | immediately before the unique return from `show()V` |

The exact class SHA, class name, method name/descriptor, and unique reviewed bytecode seam remain the
fail-closed transform gates. A mismatched class, hash, method, or ambiguous seam keeps the original
bytes.

For macOS and Windows, the exact reviewed label-removal call remains useful as low-frequency
diagnostics. Runtime-state v2 records it separately as `mainMenuOverlayRemovedAt`. It does not
change semantic state, does not advance the semantic sequence, and cannot delay or regress
`main-menu-interactive`. Linux has no newly invented overlay clock; its existing reviewed `show()`
seam already represented first menu usability, and this repair retains it.

## Continue/control ordering

The closed desktop-smoke Continue path remains on the reviewed title `advanceImpl` boundary. It
requires `RuntimeSemanticState.is("main-menu-interactive")` before processing a
`main-menu.continue` request, then reaches the reviewed title callback path through `getMainMenu()`
and `menuItemSelected(CONTINUE)`.

Publishing v2 interactivity at successful `show()` completion makes that state true before the first
subsequent reviewed title advance. The following title advance can therefore accept a pending
Continue request while the `Preloading...` label may still be present. No click synthesis, sleep,
countdown bypass, title mutation, rendering change, worker change, or timer change is part of this
repair.

## Runtime-state version contract

Historical `starsector-preflight-runtime-state-v1` files remain parseable. Their old
`mainMenuInteractiveAt` value is deliberately exposed by desktop tooling as the legacy v1 overlay
removal endpoint; it is not exposed as `firstUsableMainMenuAt`.

New output uses `starsector-preflight-runtime-state-v2`:

- `mainMenuInteractiveAt`: first reviewed usable-menu boundary;
- `mainMenuOverlayRemovedAt`: later exact overlay-removal observation where supported;
- the existing campaign/simulation/combat state semantics remain unchanged.

This format split prevents old archives from acquiring a new timing meaning merely because the field
name stayed the same.

## Correction to historical timing evidence

All raw v1 measurements remain historical observations. Their endpoint must be described according
to the v1 instrumented seam.

Most importantly, the PR #1222 ordinary Windows reference recorded:

- `main-menu-ready` at 35.806 seconds;
- old v1 `mainMenuInteractiveAt` at 50.514 seconds;
- a 14.708-second ready-to-old-marker interval.

Exact bytecode proves that the latter endpoint was `Preloading...` label removal. User-observed menu
usability precedes it. The 14.708-second interval therefore cannot be described as 14.708 seconds of
user-blocking startup or time until first useful interaction. The deterministic `blink(3.0f, 10.0f)`
countdown and the title-state delta cap remain unchanged Starsector behavior; this task changes only
what Preflight calls and records as the interactive semantic boundary.

The same correction applies to historical v1 `mainMenuInteractiveAt` comparisons throughout the
Windows evidence journal. In particular, standalone Preflight versus Preflight + Fast Rendering
numbers such as 125.255 versus 49.551 seconds, 48.355-second host-profile observations, the
50.998-second early combined observation, worker-successor “interactive” times, and later thin
interactive comparisons used the former overlay-removal endpoint. Their raw values remain valid for
that old endpoint. They require remeasurement with runtime-state v2 before serving as evidence of
genuine time to first menu interaction or before supporting a new standalone-versus-Fast-Rendering
semantic comparison.

This correction makes no new startup-performance claim and does not run or instrument Fast
Rendering.

The first adjacent v2 startup cohort is retained in
[the TEXTURE thread-CPU probe validation](2026-09-02-windows-texture-thread-cpu-validation.md).
Its three Windows runs reached the reviewed v2 usable-menu boundary in 45.938, 40.272, and 46.317
seconds. None observed `mainMenuOverlayRemovedAt`: the runner accepted and stopped each launch at
the earlier usable-menu boundary. These are single-run diagnostic-validation observations, not a
standalone-versus-Fast-Rendering campaign or a replacement startup-performance claim.

## Validation status

The implementation is covered by focused runtime-state, transformer, reader, control-path, and exact
installed-adapter tests. Three-platform repository verification is required because this changes a
platform-specific bytecode transform. The exact Windows/macOS/Linux installed tests only inspect and
transform the reviewed class bytes; they do not start the game.

A physical smoke on Big Red is optional for merge. When that route is next used, the narrow pending
proof is: submit `main-menu.continue` after v2 enters `main-menu-interactive`, before waiting for
`mainMenuOverlayRemovedAt`, and retain the executed title callback/campaign transition while the
`Preloading...` label may still be visible. No performance cohort is required for that proof.

## Next benchmark after this repair

The next performance measurement, when separately requested, should rerun the ordinary controlled
startup comparison with the same accepted identity controls and a v2-capable Preflight build, then
report process start to v2 `mainMenuInteractiveAt` as the genuine first-usability clock. If the
standalone-versus-Preflight + Fast Rendering comparison is needed, both Preflight-backed arms must
use the same v2 semantic endpoint and retain `mainMenuOverlayRemovedAt` only as diagnostics. Run the
usual shuffled/repeated campaign only when a performance claim is actually being made.
