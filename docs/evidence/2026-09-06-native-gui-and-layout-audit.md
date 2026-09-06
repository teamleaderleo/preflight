# Native GUI and layout audit, 2026-09-06

## Linux native interaction before the layout changes

Source `22e28d92`, normally rebuilt Debian package SHA-256
`a0c0afba552f002ccba22320af146b759f35d1b18d644dc940a4c416c12bbb58`.
The installed engine verified 108 runtime files / 52,389,105 bytes and passed its smoke check.

The actual GTK folder picker was exercised through Home → Games → starsector-0.98a-RC8 → Open.
Preflight reached Ready. Settings read back 2048×1280 fullscreen, sound off, 2 GiB memory,
100% UI size, battle size 400 and AA off. Temporary changes to 3 GiB and battle size 600 were
applied, verified by the GUI, and retained after closing/reopening Preflight. Both changes were
then restored through Apply and the original values were visibly verified.

At the default window size the dirty settings footer was clipped and pointer scrolling did not
reveal Apply. Maximizing made it reachable. This failed observation is retained, not counted as
a default-size pass. Subsequent browser inspection found computed `overflow-y: hidden`: a rule
for a settled Speed page also matched its hidden React Activity while Game settings was active.

An RDP transport disconnect interrupted the first launch attempt; SSH showed GDM and Preflight
still alive and no game started. This is excluded from launch acceptance. After reconnecting,
one actual game launched directly. Closing Preflight preserved the game and its monitor;
reopening recovered Running, and Stop returned to Ready with the selected installation retained.
The GUI was closed and an SSH process check found no test GUI/game/Java process remaining.
Run evidence is `20260906-181926-081-29d98eee`. This was a correctness check, not a startup
benchmark. No mod or benchmark-clock changes were made. The existing Linux fullscreen limitation
in #1251 was not resolved by this work.

## Layout corrections

The maintainer identified unused margins and visible cutoffs in large native windows. The main
workspace's 1120px width ceiling is removed. Ship framing now uses a cached, rotation-independent
perspective bound including deck, structure, engines and mounts. It reserves room for the maximum
zoom and strokes rather than fitting each animated projection. The floor fades at the canvas edge.
The settled-Speed scrolling rule now explicitly selects the Speed viewport.

Browser observations: the main workspace reaches the window's right edge at 2048×1280 and
3840×2160, with no horizontal document overflow. Home and Hangar were rendered at large and
minimum sizes. At 1040×700 the dirty settings workspace changed from hidden overflow with
scrollTop 0 to auto overflow with scrollTop 130, exposing Apply. At 720×560 the footer was
scroll-reachable; checkbox → Tab focused Apply, Enter saved the preview values, and Home remained
usable. These are browser preview results, not native package acceptance. Native screenshots,
browser screenshots, failed observations and command logs remain under
`benchmark-results/gui-audit-20260906-final/` on their respective machines.

## Verification of the layout package

Candidate source `70fbf7e489b05bd2d8603076c209cdd87776db99`, merged by #1259 as
`6aa3dbd9`. All 497 frontend tests (85 files), the production frontend build, and diff checks
passed. The explicitly dispatched full three-platform Desktop application CI run `34052243323`
passed on this candidate. Ordinary PR checks and native manual observations are separate evidence.

The updated Linux Debian package SHA-256 was
`a145fdfb0b08b7a04761a5d6ae10147baaad87db3e64f7f48d70ff65d0eeb6a1`.
Installed-engine verification again passed. In the native default-size GUI, keyboard navigation
revealed the confirmation and Apply button for an unsaved battle-size draft; PageUp scrolled back
to the top. The draft was discarded. Remote wheel attempts did not move the page and are not a
wheel-input pass. Maximizing filled the available width with a complete ship visible. No extra
game was launched on this layout generation; the preceding Linux lifecycle observation remains
identified by its own source above. The owned GUI process was explicitly stopped before GPU handover.

The Windows NSIS package SHA-256 was
`0D4675070F46EF40CF7ECB2A5549F716D2D535241445EAE78FD5853F6D236E1D`.
The normally built native/frontend package reused the verified Java engine: the Java source diff
from the existing engine generation was empty. Package and installed-engine verification passed
(164 runtime files / 50,192,187 bytes). Installation was to
`C:\Users\leo\AppData\Local\Programs\Preflight`.

Windowed Moonlight rendered the Windows desktop. Initial click attempts did not open the GUI;
after reconnecting, selecting the desktop shortcut and pressing Enter worked. The native folder
chooser selected `C:\Games\Starsector` and returned to Settings with that path. The original
1024×720 windowed, sound-on, 100% UI, 4 GiB, battle 400 and AA-off values read correctly.
The resolution list included 3072×1920 marked Display. Temporary 1920×1080, 125% UI and 6 GiB
changes applied successfully and survived GUI close/reopen; an installed-engine read independently
confirmed them. Scrollbar dragging exposed Apply. Remote wheel/initial confirmation attempts were
ineffective, so these are not claimed as passes. Maximized Home filled the workspace with no ship
cutoff in the retained screenshot. The original custom resolution, scale and memory were restored
using the installed CLI with the GUI closed; its JSON receipt verified all original values.

Windows preparation completed, then launched the game directly to its main menu. The long texture
stage displayed 71% because the frontend divided completed phases by seven, not measured work.
The maintainer identified the misleading presentation; the follow-up replaces percentage text
with the active stage, removes the phase-derived percentage bar, and shortens “Stop safely” to
“Stop.” Thirteen focused component tests passed; normal preview pages rendered at both required
sizes without console errors. The long-running native preparation observation belongs to the
preceding package, not these later copy changes.

Alt+F4 closed the Windows GUI while the game and wrapper survived. Reopening recovered Running;
Stop returned to Ready. Run `20260906-190721-385-de4c21d8` recorded 253,624 ms in both run metadata
and the launch ledger. Its outcome was `LAUNCHER_EXIT_NONZERO` (exit 1), with no fatal evidence and
`controllerStopRequested: false`; this is a retained stop-classification discrepancy. `Playtime`
counts this outcome, so the duration is not discarded. Do not call the outcome classification a
clean pass. The game and GUI were absent in the final process guard, and Windows build outputs
were pruned. Its clean checkout was fast-forwarded to layout main `6aa3dbd9` before shutdown.

The Windows VM was shut off before the GPU was rebound to i915; GDM returned active. The saved
Linux RDP device then connected directly in windowed mode. No persistent GPU boot configuration
was changed. Moonlight and RDP connections were closed after verification.
