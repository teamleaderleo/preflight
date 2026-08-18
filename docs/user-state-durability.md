# User-state durability inventory

This is the first slice of #584: an evidence inventory of mutable, user-relevant state that Preflight owns or whose mutation Preflight coordinates. It records the behavior present on `main` at `8140f5972876cc1e1b2095c263ea7fbd4e2fd4be`.

This change intentionally does not rewrite persistence code. In particular, `ProfileCommand` is evidence-only here while #649 is active, and Home/App/renderer persistence is evidence-only while #651 is active. Cache durability remains owned by #330. This document also does not implement #575, #579, #583, or #560.

## Classification vocabulary

A store can have more than one classification.

- **authoritative** — the surviving value is relied on for durable user intent, an externally meaningful authorization, a safety decision, or a product claim. Losing or accepting the wrong value can change what Preflight says or does.
- **reconstructible** — Preflight can rebuild the useful state from another retained source with defined enough semantics to avoid inventing history.
- **best-effort** — the owning workflow is deliberately allowed to continue when this store cannot be published. This describes the current writer contract, not a recommendation for every future consumer.
- **secret-bearing** — the record contains a credential or bearer authority.
- **disposable** — deletion/expiry/pruning is an intended lifecycle and the product can continue without the object.

“Commit point” below means the point after which the current code treats publication as complete. It is not a claim of power-loss durability. Unless a row explicitly says otherwise, the Java file writers do not call `FileChannel.force`, `fsync`, or a directory sync, and the renderer has no stronger primitive than a successful Web Storage call.

## Publication primitives in current code

These recurring mechanisms make the row-level failure descriptions shorter:

| Primitive | Current semantics |
| --- | --- |
| **direct write** | `Files.write*` publishes directly to the final path. A failure can leave the previous object truncated/replaced or a newly created partial object, depending on open options and filesystem behavior. Successful return has no explicit file/directory sync in the callers inventoried here. |
| **sibling temp + move** | Create/write a file in the destination directory, then attempt `ATOMIC_MOVE + REPLACE_EXISTING`; on `AtomicMoveNotSupportedException`, retry with ordinary `REPLACE_EXISTING`. The commit point is successful `Files.move`. Without an explicit sync, the repo does not prove crash durability of file data or directory entry. The fallback move has a wider interruption window than the atomic path. |
| **append** | Append a JSON line directly to the ledger. A torn/short tail can exist. The ledger reader is line-isolated, so one malformed line does not poison surrounding rows. |
| **localStorage** | The commit point is successful `Storage.setItem`/`removeItem`. There is no application-level temp object, version journal, checksum, or flush primitive. A thrown call leaves the prior browser value as the only restart source; hook ordering determines whether current-session state has already advanced. |
| **Java Preferences** | Preflight calls `Preferences.put/remove` and then `Preferences.flush()`. `flush()` returning is the current commit point exposed to Preflight; backing implementation details are platform/JDK-owned. |

## Top-level inventory

| Store | Classification | Owner | Location | Product dependency |
| --- | --- | --- | --- | --- |
| Named profiles | authoritative | `ProfileCommand` | `~/.starsector-preflight/profiles/*.json` | Saved mod-set identity and activation input. |
| Profile activation review receipts | authoritative, disposable | `ProfileCommand` | `state/profile-activation-reviews/<sha256>.json` | A matching fresh review is required before confirmed activation. |
| Profile recovery backups | authoritative, disposable | `ProfileCommand` | `profile-backups/enabled_mods-*.json`, `profile-backups/deleted-profile-*.json` | Exact old bytes for activation/delete recovery. |
| Integration ownership receipt | reconstructible, best-effort | `PreflightHome` | `integrations.json` | Helps rediscover Preflight-created launcher integrations; ownership can also be derived by current integration checks. |
| Launch-preference recovery backups | authoritative, disposable | `LaunchSettingsCommand` | `launcher-preference-backups/*.json` | Recovery copy made before changing Starsector launcher preferences. |
| Launcher-file recovery backups | authoritative, disposable | `JvmMemorySettings` | `launcher-file-backups/*` | Exact old launcher/vmparams bytes made before heap-setting publication. |
| Operation owner description | reconstructible, best-effort, disposable | `OperationLease` | `state/operation.json` plus `state/operation.lock` | JSON explains a held/recovered operation; OS file lock is the concurrency authority. |
| Incomplete-removal marker | authoritative, disposable | `UninstallCommand` / `OperationLease` | `state/removal.pending` | Existence blocks normal operations after an interrupted all-data purge. |
| Launch/playtime ledger | authoritative, reconstructible, best-effort | `LaunchLedger` / `LaunchLedgerBackfill` | `history/launches.jsonl` | Launch counts and playtime shown by desktop bootstrap. |
| Historical-backfill marker | reconstructible, disposable | `LaunchLedgerBackfill` | `history/backfilled-from-runs` | Skips a full historical scan; launch-ID dedupe remains the correctness guard if the marker is lost. |
| Active-run heartbeat | reconstructible, best-effort, disposable | `LaunchHeartbeat` | `runs/<run>/heartbeat.json` | Recovery witness for an interrupted launch before/after ledger publication. |
| Run metadata | authoritative, best-effort, disposable | `RunCommand` / `DesktopBridgeCommand` | `runs/<run>/run.json` | Latest-run outcome/recovery UI and historical backfill input. |
| Runtime semantic state | authoritative, best-effort, disposable | `RuntimeSemanticState` | `runs/<run>/runtime-state.json` | Startup timing/readiness evidence for desktop claims and smoke/benchmark evidence. |
| Runtime process identity | best-effort, disposable | `RuntimeProcessReport` | `runs/<run>/runtime-process.json` | PID-addressed smoke/evidence coordination. |
| Adapter health projection | authoritative, best-effort, disposable | `AdapterHealthReport` | `runs/<run>/adapter-health.json` | Latest-run compatibility/acceleration status shown by the desktop. |
| Save observation ledger | authoritative, best-effort | `SaveProfileObservation.Ledger` | `runtime/save-observations-v1.json` | Durable observation history used to associate save behavior with profile identity. |
| Run/benchmark evidence session directories | best-effort, disposable | run/benchmark producers, `EvidenceRetention` | `runs/<session>/`, `benchmarks/<session>/` | Evidence for support and performance proof; explicitly prunable after snapshot validation. |
| Renderer-owned Web Storage keys | mixed; detailed below | `desktopStorage.ts` + owning hooks | WebView `localStorage` | Preferences, product claims, consent/dedupe, receipt credentials, and startup hints. |

## Disk-backed stores

### Named profiles

- **Owner / path:** `ProfileCommand`; `PreflightHome.profiles()` → `profiles/*.json`.
- **Reader:** `loadProfiles` / `readProfile`; named profile activation/mutation also re-reads the selected profile.
- **Writer:** save/rename paths call `ProfileCommand.atomicWrite`.
- **Validation:** format `starsector-preflight-profile-v1`; validated profile name, install root, saved timestamp, enabled-mod list, and 64-hex profile fingerprint. The fingerprint identifies profile content/source state; it is not a checksum over the profile JSON file itself.
- **Publication / commit:** sibling temp created in the profile directory, `Files.writeString`, then `ATOMIC_MOVE + REPLACE_EXISTING` with ordinary replace fallback. Successful move is the current commit point.
- **Temp creation failure:** mutation fails before the destination is changed.
- **Partial/short temp write:** mutation fails if surfaced by the write; old destination remains the restart source. A crash can leave an unreferenced `.preflight-profile-*` temp.
- **Flush/fsync:** none.
- **Move failure:** save/rename reports failure; old destination remains when replacement has not committed. Rename has a second phase: target is committed first, then source is deleted; source-delete failure triggers an attempted target cleanup and can leave both names if that cleanup also fails.
- **Cleanup failure:** `finally Files.deleteIfExists(staged)` can surface cleanup failure. Stale temp files are ignored by profile enumeration because they do not use the named-profile filename contract.
- **Restart with old/new/temp/corrupt:** the named destination is the reader authority. Stray temps are ignored. A corrupt named profile produces a diagnostic/failed load rather than a rollback to a stale temp or backup.
- **In-memory-before-publish:** profile mutation/activation does not treat a newly saved profile as committed until the file mutation returns. Profile activation itself is a separate external mutation described below.
- **Coverage:** `ProfileCommandTest` covers named-profile behavior, expected fingerprints, activation review/change checks, backup/activation semantics, and safety cases; #649 is actively extending this area.
- **Missing deterministic faults:** temp-create failure, short temp write, atomic-move failure, fallback-move failure, post-move cleanup failure, rename source-delete + target-cleanup double failure, and restart with a corrupt named destination plus a valid stale temp/backup.

### Profile activation review receipts

- **Owner / path:** `ProfileCommand`; `state/profile-activation-reviews/<sha256(caller, install root, profile name)>.json`.
- **Reader / writer:** `readActivationReview` / `writeActivationReview`; deletion through `deleteActivationReview`; retention through `pruneActivationReviews`.
- **Validation:** format `starsector-preflight-profile-activation-review-v1`, exact profile name/install root, 64-hex profile fingerprint, 64-hex source-state SHA-256, valid `reviewedAt`, caller-derived filename, and maximum age of 30 minutes.
- **Publication / commit:** same sibling-temp + move helper as named profiles. Successful move is the commit point.
- **Temp/write/move/cleanup:** same current behavior as named-profile `atomicWrite`.
- **Flush/fsync:** none.
- **Restart coexistence:** only the derived final filename is read. Missing/corrupt/stale receipt forces another review; stale temps do not authorize activation.
- **In-memory-before-publish:** confirmed activation requires a persisted receipt that re-validates against current profile/source state, so the external activation does not proceed from a merely in-memory review.
- **Coverage:** profile tests cover missing/stale/changed review state and caller/session binding.
- **Missing deterministic faults:** publication primitive failures and cleanup/retention failure after a receipt has committed.

### Profile recovery backups

- **Owner / path:** `ProfileCommand`; `profile-backups/`.
- **Readers:** primarily recovery/support inspection; normal activation does not silently roll back from these files.
- **Writers:** activation `backup(home, original)` and deletion `backupProfile`.
- **Validation:** activation backup is exact `enabled_mods.json` bytes with no wrapper schema/checksum; deleted-profile backup copies the already validated profile JSON. Filename patterns and bounded retention define managed artifacts.
- **Publication / commit:** `Files.createTempFile` is used with the *final backup namespace*, then `Files.write`/`Files.copy` fills that same inode. The successful write/copy is the practical commit point; there is no final rename.
- **Temp creation failure:** profile activation/delete fails before destructive publication of the corresponding external/source deletion.
- **Partial/short write:** can leave a partially filled file under a name that looks like a completed backup if the write/copy fails after creation. The caller receives failure and keeps the source state, but cleanup of the incomplete backup is not part of the helper.
- **Flush/fsync:** none.
- **Replacement/move:** none.
- **Cleanup failure:** retention failure can fail the operation after a backup file was created; the caller still has the old source state at that point.
- **Restart coexistence:** every matching backup filename can appear as a candidate artifact; there is no embedded checksum that distinguishes a fully copied backup from a short one.
- **In-memory-before-publish:** destructive profile activation/delete starts only after backup helper success.
- **Coverage:** profile tests cover backup creation/retention and activation rollback paths.
- **Missing deterministic faults:** create/write/copy short failure leaving a final-looking backup, retention failure after complete backup, and deterministic verification of backup completeness before later recovery use.

### Integration ownership receipt

- **Owner / path:** `PreflightHome`; `~/.starsector-preflight/integrations.json`.
- **Reader / writer:** `PreflightHome.resolve` / `recordInstalledIntegrations`.
- **Validation:** format `preflight-integrations-v1`, bounded known integration records; current integration ownership checks still validate actual paths/content before destructive removal.
- **Publication / commit:** sibling `.tmp-<pid>-<nano>` + atomic replace with normal replace fallback. Successful move is the commit point.
- **Temp creation/write/move/cleanup:** an `IOException` is deliberately swallowed by the outer best-effort receipt writer; a stale temp can remain if cleanup itself fails.
- **Flush/fsync:** none.
- **Restart coexistence:** final valid receipt is consumed; corrupt/missing receipt is ignored and conventional integration discovery/ownership checks remain available. Stale temp is ignored.
- **In-memory-before-publish:** installation can complete even when this receipt cannot be written.
- **Coverage:** installation/uninstall ownership tests cover recognized/unrecognized integration behavior; #596 tracks stronger external-integration ownership evidence.
- **Missing deterministic faults:** temp/create/write/move/cleanup failures and restart with old receipt + newer orphan temp.

### Launch-preference recovery backups

- **Owner / path:** `LaunchSettingsCommand`; `launcher-preference-backups/<timestamp>-<uuid>.json`.
- **Reader / writer:** recovery artifact reader is manual/support-oriented; writer is `writeBackup` before `GameLaunchPreferences.apply`.
- **Validation:** wrapper format `starsector-preflight-launch-settings-backup-v1`; raw values intentionally contain only Preflight-mutated launcher keys and exclude registration serial/unrelated preferences. No checksum.
- **Publication / commit:** direct `Files.writeString(... CREATE_NEW, WRITE)` to the final backup filename. Successful write is the commit point.
- **Temp creation:** no temp.
- **Partial/short write:** a final-looking truncated JSON backup can remain if write fails after creation; external launcher preferences have not begun changing yet, because backup success is a precondition.
- **Flush/fsync:** none.
- **Replacement/move:** none.
- **Cleanup failure:** retention failure propagates before preference mutation.
- **Restart coexistence:** matching backup files remain independent recovery artifacts; corrupt ones are not automatically substituted into launcher preferences.
- **In-memory-before-publish:** preference mutation starts only after the backup writer returns.
- **Coverage:** `GameLaunchPreferencesTest` plus launch-settings command tests exercise typed parsing, validation, backup/restore, and rollback behavior.
- **Missing deterministic faults:** short final backup write, retention failure after completed backup, and a deterministic proof that no preference key is touched when backup publication fails.

### Launcher-file recovery backups and heap-setting publication

- **Owner / path:** `JvmMemorySettings`; backup under `launcher-file-backups/`; mutation target is the selected game-owned launcher/vmparams file.
- **Reader / writer:** `inspect`/`readStableSource`; `update`, `writeBackup`, `publishIfUnchanged`.
- **Validation:** selected source must resolve inside the installation, be a regular writable file, stay byte/attribute-stable across review, contain one usable `-Xmx` and at most one `-Xms`, and remain within 512 KiB. The backup is exact original bytes with no wrapper/checksum.
- **Backup publication / commit:** direct `Files.write(... CREATE_NEW, WRITE)` to the final backup filename, then bounded retention. Successful write+retention is the precondition for external mutation.
- **External publication / commit:** same-directory `.preflight-heap-*.tmp`, replacement bytes, metadata/permission preservation where available, final source-stability check, then atomic replace with ordinary replace fallback. Successful move is the external commit point. The code immediately re-reads the published bytes and semantic heap setting.
- **Temp/create/write failures:** fail before external replacement; if publication never happened, the newly created backup is deleted on the failure path when possible.
- **Partial/short write:** a bad staged file never becomes accepted without a successful move; post-move exact-byte verification catches a mismatched publication.
- **Flush/fsync:** none.
- **Move failure:** update fails. If publication had committed and a later check fails, rollback republishes original bytes only if the just-published source is still unchanged.
- **Cleanup failure:** temp cleanup or backup cleanup can be suppressed onto the primary failure; recovery backup is intentionally retained when rollback proof is uncertain.
- **Restart coexistence:** the game-owned source file is authority; orphan `.preflight-heap-*` temps are not read. Backups remain explicit recovery artifacts.
- **In-memory-before-publish:** caller reports a changed heap only after publication and reinspection succeed.
- **Coverage:** `JvmMemorySettingsTest` includes containment and deterministic before/after-publication external-edit race hooks. This is the strongest current deterministic publication seam in the inventory.
- **Missing deterministic faults:** create/write/atomic-move/fallback-move/fsync-equivalent crash cases and cleanup failure as an independently injected event.

### Operation owner description and lock

- **Owner / path:** `OperationLease`; `state/operation.lock` and `state/operation.json`.
- **Reader / writer:** `acquire`/`readOwner`; `writeOwner`; `close` deletes matching owner JSON and releases OS lock.
- **Validation:** JSON format `preflight-operation-v1`, operation name, PID, start time, optional install root, UUID-like token fields required by parser. Corrupt metadata is treated as absent.
- **Publication / commit:** correctness commits at successful OS file-lock acquisition. Owner JSON uses sibling temp + move; if JSON publication fails, acquisition unwinds and releases the lock.
- **Temp/write/move/cleanup:** owner JSON mutation fails the acquisition; stale PID-tagged temps are eligible for best-effort cleanup by the next recovered owner.
- **Flush/fsync:** no explicit sync for JSON; file lock lifecycle is OS-owned.
- **Restart coexistence:** a stale valid `operation.json` with no live OS lock is descriptive recovery information, not a blocker. A corrupt/missing JSON still allows lock-based admission. Next owner can remove interrupted PID-tagged temp files under the Preflight root.
- **In-memory-before-publish:** protected operation begins only after lock and current owner JSON publication return successfully.
- **Coverage:** `OperationLeaseTest` covers mutual exclusion, stale owner recovery, ownership/token behavior, symlink/removal safeguards, and temp recovery.
- **Missing deterministic faults:** owner temp create/write/move failure and owner-delete failure combined with immediate reacquisition.

### Incomplete-removal marker

- **Owner / path:** `UninstallCommand.markRemoval`; reader in `OperationLease.refuseIncompleteRemoval`; `state/removal.pending`.
- **Validation:** reader intentionally uses existence only; content is human-readable advisory text and has no schema.
- **Publication / commit:** direct `Files.writeString(CREATE, TRUNCATE_EXISTING)` to the final path. Successful call is the current commit point and occurs before all-data root deletion begins.
- **Temp creation:** none.
- **Partial/short write:** even a partial file still satisfies the existence gate after restart; a failure that leaves no directory entry prevents purge from starting because `markRemoval` propagates.
- **Flush/fsync:** none.
- **Replacement/move:** none.
- **Cleanup failure:** marker disappears as part of successful state/root deletion; an interrupted purge intentionally leaves it to block normal operations until purge is retried.
- **Restart coexistence:** any marker file blocks non-purge operations; contents are not trusted for authorization.
- **In-memory-before-publish:** destructive all-data removal starts only after marker write returns.
- **Coverage:** `UninstallCommandTest` covers interrupted-removal refusal/retry behavior.
- **Missing deterministic faults:** create/truncate/write failure before deletion and crash/power-loss immediately after successful write but before directory entry durability.

### Launch/playtime ledger

- **Owner / path:** `LaunchLedger`; `history/launches.jsonl`. Historical reconstruction is `LaunchLedgerBackfill`.
- **Reader / writer:** `LaunchLedger.read` / `LaunchLedger.record`; desktop bootstrap calls backfill then computes `Playtime` from the ledger.
- **Validation:** one strict JSON object per line, format `starsector-preflight-launch-ledger-v1`, typed launch identity/timestamps/outcome/duration fields. Malformed or foreign-format lines are skipped independently. Maximum retained rows: 10,000.
- **Publication / commit:** normal record is direct append; successful append is the current commit point. Trimming writes the retained lines to a sibling temp and replaces the ledger using atomic move with ordinary replace fallback.
- **Temp creation failure:** relevant only to trimming; record returns a diagnostic string and the completed launch remains successful.
- **Partial/short write:** a torn appended line can remain. Reader isolation discards that row while keeping surrounding launches. A failed trim leaves the previous ledger when replacement did not commit.
- **Flush/fsync:** none.
- **Move failure:** trim/record reports a bookkeeping problem to the caller. Launching is explicitly independent from ledger success.
- **Cleanup failure:** trim cleanup is attempted; a stale temp is not a ledger reader input.
- **Restart coexistence:** final `launches.jsonl` is read. Run directories/heartbeats can backfill launches missing from it; launch identity dedupe prevents duplicate imported hours if the historical marker is lost.
- **In-memory-before-publish:** the game launch has already happened before final ledger recording. A launch can therefore be real while its playtime/history row is absent.
- **Coverage:** `LaunchLedgerTest` covers concurrency, malformed/torn-line isolation, bounded trimming, unwritable history, symlink refusal, and time-span semantics. `LaunchLedgerBackfillTest` covers exact-once imports, lost marker, corrupt runs, interrupted heartbeat recovery, active-owner refusal, simultaneous backfill, and source containment.
- **Missing deterministic faults:** short append/ENOSPC after partial bytes, trim temp-create/write/atomic-move/fallback-move/cleanup failures, and crash durability around append/rename.

### Historical-backfill marker

- **Owner / path:** `LaunchLedgerBackfill`; `history/backfilled-from-runs`.
- **Reader / writer:** `runOnce`; direct marker write after import pass.
- **Validation:** marker existence/type and containment; content is not the correctness authority.
- **Publication / commit:** direct final-path write; successful return is the marker commit point.
- **Failure behavior:** missing/unwritable marker causes later scans, but launch-ID dedupe is designed to prevent double-counting. A symlinked marker is refused.
- **Flush/fsync:** none.
- **Restart coexistence:** a lost marker triggers reconstruction; duplicate ledger identities remain suppressed.
- **In-memory-before-publish:** imported rows can commit before marker publication. This ordering is deliberate and tested.
- **Coverage:** lost-marker and symlink-marker tests prove the marker is an optimization, not the exact-once authority.
- **Missing deterministic faults:** partial marker write and marker creation failure after successful import, although semantics should remain a repeat scan.

### Active-run heartbeat

- **Owner / path:** `LaunchHeartbeat`; `runs/<run>/heartbeat.json` with fixed sibling `heartbeat.json.tmp`.
- **Reader / writer:** `LaunchHeartbeat.read`; `start`/periodic writer; `complete` removes target and temp after ledger handling.
- **Validation:** format `starsector-preflight-run-heartbeat-v2`, regular file/size bound, launch UUID, owner PID/start identity, timestamps, elapsed bounds, optional profile fingerprint.
- **Publication / commit:** write fixed sibling temp then atomic replace with ordinary replace fallback. Successful move is the current heartbeat commit point.
- **Temp creation/write failure:** periodic heartbeat publication is swallowed; the game continues.
- **Partial/short write:** malformed temp is ignored unless moved; stale final heartbeat remains the restart source. The helper currently lacks a `finally` cleanup around every write failure, so `heartbeat.json.tmp` can survive.
- **Flush/fsync:** none.
- **Move failure:** swallowed by periodic best-effort update; prior heartbeat remains.
- **Cleanup failure:** `complete` cleanup is best-effort; stale heartbeat is guarded by launch/process identity during backfill.
- **Restart coexistence:** final validated heartbeat can reconstruct interrupted elapsed time. Temp is not read as authority. Completed `run.json`/ledger identity can prevent double import.
- **In-memory-before-publish:** the running game always proceeds independently of heartbeat success.
- **Coverage:** backfill tests cover interrupted recovery, active-owner refusal, identity mismatch, and exact-once deletion/import behavior.
- **Missing deterministic faults:** write/move/cleanup injection and stale-old-heartbeat plus newer temp restart combinations.

### Run metadata (`run.json`)

- **Owner / path:** `RunCommand`; `runs/<run>/run.json`. Reader for product state: `DesktopBridgeCommand.lastRun/runSummary`; backfill also consumes run metadata.
- **Validation:** desktop reader bounds file size to 256 KiB, parses strict JSON, validates important timestamps/PID/install-root/profile-fingerprint fields, and ignores unreadable/malformed candidates. There is no whole-file checksum or transactional generation number.
- **Publication / commit:** direct `Files.writeString` to the final file for the initial and finalized metadata snapshots. Successful return is the current commit point.
- **Temp creation:** none.
- **Partial/short write:** a failed/torn rewrite can make the run candidate unreadable; latest-run UI then skips it. Surviving heartbeat/ledger can reconstruct some launch/playtime facts, but not every exact latest-run field.
- **Flush/fsync:** none.
- **Replacement/move:** none.
- **Cleanup failure:** session cleanup/retention is separate.
- **Restart coexistence:** one final `run.json` path exists per session; corrupt newest run is skipped rather than combined with older bytes. `lastRun` selects the newest valid same-install candidate by session modification time.
- **In-memory-before-publish:** the game process can run before and during metadata updates; final product evidence can therefore lag or disappear on write failure.
- **Coverage:** `DesktopBridgeCommandTest`, run command tests, and `LaunchLedgerBackfillTest` cover corrupt/missing run metadata and recovery behavior.
- **Missing deterministic faults:** short/truncate final rewrite, ENOSPC during finalization, and crash between game exit, final `run.json`, ledger append, and heartbeat deletion.

### Runtime semantic/process state and adapter health

These are separate files, but they share the same session/evidence lifecycle and the same sibling-temp publication doctrine.

- **Owners / paths:** `RuntimeSemanticState` → `runtime-state.json`; `RuntimeProcessReport` → `runtime-process.json`; `AdapterHealthReport` → `adapter-health.json` under the run directory.
- **Readers:** desktop startup timing / smoke process validation / `DesktopBridgeCommand.adapterHealth` respectively.
- **Validation:** all are versioned (`starsector-preflight-runtime-state-v1`, `starsector-preflight-runtime-process-v1`, `starsector-preflight-adapter-health-v1`); desktop adapter-health reader bounds to 256 KiB, requires known status values, and projects bounded fields. Runtime-state/process readers validate process/timing semantics in their dedicated identity classes.
- **Publication / commit:** unique sibling `.tmp-<pid>-<nano>` + atomic replace, ordinary replace fallback; successful move is the commit point.
- **Temp/write/move failure:** startup semantic-state initialization can disable that evidence path; later semantic-state failures disable further writes. Process/report failures are contained as evidence failures. Adapter-health analysis reports `IOException` to its caller.
- **Partial/short write:** malformed temp is not read; old final file remains unless replacement committed. Corrupt final evidence is ignored by product readers.
- **Flush/fsync:** none.
- **Cleanup failure:** helpers attempt failed-temp deletion; orphan temps are not reader inputs.
- **Restart coexistence:** final versioned file is authority for the corresponding evidence projection; stale temps are ignored. Loss changes timing/health evidence rather than game behavior.
- **In-memory-before-publish:** game/runtime operation continues after contained evidence write failures; user-facing evidence can therefore be absent even when the observed event occurred.
- **Coverage:** `RuntimeSemanticStateTest`, desktop smoke/live-report tests, `AdapterHealthReportTest`, and `DesktopBridgeCommandTest` cover schema/semantic projection and corrupt/missing inputs.
- **Missing deterministic faults:** publication primitive failures for each writer, especially old-valid + new-temp + corrupt-final restart cases.

### Save observation ledger

- **Owner / path:** `SaveProfileObservation.Ledger`; `runtime/save-observations-v1.json`.
- **Reader / writer:** `SaveProfileObservation.observations` / observer record path through `Ledger.record`.
- **Validation:** format `starsector-preflight-save-observations-v1`; strict root JSON; bounded record list (400), per-record typed/path/profile/time checks, age/bounds filtering. A malformed whole document returns an empty observation list. No checksum/generation.
- **Publication / commit:** create a unique temp in the same directory, write the complete document, atomic replace with ordinary replace fallback. Successful move is the current commit point.
- **Temp creation failure:** record attempt fails and is converted to diagnostics by the observing workflow; game/save flow has already happened.
- **Partial/short write:** old final document remains if the temp never moves. A stale temp can remain if cleanup also fails.
- **Flush/fsync:** none.
- **Move failure:** observation record fails; old ledger remains.
- **Cleanup failure:** `finally Files.deleteIfExists(temp)` can surface cleanup failure and can mask/augment the publication failure depending on exception ordering.
- **Restart coexistence:** only the final file is read. A corrupt final file becomes an empty history; valid stale temp or older content is not automatically recovered.
- **In-memory-before-publish:** save/profile activity proceeds independently; a real observation can be lost while the game remains successful.
- **Coverage:** `SaveProfileObservationTest` and `SaveProfileObservationContainmentTest` cover observation semantics, bounds, identity, and path containment.
- **Missing deterministic faults:** temp create/write/move/cleanup faults, corrupt-final restart, old/new/temp coexistence, and failure after observation event but before durable ledger publication.

### Run and benchmark evidence directories

- **Owner / path:** multiple run/benchmark evidence producers; `runs/<session>/`, `benchmarks/<session>/`. `EvidenceRetention` owns pruning.
- **Reader / writer:** support, desktop bridge, benchmark comparison, diagnostic export, and retention inventory readers; many bounded evidence writers.
- **Validation:** individual high-value files are versioned/strict as described above. `EvidenceRetention` treats a session as a measured tree (bytes/files/max modified time), not as one transactional object.
- **Publication / commit:** there is no session-wide commit point. Individual evidence files commit independently.
- **Failure behavior:** partial sessions can exist and are expected to be diagnosable. Retention re-measures every removal target before deleting any target so a session that changed after review is refused rather than pruned from a stale plan.
- **Flush/fsync:** no session-wide sync protocol.
- **Restart coexistence:** readers validate each required file; retention sees the surviving directory tree. Old runs/benchmarks are intentionally prunable.
- **In-memory-before-publish:** benchmark/run execution can progress while evidence files are still being published; acceptance logic for a particular benchmark has its own evidence validation, while ordinary run evidence is best-effort in several paths.
- **Coverage:** `EvidenceRetentionTest`, `DiagnosticBundleTest`, benchmark tests, desktop bridge/smoke tests.
- **Missing deterministic faults:** session-level crash matrices that enumerate which product claims survive each boundary between required file publications.

## Renderer localStorage

All renderer stores live in the WebView's `localStorage`; `desktopStorage.ts` is the authoritative ownership list for full-data removal. `desktopStorage.test.ts` currently proves that all 14 known keys occur exactly once in that list, that removal attempts every key even if one key throws, and that the remembered install root is a disposable startup hint.

Common localStorage failure rules:

- there is no sibling temp, move, checksum, journal, or explicit flush;
- the actual local commit point is a successful `setItem`/`removeItem` call;
- storage exceptions are handled differently by each owner, so **hook ordering is part of durability semantics**;
- restart reads only the one current value for the key; there is no old/new generation recovery beyond whatever the browser kept;
- full Preflight data removal attempts every key in `PREFLIGHT_LOCAL_STORAGE_KEYS` independently.

| Key | Classification | Owner; reader / writer | Validation | Failure, restart, and memory-before-commit behavior | Existing coverage / missing deterministic faults |
| --- | --- | --- | --- | --- | --- |
| `preflight.theme` | best-effort | `useTheme`; initializer / setter | enum `system|light|dark` | Reader currently calls storage directly; malformed value defaults. Setter writes before React state update, so a thrown write can prevent the requested session change. Restart uses stored value/default. | Theme behavior is covered through desktop UI tests; missing explicit denied-`getItem` and denied-`setItem` cases. Evidence-only while #651 is active. |
| `preflight.palette` | best-effort | `useTheme`; initializer / setter | bounded palette enum | Same ordering as theme: local commit precedes state update. | Same missing storage-fault cases as theme. |
| `preflight.optimizationPreset` | best-effort | `useOptimizationPolicy`; reader / effect writer | `recommended|conservative|off` | React state changes first; effect persistence failure is swallowed, so current-session launch policy can proceed from memory while restart falls back to the previous/default value. | `useOptimizationPolicy.test.tsx`; missing deterministic write failure followed by launch + restart assertion. |
| `preflight.disabledOptimizationDomains` | best-effort | `useOptimizationPolicy`; reader / effect writer | JSON array reduced to allowlisted domain IDs | Same state-before-persist behavior as preset; a launch can consume the in-memory domain set before durable local publication. | `useOptimizationPolicy.test.tsx`; missing partial multi-key update/restart matrix where preset persists and domains do not, or vice versa. |
| `preflight.reportReceipt` | authoritative, secret-bearing | `useDiagnosticsReport`; receipt initializer / effect writer | protocol v1, case/object IDs, bytes, SHA-256, product version/timestamps, future retention deadline, deletion method/url/**token**, signature | Native upload/finalization can commit remotely first; renderer then installs the receipt in memory and the effect persists it. Storage errors are swallowed. A process exit or storage failure in that gap can leave a remotely retained report with no restart-safe deletion credential. Filed separately as #679. | `supportReceipt.test.ts`, diagnostics/App tests cover receipt validation/lifecycle; #669 covers multi-receipt overwrite races. Missing deterministic local publication failure after remote acceptance is #679. |
| `preflight.speedRecord` | authoritative, best-effort | `useSpeedRecord`; `readSpeedRecord` / effect writer | schema `version: 2`, SHA-256 profile/benchmark identities, positive finite measured durations, nonnegative counters | Benchmark/launch counters update React state first; scoreboard and cumulative savings claims remain visible for the session even when persistence throws. Restart can lose the measurement/count increment. Invalid stored record is removed. | Function validation is explicit; product tests cover Speed surfaces. Missing deterministic quota/write failure followed by session claim and restart, plus multi-update interruption cases. |
| `preflight.afterLaunchBehavior` | best-effort | `useAfterLaunchBehavior`; initializer / setter | `minimize|keep|quit` | State changes even when storage throws; the just-finished/current-session behavior can use memory. Restart uses previous/default (`minimize`). | `useAfterLaunchBehavior.test.tsx`; missing denied storage + actual post-launch side-effect + restart case. |
| `preflight.automaticUpdateChecks` | best-effort | `useSignedUpdates`; initializer / effect writer | string `off` disables; other/missing values enable | State changes before effect persistence. Current session can change whether checks run while restart uses previous/default enabled setting. | Signed-update tests cover update protocol; missing dedicated storage failure/restart preference case. |
| `preflight.automaticRunReports` | authoritative | `useDiagnosticsReport`; consent reader / `persistAutomaticRunReports` | JSON protocol v1 + disclosure v1 + `enabled:true` + valid decision time | **Fail-closed ordering:** enabling updates in-memory consent only after `setItem` succeeds. Persistence failure forces state off and announces failure, so automatic external upload is not authorized by memory alone. Restart requires the same versioned durable consent. | `useDiagnosticsReport.test.tsx` proves versioned durable consent is required. Missing direct quota/denial fault assertion across restart. |
| `preflight.automaticRunReportHistory.v1` | authoritative | `useDiagnosticsReport`; history reader / `claimAutomaticReport` | JSON protocol v1, bounded string run identities | **Fail-closed ordering:** exact run identity is written before automatic upload is attempted; a thrown write returns false and blocks the send. Once stored, a later upload failure leaves the identity claimed, giving current at-most-once behavior. | `useDiagnosticsReport.test.tsx` proves exact-run dedupe. Missing deterministic write failure, crash after claim/before send, and policy tests for retry semantics tracked by #476. |
| `preflight.instrumentHull` | best-effort | `useInstrumentHull`; saved ID / `choose` | selected ID must resolve to available/default hull when applied | `setItem` is attempted before state update but failure is caught, then state still updates. Current cosmetic choice survives only in memory; restart falls back. | `useInstrumentHull.test.tsx`; missing denied write/restart case. |
| `preflight.instrumentHullTuning.v1` | best-effort | `useInstrumentHull`; `savedTunings` / 180 ms delayed effect | object capped to 256 entries; IDs bounded; numeric tuning fields range-validated; legacy missing inner controls default safely | Editing changes state first. Delayed publication can be skipped by exit and can throw without affecting the current session. Corrupt entries are individually dropped. | `useInstrumentHull.test.tsx`; missing timer/exit-before-commit and storage-failure restart cases. |
| `preflight.lastInstallRoot` | reconstructible, best-effort, disposable | `desktopStorage`; `readLastInstallRoot` / `rememberLastInstallRoot` | string bounded to 32,768 chars | Storage failure is swallowed; discovery reconstructs installation state next startup. No product claim should depend solely on this hint. | `desktopStorage.test.ts` explicitly calls this a disposable startup hint; missing denied storage test is low value because reconstruction is the contract. |
| `preflight.sidebar` | best-effort | `DesktopShell`; initializer / toggle | literal `collapsed` else expanded | State update encloses a caught `setItem`; the current session toggles even when persistence fails. Restart expands/defaults. | App/DesktopShell tests cover UI state; missing dedicated storage denial/restart case. Evidence-only while #651 is active. |

### Renderer multi-key atomicity

No transaction spans localStorage keys. This is important for the optimization pair and for all-data removal: one key can commit while its sibling operation fails. `clearPreflightLocalStorage` intentionally keeps deleting remaining keys after a failure and returns the failed-key list, so an interrupted/denied all-data renderer cleanup can leave a subset of state. Existing tests pin that attempt-all behavior; later durable-state work must decide which key groups require one versioned record or a native commit boundary instead of assuming per-key calls form a transaction.

## Adjacent user-owned/game-owned mutation targets

These are not Preflight-owned stores, so they are outside the classification count above. They are included because the Preflight-owned backups/review receipts only make sense beside the commit point they guard.

| Target | Owner / reader-writer | Publication and actual commit point | Failure/restart behavior | Existing coverage / missing faults |
| --- | --- | --- | --- | --- |
| Starsector launcher preferences | `GameLaunchPreferences`; Java `Preferences` node | Preflight writes typed keys and calls `store.flush()`; flush return is the commit point exposed to Preflight. A Preflight-owned backup is created first. | Any exception triggers restore from the in-memory pre-change backup and another flush attempt; rollback failure is suppressed onto the original failure. Restart truth comes from the platform preferences store. | `GameLaunchPreferencesTest`; missing deterministic platform flush-fails-after-some-keys and rollback-flush-fails matrix. |
| Starsector `mods/enabled_mods.json` during profile activation | `ProfileCommand` | Re-read expected bytes, create same-directory `.preflight-enabled-mods-*`, write replacement, copy permissions best-effort, re-read expected source, then atomic replace with ordinary replace fallback. Successful move is the external commit point. | Preflight-owned exact-byte backup and a fresh activation review precede publication. Move/write failures leave current source where replacement has not committed; later failure paths can use retained backup/manual recovery. | `ProfileCommandTest`; #649 active. Missing deterministic temp/write/move/cleanup faults are deliberately left for later work. |
| Launcher/vmparams heap source | `JvmMemorySettings` | Described above: exact stable-source check + backup + same-directory staged replacement + immediate byte/semantic verification. | Rollback is conditional on proving the just-published source was not externally changed. | `JvmMemorySettingsTest` already has deterministic external-edit hooks; primitive I/O faults remain. |

## Existing doctrine revealed by the inventory

The repo currently has several intentional durability levels rather than one universal writer:

1. **External side-effect gates can fail closed.** Automatic failed-run consent and exact-run dedupe both require successful localStorage publication before upload is allowed. Profile activation requires a persisted, fresh, source-bound review before `enabled_mods.json` replacement.
2. **Launch bookkeeping is deliberately best-effort.** A game launch survives ledger/heartbeat/evidence write failures. The ledger reader isolates torn lines, and surviving run evidence can reconstruct missing rows.
3. **Some user-visible claims advance from memory before restart-safe publication.** The Speed record, optimization choice, after-launch behavior, update-check preference, cosmetic settings, and report receipt all have at least one state-first path. The report receipt is the severe case because it carries an external deletion authority; #679 records that defect separately.
4. **Same-directory staging is common, but explicit crash durability is absent.** Profile records, activation reviews, save observations, runtime evidence, operation metadata, heap edits, and ledger trimming use temp+move without file/directory sync.
5. **Safety backups use direct final-looking files.** Profile/launch-setting/heap backups are created under their retained filename before the full payload copy has an independently verified checksum/commit marker. Their callers generally fail before the protected mutation begins, so the primary danger is a misleading recovery artifact rather than silent source mutation.

These differences should remain explicit until a later #584 slice defines the reviewed persistence primitives and which classifications are allowed to use each one.

## Deterministic fault backlog by boundary

The highest-value reusable seam would inject failures at the publication primitive boundary instead of adding one ad-hoc hook per store. This first slice leaves production code unchanged. Later work can cover the following deterministic cases without a repository-wide rewrite:

- temp creation fails before any new bytes exist;
- temp/direct write reports failure after a short prefix was written;
- append reports failure after a partial JSON line;
- atomic move is unavailable and fallback move succeeds;
- atomic move fails for another reason and fallback is not attempted;
- fallback move/replacement fails;
- publication succeeds and temp cleanup fails;
- backup payload write succeeds and retention/cleanup fails before protected mutation;
- old + new-final + stale-temp + corrupt-final combinations on restart;
- localStorage `getItem`, `setItem`, and `removeItem` throw independently;
- process exit between React state update and effect publication;
- Java Preferences flush fails after some in-memory `put`s, followed by rollback flush failure;
- crash/power-loss semantics where a successful write/move has no file or parent-directory sync.

A later implementation slice should select the smallest owner for such a seam and use it first on authoritative/secret-bearing stores. The inventory itself intentionally changes no production persistence semantics.
