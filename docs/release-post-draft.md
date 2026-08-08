# Public beta announcement draft

**Don't publish yet.** Replace every bracketed field after authorization, release-candidate testing,
and the final controlled benchmark.

## Headline

Preflight public beta: heavily modded startup from a 101-second worst case to 15.88 seconds

## Short version

Preflight is a free, open-source launcher and preparation tool for Starsector. It moves deterministic
texture, data, generated-code, and audio work ahead of the game launch, then reuses the exact result
for the same game and ordered mod profile.

On the 83-mod development profile, the observed record moved from a roughly **101-second worst case
to a 15.88-second fastest warm launch**. The established controlled median at the start of the
chronology was 88.13 seconds. The release claim is **[CONTROLLED BEFORE] → [CONTROLLED AFTER]**,
measured over **[COHORT]** on
**[GAME VERSION / HARDWARE / RUNTIME]**. Your result will depend on your mods, hardware, storage,
cache warmth, memory pressure, and temperature.

Preflight doesn't permanently patch Starsector, mod JARs, executables, assets, activation data, or
saves. Runtime shortcuts are exact-version-gated, exist only in the launched JVM, and use the
original path when their identity or validation doesn't match. It writes its own caches and
reports; only explicit backed-up profile and launch-setting actions update the corresponding
game-owned preferences.

## What the beta includes

- **Recommended** launch mode and **Balanced** storage by default.
- Prepared repeat-launch work for textures, merged/spec data, generated mod bytecode, and audio.
- Reviewed campaign/combat shortcuts behind the same exact adapter boundary.
- Current cache disk use, preview-first cleanup, and clean removal.
- Existing Starsector display, sound, UI-scale, antialiasing, and battle-size settings.
- An optional, consent-based run report containing bounded diagnostics—never saves, game/mod assets,
  screenshots, audio, JFR recordings, or arbitrary logs.

## Important beta limits

- Real-game coverage: **[MACOS / WINDOWS / LINUX MATRIX]**.
- Reviewed game version: **[VERSION]**. Unknown versions decline individual optimizations; a major
  launcher/layout change may still require a Preflight update.
- Reviewed mod-specific adapters: **[LIST]**. “Not listed” doesn't mean incompatible; no specific
  acceleration is claimed for that mod.
- Disk use: **[BALANCED RANGE]** for the test profile; Fastest is optional and larger.
- Package trust: OS-unsigned macOS/Windows packages, SHA-256 manifests, signature-verified in-app
  updates, and **[TESTED PLATFORM WARNING INSTRUCTIONS]**.

## Install, verify, and remove

Download **[RELEASE URL]**, verify **[CHECKSUM/SIGNATURE INSTRUCTIONS]**, and follow the platform
install guide. Preflight detects the game, previews preparation size, and asks before any
game-owned preference change.

Removing the app/launcher leaves Starsector, mods, saves, and Preflight caches intact. **Remove all
Preflight data** separately previews and deletes only Preflight-owned caches and diagnostics.

## Evidence and feedback

- Performance and optimization history: **[LINK]**
- Exact product/safety contract: **[LINK]**
- Known issues and compatibility matrix: **[LINK]**
- Source and releases: **[LINK]**

If something fails, choose **Send run report** only after reviewing its disclosure. Include the case
ID in **[SUPPORT THREAD / ISSUE TEMPLATE]**. Reports are retained for **[RETENTION]** and can be
deleted using **[DELETION PROCESS]**.

Preflight is an independent project and **[FINAL APPROVED DISCLAIMER]**.
