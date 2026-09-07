# Startup verification and macOS package cleanup

Correctness checks on September 6–7, 2026; these are not a benchmark campaign.
Base: `f75dddfd67e16966f31f93627161316bbb32814d`. Candidate changes:
`7de79d68` (JSON cache stack maps), `32a85664` (audio exception local), and
`de360bbc` (native macOS package cleanup).

## Fixed defects

- Weapon/projectile cache transformations previously recomputed unrelated game
  stack maps with an application-hierarchy fallback to `Object`. Cache branching
  now lives in synthetic helpers; original game frames survive composition.
  A synthetic Base/Child join regression fails with the previous plans with
  `VerifyError: Bad type on operand stack`, and passes with either new plan,
  sequential composition, and shared-tree composition.
- The streaming audio source transformation removed an initial local assignment
  needed by the original OpenAL exception handler. Retaining that assignment
  fixes the observed Mac `sound/oo0O` constructor verification failure. The
  regression fixture exercises the throwing source-generation path; the old
  plan fails verification, and all seven focused tests pass with the fix.
- macOS installation rehearsal now retries only busy (`16`) detach results,
  with three bounded delays and a ten-second timeout per attempt. It preserves
  an original exercise failure alongside cleanup errors, cleans independent
  copies, and never recursively traverses a mount whose detach failed.
  Four new cleanup tests and three existing install tests pass.

Full Java verification passed on Big Red after both agent fixes (36.315 seconds,
local command receipt `a77136ffc0c1e499`). The capability source lock still matches
all 32 guarded files. A broader Mac script-test invocation without preparing an
engine yielded 155 passes and five missing-artifact errors; that attempt is
retained, not counted as a complete release-contract pass.

## Native results and remaining FastRendering gate (#1269)

Pinned port: `jontyab/starsector-render`, `v0.8.7-port-6a226999`; archive identities
remain in `docs/fast-rendering-port-lock.json`. Verification was explicitly
enabled for the port controls. Linux used its bundled Zulu 27+47; only its two
rejected JVM flags (`AlwaysAtomicAccesses`, `UseVectorStubs`) were removed, and
the staged process helper's executable permission was restored.

Candidate CLI SHA-256 after the audio fix:
`71625e77750c6b021514e4c27e56e3c41c2bc7eda4ca8bf06f7bcc6eb8d1ad1a`.
The earlier JSON-only candidate was
`1f9a18870746986ad1468e2e855e85ec2d23d5a1676257e99941049b4b591051`.
These were disposable candidate engines, not newly installed GUI packages.

Linux passed the previous weapon verification failure, recording 3,077 weapon
and 1,263 projectile hits with zero contained failures, then failed in the port
script loader with missing `data/scripts/models/BaseFactionTimelineEvent`.
The same failure occurred with both JSON caches disabled and with **all
Preflight optimizations off**. All 83 Linux mods remained enabled; enabled-mod
file SHA-256: `76227ce91333c202271e541774f3e86fd8711c2542d63a81cfd18a4dc0a6997f`.
This establishes that the remaining Linux failure does not require Preflight
optimizations; it does not establish its underlying dependency or loader cause.

Mac first exposed the audio verifier defect. After that fix, the port reached
the same missing-class failure. A separate attempt forcing verification on the
ordinary stock Mac launcher failed before transformation on the game's
obfuscated `for.Object` field name; it is an excluded control, not a new
Preflight regression. The original stock launcher flags were not changed.

With the original Mac launcher and recommended Preflight preset, run
`20260907-023852-851-6af8b76e` reached the visibly rendered main menu, opened the
game's Exit confirmation, and exited with status zero after Yes. This is native
ordinary-renderer evidence, not verification-enabled port acceptance. The port
bridge remains independently opt-in; full combined-preset acceptance is open.

## Linux fullscreen investigation (#1251)

The actual game JVM reports `startFS=true`, game fullscreen true,
`Display.isFullscreen()=true`, NETWM support true, and LWJGL window mode 2.
Nevertheless, the 1920×1200 X11 client lacks `_NET_WM_STATE_FULLSCREEN` and has
a 74-pixel top frame. Its minimum and maximum hints both specify 1920×1200;
allowed window actions exclude fullscreen/maximize/resize. The remote GNOME
desktop is 4096×2560. An explicit window-manager fullscreen request also did
not remove the frame.

A compositor constraint on the fixed-size client is a hypothesis, not a proven
root cause. A temporary 4096×2560 control reached its watchdog before the live
state/screenshot could be captured; its later desktop screenshot cannot prove
fullscreen behavior. No rendering workaround or system display change was made.

## Retention and cleanup

Raw logs, negative regression controls, staging/cleanup hash receipts, JVM/X11
probe output, and screenshots are retained under `benchmark-results/weapon-frames/`
on the canonical Mac and Big Red checkouts. Relevant logs include
`native-linux{,-cache-control,-port-only}.log`, `native-mac-audio-fixed.log`,
`native-mac-normal.log`, and `native-linux-display-native.log`, with paired game
logs where captured. Native Mac menu/exit screenshots are retained separately
from automated tests. No browser preview or Windows native run was performed
for this slice.

Owned game runs stopped; temporary game scripts, renderer JARs, and Linux port
runtime were removed after checking their recorded hashes. Saved game settings
and mods were preserved. The Windows VM was not started, and persistent GPU
boot configuration was not changed. Existing startup clocks and older failed
or excluded observations remain unchanged.
