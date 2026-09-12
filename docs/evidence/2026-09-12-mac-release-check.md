# Mac installed-app release check

## Identity and scope

Checked on 2026-09-12 with current source `03a62fcaee36996ec1996cfe593d74341ba5b355`.
The installed `/Applications/Preflight.app` remains the development build from
`363b458215bd9ad9c43ce53e1a9cc593ff5dfb07`; its engine SHA-256 matches its bundle
receipt: `b124a1eff7ce94100754eb316b76e9225569f6a64f3472454c15f471352b0931`.
These are installed development-package observations, not current-source or tagged
release acceptance. In particular, the installed engine predates #1308.

The user confirmed Big Red is offline while travelling and restricted this pass to
Mac. No host/VM changes or further remote checks were performed after that direction.

## Native Preflight interaction

- Opened windowed at 1040×700, reached Ready, and read the real Starsector installation.
- Home options opened as the compact settings panel. Readback: 1440×932, windowed,
  sound on, 6 GB memory, 100% UI size, antialiasing off, battle size 400.
- Changed battle size to 600 through the full settings preset, closed-tools
  confirmation, and Apply. The app reported applied and verified. Quit/reopen
  displayed 600. Restored 400 through Home and Apply; successful verification was
  shown. The input retained a cosmetic `0400` string after this automation edit.
- Settings → Change folder opened the real macOS picker. Choosing `/Applications`
  resolved to `/Applications/Starsector.app`.
- Updates explicitly reports that this development build has no updater verification
  key. This build cannot establish signed updater acceptance.
- Native Launch created game PID 27339 and reached the interactive-menu marker.
  Stop clicks through accessibility and coordinates produced no observable change;
  restoring the window through its Window menu did not resolve this. This is an
  unresolved interaction check, not an established product Stop defect. The exact
  owned PID/start identity was checked, then PID 27339 was stopped with SIGTERM.
  Preflight returned to Ready and displayed its run report. This does not establish
  an in-game Quit, successful Stop click, or automatic restoration without operator input.
- Session count advanced from 875 to 877 across the two checks. Home options were
  collapsed again and the app was closed after testing.

Native UI screenshots and interaction results are retained in the task tool history.

## Game capture and startup observations

The installed engine's existing `desktop smoke probe` reported ready. Reused the
retained `menu-before-close.json` scenario: interactive marker → exact-PID activation
→ capture → three-second hold → quit. No campaign was loaded and no save was written
by the scenario. The harness passed and stopped PID 26401.

Unlike the earlier #1309 images, the inspected screenshot shows readable Starsector
menu controls, normal-looking colors, a planet and nebula, and a normal native title
bar. The preloading overlay remains visible. This accepts the visible menu scene for
this observation; it neither explains the earlier capture failures nor establishes
pixel parity, settled-menu animation, campaign, or combat acceptance.

Capture finished at `2026-09-12T09:27:07.196484Z`; quit began at
`09:27:10.200797Z`, more than three seconds later. Screenshot SHA-256:
`3e0849ac39a7c32ee75f33c0fbdf5605731270fb1a7674c46ec0b8b18eeea4e7`.

| Check | PID | processStartedAt → mainMenuInteractiveAt | Shutdown |
|---|---:|---:|---|
| Existing capture scenario | 26401 | 49.507204 s | Harness exact-process quit |
| Native Launch button | 27339 | 37.669729 s | Operator SIGTERM after ineffective UI attempts |

These are uncontrolled correctness observations on the user's active Mac, not a
performance campaign or evidence attributing a regression. No timing endpoint was
changed to capture, graphics preload, or overlay removal.

The first run served 2049 prepared audio items with one game decode and zero audio
failures. Prepared textures were ready, with 15469 hits, three missing-entry
fallbacks, zero corruptions and zero internal errors. This is evidence of active
prepared caches during the usable capture, not general gameplay acceptance.

## Retention and remaining gates

Raw receipts and the original screenshot are under
`benchmark-results/mac-release-check-20260912/`; native launch records were copied
into its `native-launch/` directory. The native run also remains under
`~/.starsector-preflight/runs/20260912-092816-763-185ae1f2/`.
Both game process records say stopped and the final process check found no running
Starsector or Preflight process. All 83 mods remain enabled with unchanged enabled-mod
SHA-256 `76227ce91333c202271e541774f3e86fd8711c2542d63a81cfd18a4dc0a6997f`.

No new package was built or installed, no release tag was created, and no report was
uploaded. Current-package refresh, native Stop acceptance, campaign/combat/disposable
save lifecycle, external-game duplicate-launch handling, signed update/lifecycle and
packaged upload cancel/retry/delete acceptance remain open. Windows/Linux checks are
deferred while Big Red is offline. Earlier failed captures remain preserved.
