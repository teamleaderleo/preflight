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

The checked-in host currently builds with native bundling disabled while the old unmerged packaging
assets and signing workflow are brought forward deliberately. The engine bundle itself already
contains `preflight.jar` and a `jlink` runtime, so end users will not need Java, Node, Maven, npm, or
Rust once platform installers are restored.

## Boundaries

- The Java `desktop snapshot` bridge emits one versioned JSON document and is hidden from human CLI
  help.
- The Rust host exposes only `get_snapshot`, `get_cache`, `start_game`, and `start_preparation`.
- The folder picker is the only frontend capability beyond Tauri's core defaults.
- The host starts `preflight run --fast`, refuses a second tracked instance, and reports the bounded
  tail of a failed child process.
- Preparation is a separately reported background operation, but it shares one ownership lock with
  the game so profile files and caches are never prepared while Starsector is running.
- Preflight writes only to its own home/cache/run directories. It does not rewrite the game, mods,
  or saves.
