# Preflight desktop

This directory contains the Tauri 2 desktop application. React renders the interface; a narrow Rust
host resolves the bundled Java engine, asks it for versioned JSON snapshots, and starts a tracked
accelerated game launch. The browser layer has no shell or filesystem permission.

## Run it locally

You need Node 22+, a full JDK 17+, Maven, and the stable Rust toolchain.

```bash
cd preflight-desktop
npm ci
npm run desktop:dev
```

`desktop:dev` packages the current shaded Preflight JAR and a minimal Java runtime before starting
Tauri. To work only on visual design, use `npm run dev`; it supplies a clearly marked preview
snapshot and doesn't launch anything.

Tauri development uses the configured Vite dev server: React and CSS changes hot-reload in the
webview, while Rust host changes rebuild and restart the native process.

## Build an installer

```bash
npm run desktop:build
```

Tauri writes native artifacts below `src-tauri/target/release/bundle/`. The distribution workflow
builds each target on its native GitHub runner rather than cross-compiling:

- Windows: NSIS `.exe`
- macOS: `.dmg` containing `Preflight.app`
- Linux: `.AppImage` and `.deb`

The bundle contains `preflight.jar` and a platform-native `jlink` runtime, so end users don't need
Java, Node, Maven, npm, or Rust. Current packages are private, unsigned development artifacts. Don't
distribute or describe them as warning-free installs until public distribution is authorized,
Windows signing and Apple signing/notarization are decided and configured, and the signed updater is
verified on a release candidate.

Tagged builds generate a release-only Tauri configuration containing the public updater key and the
v2 artifact switch. They require `TAURI_SIGNING_PRIVATE_KEY`,
`TAURI_SIGNING_PRIVATE_KEY_PASSWORD`, and the compile-time `PREFLIGHT_UPDATER_PUBLIC_KEY`; missing
credentials stop the release before packaging. The client uses the fixed GitHub `latest.json` feed,
checks in the background, and waits for explicit install confirmation. Ordinary development builds
contain no verification key and report their update channel as disabled.

## Boundaries

- The Java `desktop snapshot` bridge emits a versioned JSON document and is hidden from human CLI
  help.
- The Rust host exposes only installation/cache/profile/launch-settings snapshots, validated
  launch-setting updates, preview-first named-profile save/activation, and tracked game/preparation
  starts. It can't execute arbitrary frontend input.
- The folder picker is the only frontend capability beyond Tauri's core defaults.
- The host starts `preflight run --optimization-preset <recommended|conservative|off>`, validates
  that closed set before creating a process, refuses a second tracked instance, and reports the
  bounded tail of a failed child process.
- The only user-selected write outside Preflight's own directories is a `.zip` chosen through the
  native save dialog. The Java engine fills it from its bounded diagnostics allowlist; the frontend
  can't choose source files or add arbitrary content.
- Preparation is a separately reported background operation, but it shares an ownership lock with
  the game so profile files and caches are never prepared while Starsector is running.
- Confirmed profile activation shares that lock, refuses missing mods and cross-install profiles,
  rechecks the current file, and writes a backup before replacement. Previewing remains read-only.
- Outside an explicitly confirmed profile activation, Preflight writes only to its own
  home/cache/run directories, except when the user explicitly saves launch settings. That operation
  updates only Starsector's existing resolution, fullscreen, sound, antialiasing, UI-scale and
  gameplay-settings preferences after a bounded backup. Activation changes only
  `enabled_mods.json` through the backed-up, rechecked replacement path. Neither operation rewrites
  game binaries, mod contents, or saves.
- Update installation is also tracked by the host. It refuses to start while the game or preparation
  is active, blocks competing mutations while replacement is underway, verifies the release
  signature, and restarts only after installation succeeds.
