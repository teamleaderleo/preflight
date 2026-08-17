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

### Desktop UX Polish & Gating
- **Launch Posture**: Added posture indicator (`Accelerations active · Balanced storage` / `Original code and assets · Vanilla fallback`) under the primary launch button in [`HomePage.tsx`](preflight-desktop/src/components/HomePage.tsx).
- **Hydration Stability**: Added `min-height: 130px` to `.quick-settings--loading` in [`styles.css`](preflight-desktop/src/styles.css) to eliminate layout shift during startup settings scan.
### Exact Per-Mod Resource & Prepared-Data Cost Accounting ([#563](https://github.com/teamleaderleo/preflight/issues/563))
- **Core Cost Model**: Added [`ModCostBreakdown.java`](preflight-core/src/main/java/dev/starsector/preflight/core/ModCostBreakdown.java) in `preflight-core` providing immutable, factual data structures (`ModFootprint`, `ClassCounts`, `OverlapCounts`, `Report`) that separate verifiable physical resource metrics (installed files, GPU texture allocations, audio source bytes, prepared cache payloads) from speculative startup timing blame.
- **Aggregator & Analyzer**: Implemented [`ProfileModCostAnalyzer.java`](preflight-cli/src/main/java/dev/starsector/preflight/cli/ProfileModCostAnalyzer.java) in `preflight-cli`, combining `ProfileCensus`, `ResourceIndex`, `ResourceProviderComparison`, and `GpuTextureFootprint` into a reconciled cost report.
- **CLI Command**: Added `preflight profile cost [--game <path>] [--json]` to [`ProfileCommand.java`](preflight-cli/src/main/java/dev/starsector/preflight/cli/ProfileCommand.java).
- **Privacy & Invariants**: Enforces strict exclusion of physical filesystem roots and usernames in public serialized JSON.

---

## 2. Review & Verification Reference

- **Full Desktop Verification Pipeline**: `npm --prefix preflight-desktop run verify`
  - **Release Node Tests**: 20/20 passing
  - **Vitest Unit Tests**: 220/220 passing across 27 test suites
  - **Frontend Build**: `tsc -b && vite build` built client bundle cleanly in 98ms
  - **Rust Backend Tests**: 86/86 Cargo tests passing
  - **Cargo Format & Clippy**: `0` warnings
- **Maven Backend**: `./mvnw test` (120/120 CLI tests passing, 688/688 total project tests passing)

