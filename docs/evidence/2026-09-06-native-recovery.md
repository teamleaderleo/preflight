# Native recovery follow-up, 2026-09-06

These are development-package correctness observations, not release-tag acceptance. The source
for the first package generation was `ca00255b0897fbdd37f0e9520c26678a8d9b4d41` (PR #1253,
merged as `7f4697df`). All jobs in Desktop CI run `34045875379` passed, including three-platform
native-host validation and native package installation. CI does not retain these native packages;
the following packages were built locally from the same source.

## Mac

Installed DMG SHA-256: `07c2f3f5fa46225b1f800c661f16a151160bc1b59fe188252b19be9c3f722869`.
Package verification, installed-copy native-host boot and engine smoke passed. This is an unsigned
development package. The native compatibility link opened the latest run's suggestions in Help.

Run `20260906-163847-167-c67a41eb` launched directly. Closing Preflight exited the GUI and left
game PID 1357 running. Reopening the installed app showed Running, a reconnection message and Stop.
Clicking Stop ended that exact game and returned to Ready with a completed-report message. The run
retained `outcome=COMPLETED` and `elapsedMillis=50867`. Original Mac game settings were unchanged.
Native screenshots and receipts are under `benchmark-results/reliability-native-macos-*` and
`benchmark-results/reliability-macos-run/`.

## Linux

Installed Debian SHA-256: `0cc43d9d327d692ec1f5a0ae18f3da33d2083763ea27105a52e2cf56348c7d95`.
Installed-engine verification passed (108 runtime files, 52,389,105 bytes, engine smoke passed).
The full local build produced Debian/RPM; AppImage failed without its documented environment.
That failure is retained in `benchmark-results/reliability-linux-package.log`.

The native Help action opened Game settings with 2048×1280 fullscreen, sound off, 100% UI and
2 GiB intact. Actual GTK folder selection traversed Home → Games → starsector-0.98a-RC8 → Open
and returned to Ready with that installation. The attempted keyboard location entry instead
entered search and found no results; it is excluded from the successful picker observation.
Windowed RDP restored mouse automation after fullscreen RDP returned `noWindowsAvailable`.

Run `20260906-164506-430-de2147ba` was launched under a transient GUI service. Closing the GUI
caused systemd's default control-group cleanup to terminate its child game. This is an excluded
operator setup, not a product recovery failure. The operator guide now distinguishes it from
GNOME's installed application launcher.

Run `20260906-164812-766-f87f157d` used the installed GNOME launcher under
`app-gnome-Preflight-2304450.scope`. Closing GUI PID 2304450 left game PID 2305188 alive.
Reopening the installed app recovered Running and Stop. Stop ended the game, but the post-exit
refresh returned to Setup: automatic discovery omitted this manually selected installation and
cleared its remembered path. The failure screenshot is retained as
`benchmark-results/reliability-native-linux-recovery-lost-install.png`.

The fix supplies the current remembered installation to the recovered-game completion refresh.
A regression using a game outside automatic discovery reproduced the failure before the fix and
passes afterward, asserting both Ready and persistence of the exact selected path. It changes no
startup clock, stop receipt, run duration or mod selection. Native re-verification is pending.
