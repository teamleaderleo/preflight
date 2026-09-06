# Desktop operation reliability

Source follow-up for #1260, #1261, and #1263. Native package acceptance remains separate from the automated results below.

## Source contracts

- Repeated focus events share one in-flight profile/readiness/cache refresh generation. A later focus performs fresh validation; no timestamp-only trust or persistent validation cache was added. Focus work during launch, preparation, recovery, or an owned game is deferred and drained once afterward. Installation changes retire stale profile and cache responses.
- Foreground native commands reserve admission and release the coordinator mutex before child work. Conflicting Launch, Apply, Stop, cleanup, profile, and update operations refuse promptly. Quit waits for admitted writes to finish; reservation disposal also handles error/unwind paths. Launch rechecks closing state before spawning the game.
- Cache repair uses the mutation budget and is not killed with read-only inspection helpers during quit. Ordinary health inspection retains its read budget and cancellation behavior.
- Desktop Stop supplies `--user-requested`. The CLI atomically writes `user-stop.requested` with the exact recorded PID/start pair before signaling that process. Run classification accepts the receipt only against the strict runtime identity. Expected termination exits (Windows 1; Unix 137/143; or zero) become `USER_STOPPED` when no fatal evidence is present. The original launcher exit remains in the report. Unexpected nonzero exits and fatal evidence remain failures.
- Playtime includes `USER_STOPPED` without changing duration accounting. Ordinary controller/benchmark Stop calls do not gain user-stop classification implicitly. The `processStartedAt → mainMenuInteractiveAt` measurement clock is unchanged. The original failed Windows observation in #1263 is retained.

## Automated evidence

- Focused profile/refocus tests passed, including a 20-event burst, a subsequent external change, game-active deferral, and an installation switch.
- All 506 frontend tests in 86 files passed serially on Node 24.19.0. Log: `benchmark-results/operation-frontend-serial.log`; command receipt `3cf5c2f52fe1ee9f`.
- Production frontend build and 156 release-contract tests passed (`2a3890d0ea6b8c70`).
- Java 17 focused unit checks passed. The latest classification rerun passed 12 lifecycle-evidence tests plus 15 integration tests: the packaged CLI Stop test and existing RunCommand integration suite (`e52cb2804fd9fce1`). The Stop fixture owns a synthetic JVM, verifies the durable user-stop receipt, and cleans up both target and CLI processes.
- 97 native-host tests passed. Reservation tests hold work on a separate thread, verify the mutex remains available, refuse conflicting admission, exercise deferred quit, and release on unwind. These are automated concurrency checks, not a native GUI interaction claim.
- Native clippy passed with warnings denied and build parallelism capped at two (`6abde6c991d2bcda`). Three-platform CI is required before source integration.

## Failed and interrupted attempts

The first native test run failed because an older fixture treated an already-requested app exit as a harmless neighbor to cache repair (`9c846293be80fe6d`). The contract now explicitly refuses a new repair during exit while retaining the ordinary report-upload neighbor case.

A frontend build caught an extra argument accidentally added to the adjacent setup-check call (`40432ef085f9a68f`); that call was corrected before the successful build. A parallel full frontend run failed while local native/Java compilation was active and was interrupted (`9387b6d097b06e18`). A diagnostic rerun was also stopped to narrow the check (`dcad2d2d88227d87`). The Ready test passed alone (`4b1b6d5f5565b733`), and the unchanged frontend then passed the full serial run. This is evidence of load-sensitive test failures, not proof of a particular host-level cause. Diagnostic logs remain under `benchmark-results/operation-frontend-verbose.log` and `operation-ready-failure.log`.

An initial clippy dependency rebuild was stopped to reduce local load (`58ec2f4d351f4a9e`), then completed with two build workers. Failed/excluded receipts are retained.

## Native acceptance still to record

Use one identified package generation for Mac, Linux, and Windows. Record package and installed-engine hashes, settings/readback/reopen, installation selection, launch, close/recover/Stop, final run classification/playtime, process cleanup, and focus-triggered native read counts/costs. Keep browser previews, automated tests, CI, and native interaction distinct. Preserve game settings and mod selections.

The release board #652 still owns the separate final source/tag and publication decisions. Private package rehearsals do not constitute final tagged release acceptance.
