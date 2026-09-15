# Native package acceptance — 2026-09-15

This is development-package evidence, not acceptance of a frozen tagged release.

## Package and source identity

Mac and Linux local packages were built normally from `74ccb2450c0e7ec4dc2450d824cf43d5cceb4b25`.
Installed-engine verification passed on both (Mac: 157 extracted entries/109 runtime files;
Linux: 151/108; zero runtime changes, engine smoke passed).

| Artifact | SHA-256 |
| --- | --- |
| Mac DMG | `52519a0a9736a716662252aca50738888ffd4d9d8f974fbee6630349b4a1d3be` |
| Mac installed engine | `e238ef19d64d4ab2fa37cfcc864cf4b23271ccfde675433379eb1759290dc2f7` |
| Linux DEB | `015cea9f35989e489e7db4cc89fa807751eb0e09c6361d1e3e8f2e189c5915a5` |
| Linux installed engine | `e45047149c1b989bc4fd7d4ea14e8ee33b4dfd710b3d77f47ce6ac20df0615ed` |

[Desktop CI 34929772902](https://github.com/teamleaderleo/preflight/actions/runs/34929772902)
passed all three package jobs. This workflow validates installers but does not retain them.
Unsigned Distribution retains downloadable installers; it does not publish without a tag.

## Native interaction

Mac: opened the installed app; inspected divider and wider layout, game settings and theme.
The native folder picker selected `/Applications` and resolved `/Applications/Starsector.app`.
Changed battle size 400 → 401, confirmed Apply, reopened and read 401, then restored 400 and
verified Apply again. Other settings remained 1440×932 windowed, sound on, 6 GiB, 100% UI size.
Dark theme was restored. Source inspection explains the Home control fade after 2.2 seconds;
missing HUD controls in an idle capture alone do not establish a rendering defect.

A normal GUI launch created run `20260915-050525-726-628a8248`, PID 50982, with all 83 mods.
The runtime reached `main-menu-interactive`. Automated GUI Stop attempts did not take effect;
the documented exact-PID engine stop was used after its dry-run selected only this process.
It returned `stopped`; run outcome was `USER_STOPPED`, exit 0, no postprocessing failures.
The native app returned to Ready and was closed. **GUI Stop and automatic minimize/restore
are not established by this fallback.**

That run recorded `processStartedAt=2026-09-15T05:05:26.138Z` and
`mainMenuInteractiveAt=2026-09-15T05:08:25.471934Z` (179.334 seconds). This was a busy-machine
functional run concurrent with native test compilation, not a controlled performance comparison.
Do not use it as a release headline or dismiss it as a proven scheduler effect. Adapter health was
ACTIVE: 48 transformations, zero contained/cache-rejection/wrapper/integrity failures;
prepared audio served 2049 of 2050 decodes. A performance-regression conclusion remains open.

Linux: installed the DEB, opened the native app in an owned transient service, and exercised
Settings → Change folder → Home/Games → starsector-0.98a-RC8 → Open → Home Ready.
This closes the previously unverified picker interaction for these package bytes.
After reconnecting RDP, native settings read 2048×1280, fullscreen on, sound off,
8 GB memory, 100% UI, antialiasing off, battle size 400. Applied battle size 401, observed
“Global game settings applied and verified,” restarted the app, and read 401 in Home Options.
Restored 400 through Apply, verified success, then reopened through the GNOME desktop launcher
and read 400. Other game settings were unchanged.
Then launched from the GNOME desktop application scope (not the transient test service).
Run `20260915-053438-800-5355306f`, PID 2503422, reached main-menu-interactive in 29.865 seconds
on the established clock. This is one functional observation with the shared Windows VM also
running. The larger game resolution was cropped by the RDP view; the visible title/background
rendered, but this is not complete display-fidelity acceptance. Clicked Preflight's native
Stop Starsector button, observed Ready, and verified USER_STOPPED, exit 0, no postprocessing
failures, and the game PID absent. Close/reopen while the game runs and full display acceptance
remain separate unchecked lifecycle cases.

Windows: current shared-display VM booted and its desktop was visible through SPICE inside the
Linux RDP session. No GPU handover was used; Linux retained i915. This establishes access, not
acceptance of the new Windows package. The RDP view subsequently went black, and the saved
Windows Moonlight route failed to connect. Reconnecting later restored the RDP picture.

## Build failure and repair

[Distribution 34930374875](https://github.com/teamleaderleo/preflight/actions/runs/34930374875)
passed Mac/Linux but failed Windows twice. The torn-report recovery test held its damaged fixture
file open while deleting its directory: Windows error 145, DirectoryNotEmpty, at reports.rs:1149.
93 native tests passed and one failed in the first attempt. Preserve both failures.

[PR #1319](https://github.com/teamleaderleo/preflight/pull/1319) explicitly closes that fixture
before cleanup; production report code and recovery assertions are unchanged. The focused local
test and all PR checks passed. The capability source lock was reviewed for this test-only edit.
It merged as `c3fcba47`. A branch Distribution attempt (`34932095821`) ended before any job steps;
it supplies no package evidence. The corrected main Distribution is
[34932267389](https://github.com/teamleaderleo/preflight/actions/runs/34932267389).
It passed all three platforms. The Windows installer SHA-256 is
`e2a09fdcd240cd9db2c88cffe112e3a66d688a2ebc9ce9ab03a07b4381ff68b1`.

### Windows installed-package finding

The guest SSH route returned STAR-WIN11. Installed the corrected main Windows installer silently
over the existing 0.1.0 installation at `C:\Users\leo\AppData\Local\Programs\Preflight`; exit 0.
The installer hash matched its hosted SHA256SUMS. Installed engine JAR SHA-256 was
`e45047149c1b989bc4fd7d4ea14e8ee33b4dfd710b3d77f47ce6ac20df0615ed`.

Default installed verification first failed on B612 license bytes. Updating the clean Windows
checkout from `6aa3dbd9` to exact candidate `c3fcba47` did not resolve it: installed text has CRLF
(4748 characters), source has LF (4655), and they are equal after CRLF normalization. This is
a reviewed-source comparison mismatch, not evidence that the license text changed.

The existing `verifyReviewedSources:false` installed-candidate path then found a separate real
inventory failure: extra `runtime/bin/syslookup.dll` and `runtime/conf/jaxp.properties`.
The same-version silent reinstall did not remove these files. The pre-install tree was not
inventoried, so this does not establish when they first appeared, or reproduce a version-changing
upgrade. Do not claim this installed tree passed integrity, despite green fresh-install CI.
Retained verifier failures are in the local evidence directory.

Native interaction on that installed tree selected `C:\Games\Starsector` through the Windows
folder picker and reached Ready. Readback was 1024×720 windowed, sound on, 4 GB, 100% UI,
antialiasing off, battle size 400. Applied 410, observed verified success, closed/reopened and
read 410. Restored 400 through Apply and observed verified success. No Windows game was
launched from the mixed runtime.

Closed Preflight and moved the mixed installation aside to
`C:\Users\leo\AppData\Local\Programs\Preflight-mixed-runtime-20260915` for diagnosis.
Installed the identical hashed installer into the now-empty original directory: exit 0.
The installed-candidate verifier (`verifyReviewedSources:false`, the existing integrity mode)
passed: 206 entries, 162 runtime files, 45,277,364 runtime bytes, no runtime changes, smoke passed.
This repairs the operator installation; it does not prove an in-place reinstall removes extras.
Follow-up: [#1321](https://github.com/teamleaderleo/preflight/issues/1321) tracks an isolated
stale-file fixture and same-version/version-changing installer comparisons.

A normal Launch from the clean install created run `20260914-144955-479-6232e3f9`, PID 9108
(guest clock differs from the host). Starsector displayed `Pixel format not accelerated`;
the trace reaches LWJGL WindowsPeerInfo.nChoosePixelFormat through Fast Rendering's Display
bridge. The shared QXL profile did not provide the required accelerated pixel format. No menu
marker was reached. Dismissed the game's error dialog; Preflight restored its window and showed
Needs attention. Result: LAUNCHER_EXIT_NONZERO, exit 1, no postprocessing failures, PID absent.
This is a failed launch, not a timing result or GPU-backed Windows game acceptance.

After the attempted failed-run notice dismissal, another Preparing state appeared unexpectedly.
Stopped it through the visible Stop button and observed Prepare and launch again before closing
the app. The cause is unresolved; source wires Dismiss to clearing the failure only. Preserve
this interaction uncertainty rather than claiming the dismissal/retry flow passed.

## Access observations and limits

Big Red reported kernel `7.0.0-30-generic`, i915 on `0000:00:02.0`, and active GDM; do not reuse
the older remembered `-31` state. `/var/crash/202609100635/` was present. The original GPU guard
refused direct invocation while i915 owned the GPU. The VM's current XML has no GPU hostdev;
the installed shared-display launcher verifies that classification before starting it.

RDP initially failed with `Unknown monitor`; Mutter reported no active monitors. Restarting
remote-desktop alone did not fix it. Temporarily changing screen-share-mode from mirror-primary
to extend enabled the native Linux view. Later black captures coincided with PowerSaveMode 3
and ScreenSaver.GetActive=true for leo; that did not establish that the physical desktop was
locked. A fresh check found the physical family Wayland session active with LockedHint=no,
and leo's screensaver inactive. These are separate GNOME sessions. Reconnecting restored the
leo RDP picture without unlocking anything. Do not count black captures as app failures.

Local raw logs, failed observations, package identities, and recovery state are retained under
`benchmark-results/package-acceptance-20260915/`; corresponding Linux build logs remain under
the same relative directory on Big Red. Native screenshots/accessibility observations are in the
operator conversation. Browser preview evidence from #1317/#1318 is separate.

The live release board still requires a selected/tagged generation, native Windows/Linux game
acceptance, package-bound lifecycle/update evidence, the report upload cancel/retry/delete canary,
and explicit publication authorization. This session does not close those gates.
