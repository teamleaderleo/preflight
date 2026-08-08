# VMware Fusion acceptance

This harness collects useful Windows and Linux evidence without copying Starsector into a guest.
It creates a tiny synthetic installation at runtime, removes it afterward, and records exactly what
the result can establish. It never turns a virtual-machine result into a native performance claim.

## Architecture boundary

VMware Fusion on Apple silicon runs ARM64 guests.

- Windows 11 ARM can execute the published x64 installer and application through Windows' x64
  application emulator. This is useful evidence for installer behavior, path quoting, the bundled
  runtime, cache preparation, dry-run launch construction, process-adapter availability, diagnostics,
  and removal. The evidence calls this **x64 package behavior under ARM64 emulation**.
- Ubuntu in Fusion is ARM64. It can't execute the published x86-64 Debian or AppImage artifacts.
  It can run the Maven, Java, TypeScript, Rust, and package-contract source tests with native ARM64
  toolchains. The harness calls this **portable source contracts**, not Linux package acceptance.
- An x86-64 Ubuntu machine or VM can run the complete Debian/AppImage package check. Real Linux
  hardware remains the gate for graphics, audio, gameplay, and performance claims.

Modal's NVIDIA workers solve a different problem. The existing block-upload conformance probe uses
one to prove the OpenGL driver accepts Preflight's prepared texture blocks. It doesn't provide a
Windows desktop, a licensed installation, audio, or representative game performance, so the Fusion
harness doesn't duplicate it.

## Guest preparation

Start each guest from a clean snapshot. Copy in a reviewed repository checkout and the candidate
artifacts through a checksum-verified folder. Don't copy a game installation, mods, saves, reports,
activation data, or screenshots into the guest.

The harness needs Node.js, Git, and the repository's normal build dependencies. Windows package
inspection additionally needs `7z` on `PATH`. The Ubuntu ARM64 portable run needs Maven, a compatible
JDK, Rust, and the native libraries already required by Tauri development. Run `npm ci` in
`preflight-desktop` before either command.

Record the repository revision and candidate SHA-256 separately in the operator note. The evidence
document also captures the revision, whether the harness checkout was dirty, a digest of that bounded
status, each candidate package's independently calculated SHA-256, operating-system release,
architecture, Node version, executed checks, and bounded result objects. A dirty checkout is visible
evidence rather than silently inheriting the reviewed claims of `HEAD`.

## Windows 11 ARM package run

Open PowerShell in the repository and run:

```powershell
cd preflight-desktop
npm ci
npm run desktop:fusion-acceptance -- `
  --package-dir "C:\candidate\windows" `
  --output "$HOME\Desktop\preflight-fusion-windows.json"
```

The command performs the existing extracted-package verification and a real silent NSIS
install/uninstall cycle. While the installed copy exists, its bundled Java runs the following
synthetic contract:

1. discover an explicit installation whose path contains spaces, Unicode, and an exact Windows
   batch launcher;
2. prepare resource and classpath indexes into an isolated Preflight home;
3. repeat preparation and require cache reuse;
4. construct an exact dry-run launch while a sentinel proves the launcher wasn't executed;
5. validate the packaged campaign scenario and seal an intentional `skipped` no-game result;
6. probe the exact-PID Windows desktop adapter without attaching to a game;
7. export a bounded diagnostics ZIP and prove a private console sentinel is excluded;
8. preview and apply full Preflight-data removal while synthetic game, mod, and save sentinels remain;
9. uninstall the native application and verify its owned files are gone.

The result doesn't prove a real game starts, nor does it measure Windows performance. Its mode must
be `windows-x64-package-under-arm64-emulation` on an Apple-silicon Fusion guest.

## Ubuntu ARM64 portable run

Open a shell in the repository and run:

```bash
cd preflight-desktop
npm ci
npm run desktop:fusion-acceptance -- \
  --portable-only \
  --output "$HOME/Desktop/preflight-fusion-ubuntu-arm64.json"
```

This runs the full Maven reactor and desktop verification with the guest's native toolchains. The
evidence includes output sizes and SHA-256 digests rather than unbounded build logs. It must contain
a `skipped` check for the published x86-64 Linux package. A harness invocation that supplies an
x86-64 package on ARM64 Linux fails before testing instead of recording a misleading pass.

On an x86-64 Linux guest or machine, omit `--portable-only` and supply the candidate directory. That
path verifies both Linux package payloads and performs the real Debian install/remove exercise:

```bash
npm run desktop:fusion-acceptance -- \
  --package-dir /absolute/candidate/linux \
  --output "$HOME/Desktop/preflight-linux-x64.json"
```

## Deferred operator gates

The structured document always keeps these checks deferred:

- **Signed update and rollback:** use two differently versioned, update-signed private candidates,
  the isolated rehearsal feed, and an operator-confirmed restart. Static package files can't prove
  the running application accepted, rejected, or rolled back an update.
- **Licensed-game process ownership:** run the packaged campaign scenario later against the
  operator's legitimate installation. Synthetic dry-run planning intentionally starts no process.
- **Graphics, audio, and gameplay:** use the licensed-installation checklist and label a Fusion
  result as emulated compatibility. Native beta evidence is still required.
- **Performance:** don't publish VM startup or frame-time measurements.

These aren't harness failures. They mark the boundary between a redistributable package test and a
real-installation acceptance run.

## Evidence handling

`preflight-fusion-acceptance-v1` is written atomically to a new path. The harness refuses to replace
an existing result. Review it before sharing. The result contains generated fixture metadata,
package filenames, hashes and bounded command identities; it contains no game files or synthetic
fixture payloads. Keep the candidate checksum and any manual update/game results beside it as
separate evidence rather than editing the sealed JSON.
