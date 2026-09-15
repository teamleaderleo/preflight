# Windows software rendering and installer recovery

This follows the [native package audit](2026-09-15-native-package-acceptance.md).
The shared QXL VM intentionally keeps the physical GPU on Linux. Its earlier pixel-format
failure did not establish that passthrough was required.

## Installer

[PR #1322](https://github.com/teamleaderleo/preflight/pull/1322), merged as `9ff79ced`, replaces
the recognized bundled engine before copying Windows installer resources. It refuses linked
engine trees and does not remove files outside the engine. The native Windows installer test
injects an obsolete runtime file, reinstalls the same package normally and with `/UPDATE`, and
verifies the exact resulting inventory. A junction fixture verifies rejection and preservation
of external data. All three platform package jobs and release/native-host checks passed in
[Desktop CI 34951705035](https://github.com/teamleaderleo/preflight/actions/runs/34951705035).

## Software-rendering setup

The preserved passthrough evidence identified the disabled Mesa shim in the game JRE.
Copied it back to `C:\Games\Starsector\jre\bin\opengl32.dll`, preserving the disabled-name backup.

| File in the game JRE's bin directory | SHA-256 |
| --- | --- |
| `opengl32.dll` and `opengl32.mesa26.2.0.llvmpipe-disabled.dll` | `33b217ed7947b48684baa987914475898a2b4d7d64cce96b078216c67a633582` |
| `libgallium_wgl.dll` | `1a2e49cd5fdb1a857d98117ab04240d723b57da5dffe6d07f5386f42014557c1` |

The legacy `Play-Starsector-VM.cmd` sets `GALLIUM_DRIVER=llvmpipe`; normal Preflight selected
`starsector-core/fr.bat`, which calls the same game JRE but does not set that environment variable.
Both Windows User and Machine Gallium values were empty. Set the User value to `llvmpipe`.
The existing desktop did not pick up the new value: a subsequent GUI launch still emitted
`preflight.padding.unpadded=true`, inconsistent with the explicit llvmpipe policy. Restarted only
the Windows guest so the desktop would inherit its new environment. Host i915/GDM stayed active.

Preserved failures before the refreshed desktop:

| Run | Observation |
| --- | --- |
| `20260914-181914-580-55a13ba7` | Shim restored; exit -2147024809 after 3548 ms; no interactive-menu marker |
| `20260914-183315-322-3e90118d` | Old desktop environment; same exit after 3107 ms; unpadded policy still enabled |

These are failed launches, not startup performance results. Guest timestamps differ from host
timestamps. During intervening GUI inspection, Big Red had load average about 74 and 5.1 GiB swap
in use; a Party Protocol Actions runner owned the observed Chrome workload. No other task's
processes were stopped. A direct packaged-engine settings read subsequently returned exit 0 in
1.23 seconds. Preserve the GUI timeouts and unavailable-settings observations without attributing
them conclusively to scheduling.

## Native recovery result

After the guest restart, the first app instance again showed unavailable settings and a failed
storage calculation. The packaged engine read the saved settings successfully. Closing and
reopening the app reached Ready with the correct values; the intermittent first-open failure
is tracked in [#1323](https://github.com/teamleaderleo/preflight/issues/1323) and is not dismissed as a renderer problem.

Normal GUI Launch then reached the game's main menu in run
`20260914-184746-221-c782ba45`, game PID 9656. The process loaded both `OPENGL32.dll` and
`libgallium_wgl.dll` from the game JRE. The run report confirmed padded llvmpipe uploads
(`npotDirect=true`, `unpadded=false`), with Recommended prepared textures and validated prepared
audio effective. All 83 enabled mods were retained. Settings remained 1024×720 windowed,
sound on, 4 GiB, UI 100%, antialiasing off, battle size 400.

`processStartedAt=2026-09-14T18:47:46.411Z` to
`mainMenuInteractiveAt=2026-09-14T18:48:35.053044200Z` is **48.642 s**. This is one functional
recovery observation on the shared software-rendered VM and busy host, not a replacement for
prior startup claims. `mainMenuReadyAt` was absent; overlay removal was later and was not used
for timing. The CUA screenshot taken before closing showed readable menu text and intact planet,
background, and button textures. A later exit-confirmation screenshot also showed a moving ship.
These are visual observations through the RDP/SPICE stream, not a pixel-equivalence test.

Clicked the game's Quit → Yes. The run completed with exit 0, no execution or postprocessing
failure, and its game PID disappeared. Preflight restored its window to Ready with unchanged
settings. This checks ordinary game exit; it does not substitute for the GUI Stop contract.

Keep the restored shim and User `GALLIUM_DRIVER=llvmpipe` for this shared VM. Reversing this
configuration would remove that User value and disable the active shim while retaining the backup;
do not do so merely to run Windows alongside Linux.

## Installed fixed candidate

Unsigned [Distribution 34952686919](https://github.com/teamleaderleo/preflight/actions/runs/34952686919)
completed successfully for source `9ff79ced`. Windows installer SHA-256:
`51ec73128f4417f28540dc243e4e8a6f0747df23435962b43e038733b4ae115d`.

With the app and game closed, copied the two known obsolete files (`runtime/bin/syslookup.dll`
and `runtime/conf/jaxp.properties`) from the preserved mixed-install backup into the installed
engine, then ran the fixed installer against `%LOCALAPPDATA%\Programs\Preflight`.
Installer exit 0; both obsolete files disappeared; the backup remained. Installed verification
using the matching source and `verifyReviewedSources:false` passed: 206 entries, 162 runtime files,
45,277,364 runtime bytes, no runtime changes, engine smoke passed. This mode checks the packaged
inventory and reviewed capability boundary while avoiding the previously documented CRLF source
font-license mismatch. The engine JAR remains
`e45047149c1b989bc4fd7d4ea14e8ee33b4dfd710b3d77f47ce6ac20df0615ed`.
The updated native app reopened to Ready with unchanged settings. No redundant startup run was
performed for this installer-only source change. The successful game run above used the prior
`c3fcba47` package with those same engine bytes.

Raw successful-run reports and the downloaded installer/checksum are retained under
`benchmark-results/package-acceptance-20260915/`. Failed guest runs and the mixed-install backup
remain preserved.

## Cleanup

Closed the updated GUI and confirmed no Windows Java/game/Preflight process remained before
orderly guest shutdown. `win11-starsector` is shut off. The owned viewer unit was no longer loaded;
no viewer process remained. Windows App on the Mac is closed. Restored the Linux RDP setting to
`mirror-primary` and restarted only the user's remote-desktop service. Removed this task's exchange
staging after copying the run evidence locally.

Final SSH checks: GPU `0000:00:02.0` belongs to i915, GDM is active, Tailscale reports Running and
Self online, and `/var/crash/202609100635/` remains present. The observed host kernel is
`7.0.0-30-generic`; do not substitute the older requested `-31` assertion for this live result.
No host reboot, GPU detach, passthrough change, or other task's process termination occurred.
