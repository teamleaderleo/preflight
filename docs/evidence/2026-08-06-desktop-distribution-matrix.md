# Cross-platform desktop distribution is green

**Date:** 2026-08-06

**Green workflow:** <https://github.com/teamleaderleo/starsector-preflight/actions/runs/31073751507>

**Packaging head:** `fedf236` (`Resolve bundled JVM during AppImage packaging`)

## Result

The complete distribution matrix passed: portable CLI archives, macOS DMG, Windows NSIS installer,
Linux Debian package, and Linux AppImage. Every desktop job ran the frontend tests, prepared the
bundled minimal Java runtime/engine, ran Rust tests, built the native package, collected only the
approved bundle types, generated SHA-256 manifests, and uploaded the artifact.

| platform package | bytes | SHA-256 |
| --- | ---: | --- |
| macOS arm64 DMG | 35,896,153 | `7755f970c130a0afdf094d54c45e5346714ccb082d865bb4106eb7ea6311cf6f` |
| Windows x64 NSIS setup | 31,863,170 | `3f3ee298f665b2a9c9ba2668dbaee6491e0ef8963137babb5f9db1f431ff1cbc` |
| Linux x64 Debian | 39,868,284 | `e4e756be30fbbd05bc5eee1a727f1ec21a29e070b70406dbb934047bf4bc0484` |
| Linux x64 AppImage | 122,063,352 | `1b2088bdc82743e345c3cc6783a7fdaaf12fae2ed1bca1a0f2e1913ac698cfae` |

The macOS package also has a local installed-image gate: the DMG mounted, its bundled engine
answered a live cache snapshot request, and its Orbitron OFL resource was byte-identical to the
repository license.

## Linux AppImage boundary

Tauri's `linuxdeploy` recursively inspects the bundled jlink runtime but does not infer Java's
private `runtime/lib/server` dependency directory. Supplying that directory only during AppImage
packaging lets it resolve `libjvm.so`; the resulting AppImage completed and uploaded.

The portability cost is explicit. `linuxdeploy` bundles GTK/WebKit dependencies and copies
`libjvm.so` into `usr/lib` in addition to the jlink runtime's own server copy, producing a 122.1MB
AppImage versus a 39.9MB Debian package. The Debian package is the lean choice for compatible
distributions; AppImage is the broad single-file beta. Removing that duplicate safely requires a
custom post-linuxdeploy AppDir pipeline because linuxdeploy also rewrites runtime ELF paths. It must
not be implemented as a blind file deletion.

## Release boundary

These are intentionally unsigned beta packages. Apple signing/notarization and Windows signing need
real credentials. CI cannot include the licensed Starsector installation, so Windows/Linux prove
portable engine construction, tests, and packaging—not live game compatibility. Runtime adapters
continue to exact-gate and fall back to vanilla on unknown platform/game/mod bytecode; real beta
telemetry remains the Windows/Linux activation gate.
