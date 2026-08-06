# Starsector Preflight desktop

This directory contains the Tauri 2 desktop application. React renders the interface; a narrow Rust
host resolves the bundled Java engine, asks it for versioned JSON snapshots, and starts one tracked
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
snapshot and does not launch anything.

## Build an installer

```bash
npm run desktop:build
```

Tauri writes native artifacts below `src-tauri/target/release/bundle/`. The distribution workflow
builds each target on its native GitHub runner rather than cross-compiling:

- Windows: NSIS `.exe`
- macOS: `.dmg` containing `Starsector Preflight.app`
- Linux: `.AppImage` and `.deb`

The bundle contains `preflight.jar` and a platform-native `jlink` runtime, so end users do not need
Java, Node, Maven, npm, or Rust. Current packages are unsigned beta artifacts. Do not describe them
as warning-free installs until Windows signing and Apple signing/notarization credentials are wired
into CI and verified on a tagged release.

## Boundaries

- The Java `desktop snapshot` bridge emits one versioned JSON document and is hidden from human CLI
  help.
- The Rust host exposes only installation/cache/profile snapshots, preview-first named-profile
  save/activation, and tracked game/preparation starts. It cannot execute arbitrary frontend input.
- The folder picker is the only frontend capability beyond Tauri's core defaults.
- The host starts `preflight run --fast`, refuses a second tracked instance, and reports the bounded
  tail of a failed child process.
- The only user-selected write outside Preflight's own directories is a `.zip` chosen through the
  native save dialog. The Java engine fills it from its bounded diagnostics allowlist; the frontend
  cannot choose source files or add arbitrary content.
- Preparation is a separately reported background operation, but it shares one ownership lock with
  the game so profile files and caches are never prepared while Starsector is running.
- Confirmed profile activation shares that lock, refuses missing mods and cross-install profiles,
  rechecks the current file, and writes a backup before replacement. Previewing remains read-only.
- Outside an explicitly confirmed profile activation, Preflight writes only to its own
  home/cache/run directories. Activation changes only `enabled_mods.json` through the backed-up,
  rechecked replacement path; it does not rewrite game binaries, mods, or saves.
