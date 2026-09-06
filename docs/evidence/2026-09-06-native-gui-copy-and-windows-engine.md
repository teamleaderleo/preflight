# Native GUI copy, layout, and Windows engine verification

Source tested: `44bfda0e` (PR #1246), following main `5c6fbe2b`. These are local development
package observations, not a selected signed release acceptance or a startup benchmark campaign.
Access procedures are in [Native GUI operator access](../native-gui-operator-access.md).

## Changes and findings

- Removed repeated game-file/save-file assurances and explanatory subtitles from battle size,
  frame pacing, and editable memory. The battle-size warning appears only above the game's slider
  maximum. Frame-pacing distribution data remains visible.
- Home Options fits its controls, with less vertical padding and clearance for its toggle and
  launch actions. A first browser matrix found 2 px of nested overflow at 880×640; the corrected
  padding passed the repeated matrix. Dirty settings retain access to Apply.
- The actual Windows package initially failed to find the Java main class. Its canonical
  `\\?\C:\...` JAR path reproduced the failure with the bundled Java, while the equivalent normal
  path succeeded. `EnginePaths::command` now simplifies Java/JAR paths with `dunce`; it does not
  change which files are executed. Release Windows builds also suppress the GUI's console window.
  A real bundled-JVM regression runs in Windows package CI.

## Native interaction

| Platform | Actual interaction and result | Limits |
| --- | --- | --- |
| Linux | GTK folder chooser selected `/home/leo/Games/starsector-0.98a-RC8`, reached Ready. Read 2048×1280 fullscreen, sound off, 100% UI, 2 GiB heap. Applied 1280×800 and reopened with installation and resolution retained. Restored 2048×1280. | Picker and Apply were on the initial main package; final source package was installed and its bundled engine verified separately. No game launched in this audit. |
| macOS | Selected `/Applications/Starsector.app` in the native picker. Applied native 2880×1864, 200% UI, 4 GiB; quit/reopened with all retained. Restored 1440×932 windowed, sound on, 100% UI, 6 GiB. Final package's compact Options checked. | Launch opened the stock launcher. User reported malformed launcher graphics; cause unresolved. No interactive main menu observed. |
| Windows | Native chooser selected `C:\Games\Starsector`, reached Ready with fixed package. Applied 1920×1200 and 150% UI; reopened and saw both retained. Restored 1024×720 windowed, sound on, 100% UI. Launch opened the stock launcher; closing it stopped the child process. | Memory correctly refused editing because root `vmparams` and `starsector-core\fr.vmparams` specify different heaps (2/4 GiB). Successful memory writing is unverified. Stock launcher displayed 1600×1200 although saved settings remained 1024×720; discrepancy unresolved. No interactive main menu observed. |

Moonlight mouse scrolling did not reach the Windows Apply panel; keyboard tab navigation revealed
it and completed Apply. This was not a missing Apply panel. A desktop shortcut required keyboard
activation after pointer focus. Native observations are distinct from browser previews.

The Mac launch run was `20260906-042251-535-1b83d71f`; an attempt to inspect the stock launcher
with the native app selector also opened a second wrapper process. Both were stopped and verified
absent. The Windows run was `20260906-044134-368-867d07d9`, process start
`2026-09-06T04:41:34.473Z`; runtime state became stopped and `mainMenuInteractiveAt` remained null.
Neither run supplies a startup duration. The established clock remains
`processStartedAt → mainMenuInteractiveAt`; screenshots and launcher appearance do not replace it.
All 83 Windows mods remained enabled. No acceleration benchmark was repeated.

## Package identities and automated evidence

Local final package SHA-256:

| Artifact | SHA-256 |
| --- | --- |
| macOS DMG | `c2faa6bf4ff755cdfb2ad362a64bd14d69b60b48f787d6f358c86ec77bf0296a` |
| Windows NSIS | `d323df985829609f9f3155293985ca9e10811fe5f64334c884cbb939ab9b308b` |
| Linux Debian | `c72eda9730b66cd51894c300d5dd7fd50e73fc6866fb58c49e9020cd9e0aa583` |

Installed Windows engine verification passed (208 entries, 164 runtime files); final Linux passed
(151 entries, 108 runtime files). Final local Linux build produced Debian/RPM but AppImage
packaging failed at `linuxdeploy`; this failed observation is retained. CI Linux packaging passed.

Focused frontend/style tests: 119 passed in five files on repository Node 24.20.0. Native Mac
tests: 109 passed across library/binary/integration suites. The Windows canonical-path bundled-JVM
test passed locally. An initial Node 26 run failed because localStorage was unavailable; it is
excluded from supported-runtime acceptance and its log is retained.

Browser previews at 1040×700 and 720×560 checked Options, dirty Apply, settings, and frame pacing.
The ten-size layout matrix passed after the padding correction, including all four frame-pacing
distributions. These previews use browser fixtures, not native installation interaction.

[Desktop CI run 34011222189](https://github.com/teamleaderleo/preflight/actions/runs/34011222189)
passed engine, frontend, release contracts, native host, and Linux/macOS/Windows packages, including
the Windows bundled-JVM regression. Separate three-platform repository checks also passed.

## Retained records and handback

Local audit logs/screenshots are under `benchmark-results/gui-audit-20260906-*` on the Mac and
canonical Linux/Windows checkouts. Windows run JSON, runtime state, console, and settings receipts
were copied to the Mac's `gui-audit-20260906-windows-receipts` directory. Failed Node-version,
capability-guard, initial layout, canonical-path, and AppImage observations remain available.
These ignored local artifacts are not committed game assets.

Windows was shut down through the guest agent and verified shut off. The GPU remained on
`vfio-pci`; its runtime driver override was changed to `i915`, unbound, reprobed, verified on
`i915`, and GDM started. Persistent VFIO configuration was unchanged. No reboot was performed.
The saved RDP route worked again. The final Linux package rendered Ready with compact Options,
2048×1280 fullscreen, sound off, 100% UI, and 2 GiB memory; its screenshot was retained.
