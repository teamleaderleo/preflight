# Native packages are inspected, executed, installed, and removed

**Date:** 2026-08-07

**Green workflow:** <https://github.com/teamleaderleo/preflight/actions/runs/31167396083>

**Head:** `2ee68474` (`Verify native removal ownership`)

The development-package matrix now checks the bytes a user would receive instead of stopping at a
successful Tauri build. Every job prepares the bounded engine, builds the native package, extracts
it, finds exactly one engine and Orbitron license, compares project files with their reviewed
sources, validates the runtime inventory, and runs the packaged Java with the packaged CLI.

| package | extracted entries | Java runtime | result |
| --- | ---: | ---: | --- |
| macOS arm64 DMG | exact app and volume layouts | 106 files, 50,232,379 bytes | copied from the mounted DMG and executed |
| Windows x64 NSIS | 202 | 158 files, 44,614,076 bytes | extracted, installed, executed, and normally removed |
| Linux x64 Debian | 167 | 112 files, 55,238,719 bytes | extracted, installed, executed, and normally removed |
| Linux x64 AppImage | 455 | 112 files, 55,259,847 bytes | extracted and executed |

The Debian and NSIS install exercises record every non-directory file owned by the installed
package. Their ordinary uninstallers removed every one. The macOS job copied `Preflight.app` out of
the read-only DMG, compared the copy with the mounted source, and executed the engine from the copy.

## AppImage's bounded rewrite

AppImage construction legitimately rewrites three files inside the otherwise exact jlink runtime:

- `lib/jexec`
- `lib/jspawnhelper`
- `lib/server/libjvm.so`

Tauri's pinned `linuxdeploy` tool sets their runtime search path to `$ORIGIN` and strips the copied
VM library. The verifier permits that exact changed set only for AppImage. It requires all three
files to remain ELF binaries with the expected RPATH, rejects any missing, added, or differently
changed runtime path, and then executes Java. Debian, macOS, and Windows retain byte-exact runtimes.

## Source and release boundary

The package checks reject game, mod, save, activation, log, crash, screenshot, and known proprietary
game paths. A separate full-history audit covered 3,931 historical blobs and all 1,227 tracked files
at this head, allowing only six reviewed binary icons and the licensed synthetic fixture set.

These are unsigned development checks. The first public beta still waits for the updater key, a
final tagged-candidate run, written Fractal Softworks guidance, and licensed Windows/Linux game
evidence. Paid Apple signing/notarization and Windows signing were later removed from the
first-beta gate.
