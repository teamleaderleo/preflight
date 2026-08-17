# Antigravity (Gemini) Engineering Notes & Roadmap

**Date:** 2026-08-17  
**Branch:** `agent/antigravity` (rebased and integrated with `origin/main`)

---

## 1. What Landed on `agent/antigravity`

### Asymmetric Hull Centroid Normalization ([#535](https://github.com/teamleaderleo/preflight/issues/535))
- **Problem**: `buildDeck` and `buildHullSegments` derived their lateral centerline from `hullFrame().center.y`, which is the arithmetic mean of all outline vertices. On asymmetric ships with dense point clusters on one flank (e.g. Odyssey with a 16.4% asymmetry, Astral with 12.5%), `center.y` drifted away from `0`, tilting the deck cabin and causing a 23%–33% rim falloff thickness distortion between port and starboard.
- **Fix**: Anchored the lateral reference to the bounding box midpoint `midY = (minY + maxY) / 2` (which equals `0` for normalized hulls and geometric center for collision bounds).
- **Evidence & Verification**: Added unit tests in [`wireframeHullGeometry.test.ts`](preflight-desktop/src/wireframeHullGeometry.test.ts) creating an asymmetric hull fixture and asserting that deck vertices remain symmetric about `y = 0`.

### Complete Palette Token Invariant Test ([#536](https://github.com/teamleaderleo/preflight/issues/536))
- **Problem**: `:root` defines the Hangar base palette. Overrides (`blueprint`, `ultraviolet`, `airglow`, `phosphor`) define tokens on top. If an override omits a token, CSS cascades back to Hangar's gold values rather than failing visibly.
- **Fix & Invariant**: Added an automated test in [`styles.test.ts`](preflight-desktop/src/styles.test.ts) that inventories all custom properties in `:root`. Fonts and text-support tokens intentionally inherit; all 38+ color, alpha, gradient, and `--instrument-*` tokens are strictly enforced to exist in every palette override block across all 5 palettes.

### 3D Perspective & Depth Shading Renderer ([#537](https://github.com/teamleaderleo/preflight/issues/537), [#531](https://github.com/teamleaderleo/preflight/issues/531))
- **Math**: Pitch `0.62`, perspective eye distance `7.6`, normalized reach `0.95`.
- **Depth Cue**: Back-to-front segment depth sorting with depth-scaled alpha and stroke thickness.
- **Keel Grid & Nose**: Keel ground grid projected with the same camera; bright bow nose marker.
- **Rate**: 360° rotation at 0.34 rad/s (~18s full period) with 24 FPS lock and reduced-motion fallback.

### Last-Launch Startup Duration Readout ([#463](https://github.com/teamleaderleo/preflight/issues/463))
- **Engine / CLI Bridge**: Updated `DesktopBridgeCommand.java` to parse `run.json` safely for `started`, `ended`, `durationMillis`, `outcome`, and `exitCode` under bounded memory limits (`MAX_RUN_METADATA_BYTES`). Added unit test `snapshotCarriesTheCompactRunSummaryOfTheLatestRun` in `DesktopBridgeCommandTest.java`.
- **Desktop Frontend**:
  - Exported canonical `formatDuration` in `uiFormat.ts` with comprehensive unit tests in `uiFormat.test.ts`.
  - Added typed fields to `LastRun` in `types.ts`.
  - Wired `lastRun` to `SpeedScoreboard.tsx` to display the actual measured startup time (`Last launch took 15.3s`) directly on unmeasured speed scoreboards.

### Automatic Failed-Run Reports ([#476](https://github.com/teamleaderleo/preflight/issues/476))
- **Consent & Storage**: Added `AUTOMATIC_RUN_REPORTS_STORAGE_KEY` (`preflight.automaticRunReports`) to `desktopStorage.ts` with strict storage ownership tests. Default is strictly `false`/`off`.
- **Settings Toggle**: Added "Send failed-run reports automatically" checkbox in `SettingsPage.tsx` with clear privacy disclosures and intake capability gating.
- **Automated Single-Flight Export**: On failed launch process completion, triggers a single disclosed diagnostics bundle export and upload without blocking application shutdown or retrying infinitely on failure.

### Interrupted-Session Playtime Durability ([#484](https://github.com/teamleaderleo/preflight/issues/484))
- **Heartbeat Daemon**: Added [`LaunchHeartbeat.java`](preflight-cli/src/main/java/dev/starsector/preflight/cli/LaunchHeartbeat.java) to periodically record in-flight duration to `heartbeat.json` via atomic rename while the game process is running.
- **Interrupted Session Recovery**: Updated [`LaunchLedgerBackfill.java`](preflight-cli/src/main/java/dev/starsector/preflight/cli/LaunchLedgerBackfill.java) to recover unfinalized (`ended == null`) runs as `"INTERRUPTED"` with the durable heartbeat duration.
- **Playtime Inclusion**: Added `"INTERRUPTED"` to [`Playtime.java`](preflight-cli/src/main/java/dev/starsector/preflight/cli/Playtime.java) session aggregation so ungraceful shutdowns and power-loss events retain their earned playtime hours.
- **Verification**: Added unit tests in `LaunchHeartbeatTest.java` and `LaunchLedgerBackfillTest.java`.

### Free-Space-Pressure Cache Eviction ([#477](https://github.com/teamleaderleo/preflight/issues/477))
- **Safety Reserve**: Added `AUTOMATIC_FREE_SPACE_THRESHOLD_BYTES = 5 GiB` to `useAutomaticMaintenance.ts`.
- **Dual Trigger**: Evaluates unreachable cache profile pruning when either cache size exceeds the 12 GiB limit OR available free disk space falls under 5 GiB while cache is non-empty.
- **Verification**: Unit tests added in `useAutomaticMaintenance.test.tsx` verifying threshold activation.

### Support Export Refusal During All-Data Removal ([#621](https://github.com/teamleaderleo/preflight/issues/621), [PR #626](https://github.com/teamleaderleo/preflight/pull/626))
- **Problem**: `apply_removal()` in Tauri held the coordinator mutex without publishing an admission state. A concurrent diagnostics export request would block on the mutex and execute *after* the destructive uninstallation finished, generating diagnostic files right after data removal.
- **Fix**: Added `pub(crate) removing: bool` to `OperationState` with `RemovalGuard`. `begin_removal()` sets `removing = true` and releases the coordinator lock during child execution. `begin_diagnostics_export()`, `begin_update_check()`, and `begin_update_install()` fail-closed immediately while removal is active. Added `removing` to `OperationSnapshot` across Rust and TypeScript.

### Copy Setup Public-Data Privacy Boundary ([#610](https://github.com/teamleaderleo/preflight/issues/610), [PR #629](https://github.com/teamleaderleo/preflight/pull/629))
- **Problem**: `CopySetupObservations` accepted optional per-mod `displayName` and `declaredVersion` strings which were written directly to clipboard text. Third-party mod metadata could inject private local paths (`/Users/alice/...`, `C:\...`), URLs with query tokens, credentials, or control characters.
- **Fix**: Added `boundedModId` to enforce strict mod ID token grammar, and `boundedPublicModText` / `isSensitiveModLabel` to screen `displayName` and `declaredVersion`. Fails closed by omitting labels that contain paths (`/`, `\`), URIs, credentials, secret assignments, or control characters, while cleanly preserving safe Unicode titles (e.g. `星海の航路 — Étoile`, `2.1.0-RC1`).

### Hangar Hull Picker Selection Retention & Count Consistency ([#602](https://github.com/teamleaderleo/preflight/issues/602), [PR #622](https://github.com/teamleaderleo/preflight/pull/622))
- **Fix**: Retains selected hulls outside the first 60 rows by deterministically mapping the active matching selection into the 60th slot. Preserves selection across filter clears and clarifies label count wording to `additional hulls`.

### Hangar Facet Ring Endpoints In 3D ([#605](https://github.com/teamleaderleo/preflight/issues/605))
- **Fix**: `sideStations` derives `(waterline, deck, keel)` vertices by interpolating directly along straight 3D ring segments, eliminating vertical facet divergence on sparse hull contours.

### Launcher Heap Setting Containment Proving ([#601](https://github.com/teamleaderleo/preflight/issues/601))
- **Fix**: `boundedText` validates `containedByRealPath(root, path)` prior to reading, preventing symlink traversal outside the Starsector installation root.

### Refuse All-Data Removal When Home Root Is a Symlink or Alias ([#591](https://github.com/teamleaderleo/preflight/issues/591), [PR #631](https://github.com/teamleaderleo/preflight/pull/631))
- **Fix**: In `UninstallCommand.java`, validates `home.root()` under `LinkOption.NOFOLLOW_LINKS`. Refuses all-data removal if the Preflight home directory is a symlink or alias (`safe = false`), preventing destructive traversal or unlinking of arbitrary external directories. In `OperationLease.java`, blocks acquisition of `remove-all-preflight-data` when root is a symlink.

### Refuse Launcher Installation Through Symlinked Integration Paths ([#594](https://github.com/teamleaderleo/preflight/issues/594), [PR #632](https://github.com/teamleaderleo/preflight/pull/632))
- **Fix**: In `InstallCommand.java`, validates target integration paths and parent directory chains under `NOFOLLOW_LINKS` via `requireRealDirectory()` and `validateNotSymlink()`. Publishes launcher scripts and engine JAR via temporary sibling files and atomic moves (`ATOMIC_MOVE` / `REPLACE_EXISTING`). Refuses installation if the home root or target launcher paths are symlinks or aliases.

### Require Ownership Proof Before Removing Launcher Integrations ([#596](https://github.com/teamleaderleo/preflight/issues/596), [PR #635](https://github.com/teamleaderleo/preflight/pull/635))
- **Fix**: Added [`IntegrationOwnership.java`](preflight-cli/src/main/java/dev/starsector/preflight/cli/IntegrationOwnership.java) to verify structural markers, bundle IDs, and script contents before treating an integration as Preflight-owned. Added `recordInstalledIntegrations()` in `PreflightHome` to bind installed locations in `integrations.json`, surviving environment drift (`LOCALAPPDATA`). `UninstallCommand.plan()` excludes unowned collisions from deletion targets, and `InstallCommand` refuses overwriting unowned collisions.

### Bind ProfileIdentityContext Hashes to Stable Provider Observations ([#603](https://github.com/teamleaderleo/preflight/issues/603), [PR #636](https://github.com/teamleaderleo/preflight/pull/636))
- **Fix**: In [`ProfileIdentityContext.java`](preflight-cli/src/main/java/dev/starsector/preflight/cli/ProfileIdentityContext.java), bound memoized file digests to pre- and post-read metadata stability (`size`, `lastModifiedTime`, `fileKey`) against indexed providers and filesystem identity. Fails closed immediately on mid-read modification, replacement, or post-index alteration, preventing invalid digests from entering the cache. Synchronized per-path calculations across threads to eliminate duplicate I/O.

### Cache Derived Installed Hull Catalog Under Exact Cosmetic Input Identity ([#598](https://github.com/teamleaderleo/preflight/issues/598), [PR #637](https://github.com/teamleaderleo/preflight/pull/637))
- **Fix**: In [`hulls.rs`](preflight-desktop/src-tauri/src/hulls.rs), added `compute_catalog_fingerprint` binding installation identity, `.ship` file names/sizes/mtimes, and featured sprite file sizes/mtimes under generator schema tag `preflight-wireframe-catalog-v1`. Added `CATALOG_CACHE` providing instant catalog reuse for identical installations without redundant I/O or PNG re-tracing. Automatically invalidates when participating ship/sprite inputs change while ignoring unrelated files.

### Explicitly Represent Unavailable Save-Profile Mod Metadata ([#589](https://github.com/teamleaderleo/preflight/issues/589), [PR #638](https://github.com/teamleaderleo/preflight/pull/638))
- **Fix**: In [`SaveProfileObservation.java`](preflight-cli/src/main/java/dev/starsector/preflight/cli/SaveProfileObservation.java), added `Difference.MOD_METADATA_UNAVAILABLE` to explicitly represent absence of historical mod evidence. Made `Mod.displayName()` and `Mod.version()` nullable (`null` for ID-only sources), and `Observation.mods()` / `SessionIdentity.mods()` nullable (`null` for unmatched fingerprints vs `[]` for vanilla profiles). In `differences()`, returns `MOD_METADATA_UNAVAILABLE` when either side lacks evidence, preventing false mod-count or change claims.

### Bound Save-Profile Polling & Offload Launch Index Scan ([#588](https://github.com/teamleaderleo/preflight/issues/588), [#587](https://github.com/teamleaderleo/preflight/issues/587), [PR #639](https://github.com/teamleaderleo/preflight/pull/639))
- **Fix**: In [`SaveProfileObservation.java`](preflight-cli/src/main/java/dev/starsector/preflight/cli/SaveProfileObservation.java), bounded polling work with explicit ceilings (`MAX_SAVES_PER_POLL = 400`, `MAX_ENTRIES_PER_SAVE = 512`, `MAX_SAVE_TREE_DEPTH = 6`, `MAX_AGGREGATE_ENTRIES_PER_POLL = 8_192`) and process termination cancellation (`stillOwned`). Over-budget or pathological saves are safely omitted without interrupting gameplay or session finalization. Removed the fallback synchronous `ResourceIndexBuilder.build()` from `resolveIdentity()`, cleanly disabling observation for runs lacking pre-computed fingerprints without adding scan delays to the launch path.

### Peer Reviews
- **PR #625** (Codex / Issue #621): Reviewed non-blocking admission; noted `release-receipt-source-lock.json` review requirement.
- **PR #624** (Codex / Issue #608): Reviewed sub-millisecond `FileTime` precision in direct provider identity.
- **PR #623** (Codex / Issue #595): Reviewed optimistic concurrency check on JVM memory update & rollback.
- **PR #622** (Codex / Issue #602): Reviewed 60-slot deterministic bounded picker fallback.

---

## 2. Review & Verification Reference

- **Full Desktop Verification Pipeline**: `npm --prefix preflight-desktop run verify`
  - **Release Node Tests**: 110/110 passing
  - **Vitest Unit Tests**: 233/233 passing across 28 test suites
  - **Frontend Build**: `tsc -b && vite build` built client bundle cleanly in 91ms
  - **Rust Backend Tests**: 98/98 Cargo tests passing
  - **Cargo Format & Clippy**: 0 warnings
- **Maven Backend**: `./mvnw test` (702/702 tests passing across 5 modules)





