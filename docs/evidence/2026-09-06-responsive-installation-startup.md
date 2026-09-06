# Responsive installation startup, 2026-09-06

Issue #1255 follows the observed Mac window remaining on “Finding Starsector…” and becoming
unresponsive when reopened. This work concerns Preflight's own startup, not Starsector startup
benchmarks. `processStartedAt → mainMenuInteractiveAt`, game settings and all 83 mods are unchanged.

## Diagnosis

Three sequential read-only invocations of the previously installed Mac engine, with no test game
running, took 0.660 s for `desktop snapshot --game /Applications/Starsector.app`, 0.257 s for
`stop --dry-run --json`, and 5.923 s for `desktop home-state`. These are single local observations,
not medians or a controlled performance campaign. Raw responses remain in
`benchmark-results/finding-startup/`.

The native synchronous command handlers waited for child processes on the invoking UI thread.
Consequently a background Home-data request could prevent the already-discovered Home screen
from painting or responding. The installation path was already remembered; repeatedly searching
the disk was not the explanation for the whole observed delay.

## Change

Native inspection commands use Tauri's asynchronous command dispatch. The initial discovery
request returns the small installation snapshot, while Home's four data consumers still share
one deferred home-state request. Concurrent discovery calls remain deduplicated. No cached
installation is treated as valid without checking it, and no prepared-data validation is bypassed.

Read-only engine children are registered before shutdown can race their creation. Quit cancels
those exact children and refuses late reads. Ordinary game children and mutation requests are
not in that registry. The existing timeout/kill behavior remains bounded; shutdown does not
wait for child pipe I/O on the UI thread.

## Automated verification

All 110 native tests passed locally. The new cancellation regression checks an active read,
a late request after cancellation, and survival of a separate ordinary child. Focused frontend
tests passed (88), including completion of discovery while Home data is unresolved, shared reads,
and stale-installation request ordering. All 496 frontend tests and the production TypeScript/Vite
build passed. Package and native-window results are recorded below.

The initial engine preparation correctly refused changed capability-boundary source before
review. The process, argument, read/mutation and shutdown boundaries were reviewed, and their
source-lock digests were regenerated with the repository's review script. The failed receipt is
retained alongside successful verification output.

## Packaged native macOS verification

The installed package was built from source `f0032882`. Its DMG SHA-256 was
`b4983480b209ecc50d12f6359a78df685d7d85bbd9cfc7ba5e7d7cf8499ec847`.
Package verification, installed-engine verification, and native host boot passed, with
109 runtime files / 51,010,342 bytes. This was an unsigned local development package.

Actual native interaction passed: Help and Home responded after opening, Home read back
`/Applications/Starsector.app`, 1440×932 windowed, sound enabled, 6 GiB memory,
100% UI size, battle size 400, and antialiasing off. These values were not changed.
One game was launched, Preflight was quit, and the game and its launch monitor survived.
Reopening Preflight allowed Help/Home navigation and recovered Running with Stop available.
Stop returned Home to Ready with the installation still selected and a completed-run banner.
The game and monitor exited. A further immediate Quit from Finding Starsector exited cleanly;
the subsequent process check found no Preflight GUI or Java process.

Run `20260906-175645-922-e9f82eb4` retained its completed receipt and elapsed playtime
of 64,576 ms, exit code 0, and no postprocessing failures. This is session duration,
not a game startup measurement. No game benchmark was repeated. Screenshot checks were
native app-only visual evidence, not browser previews or quantitative rendering-fidelity tests.
Receipts, app-only screenshots and raw logs remain under `benchmark-results/finding-startup/`.

The final native test rerun passed all 110 tests. One dummy timeout-test descendant was found
and explicitly stopped after the test suite. After verification, the repository pruning tool
removed 4.6 GiB of disposable local output; two obsolete app backup copies were also removed.
The verified installed app and evidence were retained. No local test game, GUI, or development
server was left running by this task.

Windows and Linux native interaction with this new package generation is not claimed here.

PR #1256 merged as `b5526b08` after all checks passed, including Windows, Linux and macOS
package/installation CI in run 34049970632. Big Red was rechecked over SSH: VM shut off,
i915 bound to the shared GPU, and GDM active. Its runtime configuration was not changed.
