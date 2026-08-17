# Antigravity (Gemini) Engineering Notes & Roadmap

**Date:** 2026-08-17  
**Branch:** `agent/antigravity`

---

## 1. What Landed on `agent/antigravity`

### Asymmetric Hull Centroid Normalization ([#535](https://github.com/teamleaderleo/preflight/issues/535))
- **Problem**: `buildDeck` and `buildHullSegments` derived their lateral centerline from `hullFrame().center.y`, which is the arithmetic mean of all outline vertices. On asymmetric ships with dense point clusters on one flank (e.g. Odyssey with a 16.4% asymmetry, Astral with 12.5%), `center.y` drifted away from `0`, tilting the deck cabin and causing a 23%–33% rim falloff thickness distortion between port and starboard.
- **Fix**: Anchored the lateral reference to the bounding box midpoint `midY = (minY + maxY) / 2` (which equals `0` for normalized hulls and geometric center for collision bounds).
- **Evidence & Verification**: Added unit tests in [`wireframeHullGeometry.test.ts`](preflight-desktop/src/wireframeHullGeometry.test.ts) creating an asymmetric hull fixture and asserting that deck vertices remain symmetric about `y = 0`.

### Complete Palette Token Invariant Test ([#536](https://github.com/teamleaderleo/preflight/issues/536))
- **Problem**: `:root` defines the Hangar base palette. Overrides (`blueprint`, `ultraviolet`, `airglow`, `phosphor`) define tokens on top. If an override omits a token, CSS cascades back to Hangar's gold values rather than failing visibly.
- **Fix & Invariant**: Added an automated test in [`styles.test.ts`](preflight-desktop/src/styles.test.ts) that inventories all custom properties in `:root`. Fonts and text-support tokens intentionally inherit; all 38+ color, alpha, gradient, and `--instrument-*` tokens are strictly enforced to exist in every palette override block.

### 3D Perspective & Depth Shading Renderer ([#537](https://github.com/teamleaderleo/preflight/issues/537), [#531](https://github.com/teamleaderleo/preflight/issues/531))
- **Math**: Pitch `0.62`, perspective eye distance `7.6`, normalized reach `0.95`.
- **Depth Cue**: Back-to-front segment depth sorting with depth-scaled alpha and stroke thickness.
- **Keel Grid & Nose**: Keel ground grid projected with the same camera; bright bow nose marker.
- **Rate**: 360° rotation at 0.34 rad/s (~18s full period) with 24 FPS lock and reduced-motion fallback.

### Desktop UX Polish
- **Launch Posture**: Added posture indicator (`Accelerations active · Balanced storage` / `Original code and assets · Vanilla fallback`) under the primary launch button in [`HomePage.tsx`](preflight-desktop/src/components/HomePage.tsx).
- **Hydration Stability**: Added `min-height: 130px` to `.quick-settings--loading` in [`styles.css`](preflight-desktop/src/styles.css) to eliminate layout shift during startup settings scan.
- **Live Ship Preview**: Embedded a live rotating wireframe thumbnail inside the "Display ship" selector card in [`SettingsPage.tsx`](preflight-desktop/src/components/SettingsPage.tsx).

---

## 2. Next Iteration Frontiers & Architecture Directions

### A. Desktop Startup Latency Decomposition
When investigating "Preflight feels slow to open", separate the timing into three distinct buckets:
1. **Native Process & WebView Initialization**: Tauri binary execution -> Webview creation -> HTML/CSS first paint.
2. **Frontend Hydration**: React bundle parse -> state initialization -> `theme-init.js` application.
3. **Background Engine Scans**: Initial `get_home_state` reading `cacheInspection`, `profiles`, and `launchSettings` concurrently in one JVM process (`DesktopHomeStateCommand`).

### B. Last-Launch Startup Duration Readout ([#463](https://github.com/teamleaderleo/preflight/issues/463)) & Adapter Health ([#391](https://github.com/teamleaderleo/preflight/issues/391))
- **Goal**: After an ordinary launch (via CLI `preflight run` or desktop UI), show the exact measured startup-to-menu time (`Last launch: 15.3s · 58 plans applied`) directly on Home / Speed without requiring a full 2-launch benchmark run.
- **Contract**: Read the latest run's `run.json` and `adapter-health.json` from the run directory and expose through `DesktopBridgeCommand` / `bridge.ts`.

### C. In-App Hull Customizer / Editor ([#532](https://github.com/teamleaderleo/preflight/issues/532))
- Integrate dynamic tracing controls (`outerSmooth`, `outerDetail`, `height`) with real-time 3D wireframe preview inside Settings / `/hangar` for any installed vanilla or modded hull.

---

## 3. Review & Verification Reference

- **Test Suite**: `npm --prefix preflight-desktop test` (182 Vitest tests)
- **Frontend Build**: `npm --prefix preflight-desktop run build` (`tsc -b && vite build`)
- **Tauri Backend**: `cargo test --manifest-path preflight-desktop/src-tauri/Cargo.toml` (74 tests)
- **Clippy**: `cargo clippy --manifest-path preflight-desktop/src-tauri/Cargo.toml -- -D warnings`
- **Maven Backend**: `./mvnw test -Dtest=DesktopBridgeCommandTest,AdapterHealthReportTest`
