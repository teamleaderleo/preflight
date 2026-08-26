# Preflight desktop

## TL;DR

```text
React UI
   ↓
small Rust/Tauri host
   ↓
shared Java Preflight engine
   ↓
tracked Starsector/preparation processes
```

The browser layer renders the app. It doesn't get a generic shell/filesystem API. The Rust host exposes a fixed set of native commands and delegates Starsector-facing logic to the same Java engine the CLI uses.

For the project-wide explanation, read [How Preflight works](../docs/how-preflight-works.md). This README is for working on the desktop app.

## Run it locally

You need Node 22+, JDK 17+, Maven, and stable Rust.

```bash
cd preflight-desktop
npm ci
npm run desktop:dev
```

`desktop:dev` packages the current Preflight JAR plus a small Java runtime, then starts Tauri.

For visual-only work:

```bash
npm run dev
```

That uses a clearly marked preview snapshot and doesn't launch Starsector.

The browser preview has read-only failure scenarios for UI review:

```text
?scenario=setup
?scenario=low-disk
?scenario=cache-repair
?scenario=profile-mismatch
?scenario=benchmark-unavailable
?scenario=update-error
?scenario=report-error
```

Unknown scenarios fall back to the normal ready preview. Tauri builds ignore the query parameter.

React/CSS changes hot-reload. Rust host changes rebuild/restart the native process.

## Verify changes

After Java-reactor changes:

```bash
# repository root
./mvnw verify

# preflight-desktop/
npm run verify
```

`npm run verify` refreshes the bounded Java engine snapshot before the release-script/frontend/Rust/clippy checks, so desktop verification doesn't accidentally use an older engine JAR.

Meaningful UI work also needs rendered review at **1040×700** and **720×560**. See [UI design](../docs/ui-design.md).

## Build a local native package

```bash
npm run desktop:build
```

Native outputs come from Tauri under `src-tauri/target/release/bundle/`.

The release path creates packages on their native runners:

- Windows: NSIS `.exe`
- macOS: `.dmg` containing `Preflight.app`
- Linux: `.AppImage` and `.deb`

Each package includes `preflight.jar` and a platform-native `jlink` runtime, so end users won't need Java/Node/Maven/npm/Rust installed.

For the stronger local package/lifecycle replay:

```bash
npm run desktop:test-package
```

That exercises the current source/package boundary, native installation, no-launch probing, removal scopes, and preservation sentinels.

Release signing/candidate provenance belongs in [Release readiness](../docs/release-readiness.md) and the release workflows. This README intentionally doesn't duplicate the signing-secret/configuration checklist.

## Native authority: what Rust owns

The host exists to keep OS authority narrow and reviewable.

It can expose specific operations such as:

- installation/cache/profile/settings snapshots;
- validated launch-setting updates;
- preview-first named-profile save/activation;
- tracked game/preparation starts;
- native folder/save dialogs;
- update installation;
- packaged smoke/testing operations.

It can't execute an arbitrary command supplied by React.

The launch preset is a closed choice (`recommended`, `conservative`, `off`) that the native/Java layers validate before creating the game process.

## Important ownership boundaries

### Launch and preparation

Preparation and Starsector share the product's operation ownership rules so they don't mutate the same profile/cache state concurrently.

The host tracks the exact child process it starts and reports bounded failure output instead of letting the frontend manage process IDs directly.

### Profiles and settings

Profile preview is read-only. Confirmed activation rechecks the current state, refuses missing/cross-install content, and backs up `enabled_mods.json` before replacement.

Supported launch-setting updates go through the Java engine's bounded preference path. They don't rewrite game binaries, mod contents, or saves.

### Diagnostics

The frontend can't choose arbitrary source files for a support ZIP. The Java engine creates the ZIP from its allowlist.

If report sending is enabled in a release package, the Rust host rechecks the exact saved ZIP before sending it to the fixed compile-time intake origin. Ordinary development builds can keep local export while sending remains disabled.

See [Diagnostics](../docs/diagnostics.md) for the human-facing boundary and [`report-intake/`](../report-intake/README.md) for server-side operations.

### Updates

Update installation is tracked native work. It waits for conflicting game/preparation activity to clear, verifies the release update signature, installs the accepted update, and only restarts after successful replacement.

Detailed release/update provenance lives in [Versioning and updates](../docs/versioning-and-updates.md) and the release workflows.

## Where code belongs

- **React/TypeScript:** presentation, view state, user interaction, previews.
- **Rust/Tauri:** narrow OS/native authority and the typed bridge.
- **Java engine:** Starsector discovery, preparation, launch semantics, profiles/settings/history/diagnostics.
- **Java agent:** runtime behavior inside the Starsector child JVM.

If a new feature makes React start assembling shell commands, parsing game internals, or implementing a second version of Java-engine policy, it's probably in the wrong layer.

For exact packaged native capabilities, see the [capability receipt](../docs/capability-receipt.md).
