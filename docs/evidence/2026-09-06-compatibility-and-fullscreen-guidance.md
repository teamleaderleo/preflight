# Compatibility and fullscreen guidance, 2026-09-06

Development correctness work; this is not selected-release acceptance or a new startup campaign.
The established `processStartedAt → mainMenuInteractiveAt` clock and playtime ledger are unchanged.

## Linux native observations

On Big Red's GNOME/Wayland desktop at 4096×2560, run
`20260906-161143-211-d890b7e7` requested 1920×1200 fullscreen. Read-only Java 17
diagnostics found `startFS=true`, `launchDirect=true`, game fullscreen=true,
LWJGL fullscreen=true, and LinuxDisplay FULLSCREEN_NETWM mode. X11 nevertheless
showed a decorated, fixed-size window without `_NET_WM_STATE_FULLSCREEN`; its
allowed actions omitted fullscreen. The earlier acceleration-off observation had
the same window behavior.

Run `20260906-161621-637-3df4edd9` requested the desktop's 4096×2560 resolution.
The observed X11 state included FULLSCREEN, the client and frame covered the display,
and native RDP visual inspection showed no game window decoration. This establishes
a working native-resolution alternative on this desktop, not a universal Linux rule.
It does not justify silently increasing the user's resolution or blaming prepared textures.

Both diagnostic games were stopped through their exact recorded PIDs. Saved settings
were restored to 2048×1280 fullscreen with sound off; all 83 mods remained enabled.
Receipts and diagnostic output are under Big Red's `benchmark-results/reliability-*`.
A later attempt to recapture xprop with unauthenticated DISPLAY=:0 failed; its empty
output file is not fullscreen evidence. No screenshot artifact was saved for the
native-resolution observation before stopping the game.

## UI changes and browser evidence

Home now names skipped/unavailable optimizations and links to the latest applicable
run's suggested actions in Help. Browser interaction found the old health position
overlapped the ship selector. The link now sits beneath the upper controls and stays
visible while decorative controls fade. Linux Help offers a Game settings action;
the current display resolution is labelled Display without changing the selection.

The compatibility browser fixture was checked at 1040×700 and 720×560. Pointer
activation and keyboard Enter open Help; Game settings navigation preserves the
draft. Screenshots are retained as `benchmark-results/reliability-{home,help}-*.png`.
These are browser previews, not native packaged-app verification. Browser errors
were empty. All 495 frontend tests and the production TypeScript/Vite build passed.
The initial build rejected unsupported test query options; those were corrected.

Windows force-stop/ordinary Quit and a single-generation three-platform packaged
acceptance pass remain separate pending work. Earlier platform package observations
are recorded in `2026-09-06-native-gui-followup.md`.
