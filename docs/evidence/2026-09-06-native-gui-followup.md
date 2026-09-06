# Native GUI follow-up, 2026-09-06

These are local development-package correctness observations, not acceptance of a selected release
tag and not a new startup benchmark campaign. The existing
`processStartedAt → mainMenuInteractiveAt` clock is unchanged. Failed observations remain retained.

## Windows memory ownership and native gameplay

PR #1249 (`3cb7856a` on main) fixes a native Windows settings defect. The selected Fast Rendering
launcher `C:\Games\Starsector\starsector-core\fr.bat` references the same `fr.vmparams` in both
conditional branches. Inspection previously counted it twice and then found an unrelated 2 GiB
root response file. Canonical response-file deduplication now identifies the actual 4 GiB owner;
distinct referenced owners remain ambiguous and unwritable.

The local NSIS package was built from `4e8d5f87` and installed under
`%LOCALAPPDATA%\Programs\Preflight`. SHA-256:
`796b82e79433c18a6fe11ce6f85f3414f82e9f5c83a137b4fc7ca6015c0ad7cb`.
Installed-engine verification passed (164 runtime files, 50,192,187 bytes, engine smoke passed).

Actual Windows GUI interaction through Moonlight verified 4 GiB readback, Apply to 6 GiB,
1920×1200 and 150% UI size, then close/reopen retention. Preparation and direct launch used all
83 enabled mods. Run `20260906-151900-248-0ed6a56f` reached the interactive main menu and the stock
“A Fistful of Credits” mission. The screenshot shows intact ship sprites, thrust/effects, text,
and HUD. The agent recorded `jvmMaxHeapBytes=6442450944`, proving the requested heap reached the JVM.
This is a qualitative native visual check, not a pixel-equivalence score for dynamic gameplay.

The original 1024×720, windowed, sound-on, 100% UI, 4 GiB settings were restored. After confirming
that only equivalent heap-unit formatting differed, the original `fr.vmparams` bytes were restored.
The unrelated root `vmparams` and stock `starsector.bat` hashes stayed unchanged throughout.

Retained Mac evidence under `benchmark-results/` includes
`gui-followup-windows-reopened.png`, `gui-followup-windows-main-menu.png`,
`gui-followup-windows-combat.png`, and `gui-followup-20260906/windows-direct-run/`.
Guest build/installation receipts were copied to that evidence directory through the existing share.

## Intentional stop reporting

Clicking the Windows GUI's Stop button stopped the owned game but displayed a crash banner.
`gui-followup-windows-stop-error.png` preserves the failure. The run retained its timing and
`elapsedMillis=498809`, with `outcome=LAUNCHER_EXIT_NONZERO`; the playtime ledger includes this
outcome when a duration exists. An intentional exit does not discard playtime or a completed
startup measurement.

PR #1250 (`b51c8ada` on main) makes the frontend await the matching stop receipt before classifying
the exit event. Successful requested stops show a stopped outcome; failed stops and unrelated PIDs
retain failure reporting. This changes the banner, not the run ledger or startup clock.
All 494 frontend tests and the production build passed with Node 24. Regression tests cover both
receipt/event orderings, failed stops, and unrelated PIDs. The initial test invocation using the
wrong local Node version failed in test setup and is retained in `gui-stop-tests.log`.

## Linux AppImage, direct launch, and remaining fullscreen mismatch

The Linux packages were built from `300eff0d`. Local AppImage generation failed without FUSE,
then failed because linuxdeploy could not locate the bundled `libjvm.so`. Using CI's
`APPIMAGE_EXTRACT_AND_RUN=1` and bundled server-library `LD_LIBRARY_PATH` produced a verified
AppImage. No system dependency or persistent GPU boot configuration was changed.

Package SHA-256 values:

- Debian: `88db343f408c99dc5c9880c6bd7986e5d35f3c7a60064c48716ed4cede012ef1`.
- AppImage: `767258a40930e2738991449576156d1305443d59c846b07362f497382edfc35f`.

Both package verifiers and engine smokes passed. The actual AppImage opened on the Linux desktop
and directly launched run `20260906-153251-220-89d622e8`, with 83 enabled mods, 2 GiB heap,
2048×1280 requested fullscreen, sound off. The main menu and mission/combat HUD rendered without
the reported RGB corruption. The game exited normally through its menu. Screenshots are
`gui-followup-linux-appimage-ready.png`, `gui-followup-linux-main-menu.png`, and
`gui-followup-linux-combat.png` in the Mac evidence directory.

Fullscreen is **not verified**: a decorated window remained visible. Although 2048×1280 was absent
from this remote desktop's advertised modes, a second native GUI launch at the advertised
1920×1200 mode also appeared windowed. Run `20260906-154513-976-a7633ebd` retains that observation.
The launch record requests fullscreen, and inspection of the installed launcher bytecode confirms
that `startFS` is parsed and passed to the game's normal entry point. Root cause remains unresolved;
the unsupported saved size alone is insufficient to explain it. A later screenshot attempt failed
with `noWindowsAvailable`; this does not retroactively pass the fullscreen check. The exact owned
game PID was stopped through the CLI after remote UI capture became unavailable.

A focused optimizations-off comparison, run `20260906-160019-136-ee05d719`, also requested
1920×1200 fullscreen and produced a decorated window. X11 inspection reported a 1920×1200
client, `_NET_WM_STATE_FOCUSED` without fullscreen, and a 74-pixel top frame extent.
`gui-followup-linux-fullscreen-off-window.txt` and the engine hash retain that receipt. This
reproduces without prepared textures; it does not establish the underlying game/compositor cause.
The saved RDP connector subsequently failed to open Windows App with error -600, while SSH
remained available. The exact owned game process was stopped and original settings restored again.
The unresolved display behavior is tracked in
[#1251](https://github.com/teamleaderleo/preflight/issues/1251). A static scan of enabled mod JARs
found no `setFullscreen` references; it does not rule out other mod influence on display handling.

The verified Debian package was installed. Original 2048×1280 fullscreen, sound-off, 100% UI,
2 GiB settings were restored. Runtime records are retained under
`gui-followup-linux-runs/`; failed and successful bundler logs remain on Big Red under
`benchmark-results/gui-followup-linux-*`.

## Mac native gameplay and corrected stop

The Mac package was rebuilt from `d2efdcc0` (the code merged in #1250), verified, and installed
at `/Applications/Preflight.app`. Its local unsigned DMG SHA-256 is
`bad8d907ba5dcc57aeba0bfa5d3e1cf221ae8bac9f991b2505833622d9669343`.
Package-copy boot and bundled-engine smoke passed, with 109 runtime files and 51,010,342 bytes.
This is a local development artifact, not a signed release acceptance receipt.

Native GUI run `20260906-154953-807-fc0e57cd` used all 83 enabled mods and the original
1440×932 windowed, sound-on, 100% UI, 6 GiB settings. Native screenshots show an intact main menu and the stock
“A Fistful of Credits” combat mission, including ship and asteroid sprites, thrust, background,
and HUD. CUA could operate Preflight but could not bind the standalone Java game executable.
Direct screenshots used `screencapture`; game interaction used foreground-guarded native mouse
events and the repository's exact-PID keyboard-event approach. PID-targeted mouse clicks did not
advance the menu; foreground mouse events did. No campaign save was opened or written.

The rebuilt Preflight Stop button stopped the game and displayed “Starsector stopped. The run report
is ready.”, returning Home to Ready without the crash banner. The run retained its duration and
completed outcome. The actual JVM heap was 6 GiB. Screenshots and runtime records are retained as
`gui-followup-macos-live.png`, `gui-followup-macos-combat.png`, `gui-followup-macos-stopped.png`,
and `gui-followup-macos-run/` under the Mac checkout's `benchmark-results/`.
Settings were unchanged and the owned game and GUI were closed.

The earlier quantitative stock-launcher comparison remains separate:
[Mac launcher fidelity](2026-09-06-macos-launcher-alignment-and-direct-start.md).
These changing gameplay scenes do not have an equivalent pixel-identical reference.

## Evidence boundaries and host state

PR #1249 passed full Java verification and three-platform package CI. PR #1250 passed frontend
and release-contract CI; the scope classifier skipped native package jobs for that frontend-only
change. Browser layout acceptance from earlier GUI audits is separate from the native observations
above. No new randomized performance comparison or universal texture-fidelity claim is made here.
The corrected stop banner has native Mac coverage and automated Windows-exit regression coverage;
the corrected Windows package's Stop interaction has not been repeated in the VM.

Windows was shut down through the guest agent before GPU rebind. The shared PCI device was
returned to `i915`, GDM started, and SSH plus the saved RDP route were verified again. Persistent
VFIO boot configuration was unchanged. Windows rebuildable outputs were pruned while the VM was
still running. See [operator access](../native-gui-operator-access.md) for the verified procedure.
