# Native operation acceptance

Private candidate source: `9d9a38b7fb0e7928aabb65bc97669b0ae5266884` (PR #1272).
Distribution [34064351822](https://github.com/teamleaderleo/preflight/actions/runs/34064351822)
and exact-candidate lifecycle [34065668277](https://github.com/teamleaderleo/preflight/actions/runs/34065668277)
passed all three platforms. These are private update-signed rehearsal packages, not a final tagged
release. Three-platform source CI and Desktop application CI also passed.

All three installed engines matched SHA-256
`68bfe93c296a680f2d0c4fd614003c86b005aa82c71a813a4299982068859c1f`.
Native screenshots, trace summaries, settings receipts and package hashes are retained under
`benchmark-results/gui-audit-operation-reliability/` in the canonical checkouts. Browser previews,
automated concurrency fixtures, CI packaging and the native interactions below are separate evidence.

## Mac

The installed application passed native folder selection, display-resolution enumeration, Apply,
quit/reopen readback, direct game launch, GUI close while the game survived, recovered Running, and
explicit Stop. Temporary settings were 1600×900 windowed, sound off, 4 GiB and 105% UI size. Native
layouts at 1040×701, 720×560 and 2000×1100 were inspected. Game menu/settings interaction rendered
without the earlier launcher stripe corruption.

Run `20260906-230209-316-6073b7c2` recorded `USER_STOPPED`, effective/raw exit 0, and 231924 ms.
The exact PID/start receipt was retained; wrapper, launcher and game all exited. Navigation to Settings
worked immediately after Stop; an overlapping update check refused admission. The deliberately delayed
work and quit-during-write cases remain automated fixture evidence, not injected native GUI tests.

This active-Mac correctness run took 101.125125 s from `processStartedAt` to
`mainMenuInteractiveAt`. Prepared textures were active: 15502 hits, no fallback/internal errors.
Cold Janino compilation reported 228 misses and about 40.094 s inside original compilation;
transformation caching reported 49 misses. This explains a contributor, not the entire difference
from older warm results. No repeat benchmark or quiet-machine claim was made. Overlay removal was
recorded separately. The original 1440×932 windowed, sound-on, 6 GiB, 100% settings were restored.

## Linux

The installed Debian package passed the actual folder picker, Apply and reopen readback at
1600×900 windowed, sound off, 3 GiB and 105% UI size. The resolution menu included
4096×2560 (Display). It launched directly to the real menu; the moving background ship and settings
interaction rendered correctly. Closing the normally launched GNOME application left the game alive;
reopening recovered Running. The application was in `app-gnome-Preflight-2495171.scope`, not a
transient service whose cgroup would kill the game on GUI exit.

Run `20260906-232546-394-3a21bcbc` recorded `USER_STOPPED`, effective exit 0, original launcher
exit 143, and 120639 ms. Stop followed by Settings navigation worked; all owned game processes exited.
The original 2048×1280 fullscreen, sound-off, 2 GiB, 100% settings were restored and checked.
The single correctness observation reached the interactive menu in 18.077025523 s on the established
clock. This was not a new acceleration comparison or campaign.

Ten attempted rapid focus pairs on the candidate produced one refresh generation: profile list
5.567172 s, readiness 0.176230 s and cache inspection 6.070977 s, all exit 0. A later separate focus
performed another fresh generation (5.439043 / 0.150059 / 5.889757 s). These are traced child
`execve`-to-exit costs, not startup times. `candidate-burst-exec.trace` freezes the first burst;
the final filtered trace also contains the later generation. Automated tests separately cover external
mod changes, deferred focus and installation switches.

Old-package focus traces retain repeated generations and interrupted/timeout children. Ordinary
strace can distort file-heavy scan costs, so those interrupted 25–28 s observations are excluded
from successful latency claims. The filtered old trace also retains 60 s timeouts and 70–72 s
profile completions. A titlebar attempt that failed to refocus the obscured window is excluded.

Both completed hosts retained all 83 enabled mods byte-for-byte, SHA-256
`76227ce91333c202271e541774f3e86fd8711c2542d63a81cfd18a4dc0a6997f`.

## Windows blocker found by native interaction

The same candidate installed successfully in `%LOCALAPPDATA%\Programs\Preflight`; the Start Menu
shortcut and engine identity were verified. Native host SHA-256 was
`f4411d15d1930cbc4cd8d0f3b579ccd320e6a35e390e3dc0a48cfa0facf6338d`.
SSH and windowed Moonlight worked after the documented runtime GPU handover.

Actual selection of `C:\Games\Starsector` failed: Rust canonicalized the folder to a Windows
verbatim path, and the Java engine rejected the `?` at path index 2. The screenshot `windows/selected.png`
retains the failure. This blocks native Windows acceptance of this generation; CI packaging success
does not establish picker correctness. No game was launched or settings changed in this failed attempt.

The follow-up uses the existing `dunce` dependency to simplify canonical game and diagnostics paths
only when ordinary Windows spelling preserves their meaning. Canonical resolution and directory,
extension, symlink and overwrite checks remain in place. A Windows bundled-engine regression passes
an actual selected temporary directory through `desktop snapshot`, rather than only running `help`.
Native Windows re-verification is still required on corrected installed bytes.

The first follow-up CI attempt (`34067383180`) stopped package preparation because the reviewed
capability source lock still contained the previous engine source digest. The diff was reviewed and
the single digest updated with the repository's capability-review command. The failure log remains
at `benchmark-results/windows-path-contract-failure.log`.

## Corrected packages installed; native continuation blocked

PR #1273 merged as `03c1e581ccd6193c865f2ddd4b10336cbf90e878`. Desktop CI
[34067557024](https://github.com/teamleaderleo/preflight/actions/runs/34067557024) passed all three
packages, native-host checks and release contracts. The Windows bundled-engine job passed all three
tests, including selected-directory inspection and diagnostics path spelling. Its log is retained at
`benchmark-results/windows-path-ci-package.log`.

Private Distribution [34068259606](https://github.com/teamleaderleo/preflight/actions/runs/34068259606)
completed successfully on that merged source. All 33 candidate files were authenticated on download,
and platform package checksums passed. These corrected packages are installed on Mac, Linux and
Windows. Their engine JAR is byte-identical to the earlier candidate above; the native hosts changed.

| Installed host | Native executable SHA-256 |
| --- | --- |
| Mac | `6016d33f9cb6fd83fcc8bb48369d1f1f754d5fc9eea40c914419cff8d00d2570` |
| Linux | `b052e8820a80a0145207a6e814e3222a0a6b1369015ce31814a61c69f704de0e` |
| Windows | `4984e269cfaa96d2599d0be23f8aaae518f227ce3f5bdc637b6e667dccaf2729` |

The corrected Windows installer hash is
`5e9080a55bddc891f2fe0ee3f71f5bb0898cada2215366e5bd41d2fadbbef75f`; the Debian package hash is
`e465003c7bd45ac9893a4a696ce23b8f0a3788f0eba9db19c3ed936c4d0375c5`. Transferred copies matched.
Mac independent installed-package integrity verification passed. Linux returned `install ok installed`;
Windows's silent installer exited zero and installed-engine/native hashes were read back.

The first corrected Debian install was mistakenly attempted before its transfer completed and failed
with a truncated-archive error (`aa045a`). After transfer completion and matching SHA-256 checks, the
install completed (`326148`). An intervening shell-quoting attempt had no effect. These are retained
operator failures, not successful package observations.

The Mac locked before corrected native GUI inspection. Computer Use refused access; no attempt was
made to bypass the lock. Therefore the corrected Windows folder picker, settings/Apply/reopen and
recovered Stop are still **unverified natively**. Earlier Mac/Linux native passes above retain their
original package identities; installation of corrected bytes is not substituted for new interaction.
Issues #1260 and #1263 remain open for that continuation. #1261 is closed with the measured native
focus evidence and automated state-change coverage. Duplicate Linux shortcut naming is tracked in
#1274.

At the pause, all owned test game/GUI, Moonlight and keep-awake processes were closed. Windows was
shut down through the guest agent and verified `shut off`. The shared GPU was rebound to `i915`, GDM
started, and SSH verified. Persistent boot configuration was unchanged. Native RDP interaction after
this final handback remains pending the Mac unlock; service state is not a screenshot/interaction claim.

## Other retained limits

The first Mac integrity verification expected a pruned local source-build JAR and failed. Independent
installed-package verification and comparison with the authenticated candidate engine then passed;
the failed source-bound attempt is retained. Early synthetic Mac click/keyboard attempts and a hung
recursive accessibility enumeration are excluded operator-input attempts, not successful interactions.

Linux has both the packaged GUI shortcut and an older CLI integration named Preflight. The latter
was preserved because it is a user-owned integration; shortcut naming is a separate usability follow-up.
Profile recovery inspection still walks the game tree and costs about five seconds on this installation.
The focus fix reduces duplicate work without weakening recovery or content validation.
