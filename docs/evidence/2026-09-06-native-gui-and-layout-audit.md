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

Final automated and new-package native results are pending.
