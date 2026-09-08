# Mac package refresh and native selection check

The installed `/Applications/Preflight.app` was replaced with a normal local build
from `363b458215bd9ad9c43ce53e1a9cc593ff5dfb07`. The previous installed engine receipt
identified `03c1e581ccd6193c865f2ddd4b10336cbf90e878` and did not match the current
reviewed capability boundary.

## Package identity and checks

- Build: `npm run desktop:build`, Node 24.20.0, canonical Mac checkout.
- Engine SHA-256: `b124a1eff7ce94100754eb316b76e9225569f6a64f3472454c15f471352b0931`.
- Native host SHA-256: `b857f02ab91e5984a9be3ad9fbea9878f724799eb238ff46033826e984033f7e`.
- DMG: `Preflight_0.1.0_aarch64.dmg`, 49,878,959 bytes.
- DMG SHA-256: `82d971996ef6fa46e019b7d8142c8ef8193d0994f391ec63643563bf5f741700`.
- Built and installed payload integrity checks passed.
- `desktop:verify-package` passed DMG install-copy, native-host boot, runtime
  integrity, and engine smoke checks after the normal app was closed.

This is a local development package, not a tagged Distribution candidate. The
verifier reports `platformSignature: unsigned-or-invalid` and no updater archive;
the local build has only the compiler's linker signature, not a sealed signed app
bundle. A separate strict `codesign` check failed accordingly. Settings correctly
reports that this development build has no updater verification key. No signing,
update-channel, or final-release acceptance is claimed.

## Native interaction

The installed app reached Ready with the existing installation and preferences.
Using Settings → Change folder opened the real macOS picker. Choosing its current
`/Applications` directory resolved to `/Applications/Starsector.app`. Quitting and
reopening Preflight retained the selection.

The installed engine's separate `desktop snapshot --game /Applications` read
returned Ready, exactly one candidate, no diagnostics, and selected
`/Applications/Starsector.app/Contents/MacOS/starsector_mac.sh`. This corroborates
the native picker result; it does not substitute for that interaction.

Native Home options read back 1440×932, windowed, sound on, 6 GiB heap, 100% UI
scale, antialiasing off, and battle size 400. The existing minimize-until-game-exit
preference persisted. No game settings were applied or changed.

Clicking Launch in the native app created wrapper PID 89456 and game PID 89487.
The retained run records the correct launcher, `directLaunch: true`, and
Recommended optimizations. It reached the runtime-state v2 interactive-menu
marker. This was one uncontrolled GUI correctness run, not a benchmark campaign:
`processStartedAt` was `2026-09-08T23:45:12.268Z`, and `mainMenuInteractiveAt` was
`2026-09-08T23:46:15.853072Z` (63.585 seconds). The elapsed time is retained without
claiming performance equivalence or attributing its difference from earlier runs.

Adapter health reported 52 applied transformations, one declined transformation
with original code retained, and zero contained failures. Prepared audio served
2049 items, left one decode to the game, and reported zero failures. No gameplay
or combat acceptance is established by these startup receipts.

## Incomplete and excluded observations

- Computer Use could list the running Java app as `com.azul.zulu.java` but could
  not attach to it. Looking up `Starsector` by display name opened an additional
  stock launcher, PID 90081, and still failed to attach. That extra launcher was
  stopped with SIGTERM. This was an operator/tool error, not a second launch by
  Preflight, and does not constitute reproduction of #1305.
- Native game screenshots and in-game Quit remain unverified. Attempts to focus
  Preflight and use its Stop control during the game also had no observable
  effect through this automation path; Stop-button acceptance is not claimed.
- The exact owned game PID 89487 was stopped with SIGTERM. Preflight returned to
  Ready, and its session count advanced from 869 to 870. Because focus/raise
  actions were attempted, this does not independently prove automatic window
  restoration on a normal game exit.
- The first full package-verification attempt hit the running app's singleton
  check. Closing Preflight and rerunning the same verifier passed. An initial
  snapshot invocation also supplied unsupported `--json`; the corrected command
  above returned the expected structured output. Neither attempt launched a game.

## Preservation and cleanup

The pre/post settings, preferences, and memory objects matched exactly. All 83
mods remained enabled; `enabled_mods.json` SHA-256 remained
`76227ce91333c202271e541774f3e86fd8711c2542d63a81cfd18a4dc0a6997f`.
Both test game processes and the owned wrapper exited. The refreshed app is left
installed and open; the temporary rollback app and rebuildable outputs are retired.

Local receipts, state, and copied run records are under
`benchmark-results/mac-package-refresh-20260908/`. The original run remains at
`~/.starsector-preflight/runs/20260908-234512-024-68bcbb13`. Tool logs retain the
failed attempts; native screenshots are retained in the task's Computer Use output.
