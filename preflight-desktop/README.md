# Starsector Preflight desktop

This directory contains the Tauri 2 desktop application. React renders the interface; a narrow Rust
host resolves the bundled Java engine, asks it for JSON snapshots, and starts tracked game launches.
The browser layer has no shell or filesystem permission.

## Run it locally

You need Node 22+, a full JDK 17+, Maven, and the stable Rust toolchain.

```bash
cd preflight-desktop
npm ci
npm run desktop:dev
```

`desktop:dev` packages the current shaded Preflight JAR and a minimal four-module Java runtime before
starting Tauri. To work only on visual design, use `npm run dev`; it supplies a clearly marked preview
snapshot and does not launch anything.

## Build an installer

```bash
npm run desktop:build
```

Tauri writes native artifacts below `src-tauri/target/release/bundle/`. The intended release formats
are:

- Windows: NSIS `.exe`
- macOS: `.dmg` containing `Starsector Preflight.app`
- Linux: `.AppImage` and `.deb`

The application bundle includes `preflight.jar` and a `jlink` runtime with `java.base`,
`java.desktop`, `java.instrument`, and `jdk.jfr`. End users do not need Java, Node, Maven, npm, or
Rust.

Local builds are unsigned. Public downloads should be code-signed (and notarized on macOS) by CI
before they are described as frictionless installs.

## Boundaries

- The Java `desktop snapshot` bridge emits one versioned JSON document and is hidden from human CLI
  help.
- The Rust host exposes only `get_snapshot` and `start_game`.
- The folder picker is the only frontend capability beyond Tauri's core defaults.
- Preflight writes only to its own home/cache/run directories. It does not rewrite the game, mods,
  or saves.
