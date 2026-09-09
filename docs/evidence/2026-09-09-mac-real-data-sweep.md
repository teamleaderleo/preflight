# Mac real-data GUI sweep and sibling-bundle discovery correction

## Scope and identities

Native checks used `/Applications/Preflight.app`, the development package built from
`363b458215bd9ad9c43ce53e1a9cc593ff5dfb07`, with engine SHA-256
`b124a1eff7ce94100754eb316b76e9225569f6a64f3472454c15f471352b0931`.
The checkout started at `e23d8bfde52448c967c354fd2e572d5ff334bbd9`.
The real installation was `/Applications/Starsector.app`, with all 83 mods enabled.
This was a busy-machine correctness sweep, not release-package acceptance or a performance campaign.

Raw receipts and logs are retained locally under
`benchmark-results/mac-real-data-sweep-20260909/`. Native app screenshots and interaction receipts
are in the Codex tool history; game-window PNGs are in the two run directories.

## Native app observations

- Home and full game settings read 1440×932, windowed, sound on, 6 GiB heap, 100% UI size,
  antialiasing off, and battle size 400.
- Settings → Change folder opened the real macOS picker. Cancel returned without changing the
  selected installation. The previous sweep owns successful folder selection acceptance.
- Home battle size was changed to 600 using the closed-tools confirmation and Apply. The installed
  engine read back 600; quitting and reopening the app also displayed 600. A second native Apply
  restored 400. Before/after settings, preferences, and memory objects match exactly.
- Mods → Check setup completed on the real profile. It reported one qualified item about variant
  hull references whose winning hull definitions could not be decoded exactly; this is not proof
  of a broken mod setup.
- A temporary `QA disposable 20260909` profile captured the real 83-mod order. Native rename review
  and Apply renamed it to `QA renamed 20260909`. The generated profile was then deleted through
  its native review flow. No profile was activated to change enabled mods.
- Help → Copy setup completed with `Setup copied` feedback. No report was uploaded or sent.

The Mods and Settings content initially disappeared from the automation tool's accessibility tree
despite remaining visible and keyboard-focusable. After quitting/reopening, Mods controls were
exposed correctly. Retain this as a transient observation, not a confirmed accessibility defect.

## Exact-PID game automation

The installed engine's `desktop smoke probe` reported the macOS driver ready. A bounded scenario
derived from the checked-in campaign scenario waited for `main-menu-interactive`, activated the
recorded PID, waited, captured screenshot/log/health/frame artifacts, and quit. It did not Continue,
load a campaign, move, or save. Both runs used the installed engine and its bundled Java runtime.

| Run | PID | Process → interactive | Capture delay after activation | Result |
|---|---:|---:|---:|---|
| `menu-run` | 90823 | 35.535538 s | 3 s | All harness steps passed; process stopped |
| `menu-settled-run` | 91261 | 38.821329 s | 30 s | All harness steps passed; process stopped |

These are uncontrolled individual observations. The later capture was specifically needed because
the first image was nearly black; it was not an attempt to improve a timing result. The established
clock remains `processStartedAt → mainMenuInteractiveAt`.

Both retained PNGs are almost black, including the macOS title bar: decoded RGB extrema are 0–5
on an 8-bit scale, with alpha 255 throughout. The second run had already recorded
`mainMenuOverlayRemovedAt` before capture. Thus later overlay removal did not resolve the capture
problem. The images carry the `Rec. ITU-R BT.2020-1; sRGB Gamma` profile. No cause is established;
do not call these visual acceptance or attribute the appearance to prepared textures. Game content
is faintly visible, but neither rendering fidelity nor gameplay was accepted.

The harness's quit receipts say `stopped exact PID`. Its driver attempts Command-Q and has bounded
termination fallbacks; that receipt alone does not distinguish every shutdown mechanism and is not
acceptance of the GUI Stop button or an in-game Quit-menu click. Both owned games exited.

## Reproduced defect and fix

The sibling bundle filter left by #1304 accepted every name containing `fast`. An inert local
`Fastmail.app/Contents/MacOS/Fastmail` fixture therefore produced `ready: true`, score 200, even
without Starsector. With a real-shaped Starsector bundle beside it, the unrelated app outranked
the game's score 150. No fixture executable was launched.

The correction accepts Starsector-named bundles, `fr.app`, and the specific Fast Rendering spellings
with spaces, hyphens, or underscores removed. It no longer uses the bare substring `fast` for
sibling bundles. Explicit launcher overrides and script discovery are unchanged.

Two regression assertions failed before the fix: empty-folder readiness and selection beside the
real game. Positive tests retain five Fast Rendering bundle spellings. After the fix, the rebuilt
engine reports the inert fixture not ready, and the real `/Applications` snapshot still selects
`Starsector.app/Contents/MacOS/starsector_mac.sh`.

`./mvnw -pl preflight-cli -am verify` passed: core 377 tests, agent 942, CLI unit 1199, CLI integration
57; zero failures/errors, with 60 total skipped tests. This local source verification is separate
from native observations on the previously installed package and from three-platform CI.

## Preservation and remaining checks

Enabled-mod file SHA-256 stayed
`76227ce91333c202271e541774f3e86fd8711c2542d63a81cfd18a4dc0a6997f`.
Game settings were restored before the code change was verified. No campaign/save test was run.
The installed app still contains the old discovery engine until its next normal package refresh.
Resolve the dark capture/display path before accepting game visuals; Windows/Linux native checks,
campaign/combat/save lifecycle, and broader release acceptance remain separate work.
