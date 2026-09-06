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
build passed. Package and native-window results will be recorded after their sequential checks.

The initial engine preparation correctly refused changed capability-boundary source before
review. The process, argument, read/mutation and shutdown boundaries were reviewed, and their
source-lock digests were regenerated with the repository's review script. The failed receipt is
retained alongside successful verification output.
