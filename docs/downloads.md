# Downloads and installation

Preflight is designed to ship as a desktop application plus a runnable and agent-capable JAR. The
same Java engine provides the CLI, launch wrapper, profile census, preparation, diagnostics, and
startup measurement tools.

> **No public download yet.** Current beta readiness is the four candidate/platform tasks in
> [Release readiness](release-readiness.md) and the live coordination board
> [#652](https://github.com/teamleaderleo/preflight/issues/652). The 2026-08-07 Fractal Softworks
> request is courtesy correspondence under the publication decision in
> [#950](https://github.com/teamleaderleo/preflight/issues/950).

## Planned release downloads

The first public tagged release is expected to include:

- `preflight.jar`, its SHA-256, and standalone `.zip` / `.tar.gz` archives;
- `LICENSE`, `THIRD_PARTY_NOTICES.md`, `PRIVACY.md`, and `KNOWN_LIMITATIONS.md`;
- `DEPENDENCY_INVENTORY.md`, the release CycloneDX SBOMs, and their checksum manifest;
- Windows: an NSIS `.exe` installer;
- macOS: an Apple-silicon `.dmg`;
- Linux: `.AppImage` and `.deb` packages;
- platform-qualified SHA-256 manifests;
- signed updater artifacts where the desktop updater applies; and
- `latest.json` plus its checksum for the fixed update feed.

Stable native package names are expected to remain:

- `Preflight-Windows-x86_64.exe`
- `Preflight-macOS-arm64.dmg`
- `Preflight-Linux-x86_64.AppImage`
- `Preflight-Linux-x86_64.deb`

Their platform checksum manifests are `SHA256SUMS-win32-x64.txt`,
`SHA256SUMS-darwin-arm64.txt`, and `SHA256SUMS-linux-x64.txt`.

The canonical public destination, once a release exists, is:

```text
https://github.com/teamleaderleo/preflight/releases/latest
```

Until then, private workflow artifacts are candidate evidence rather than public downloads.

## Candidate and publication flow

The package pipeline assembles and verifies the candidate artifacts before publication. Candidate
acceptance is tied to exact package hashes, one reviewed source revision, capability receipts,
checksums, dependency/SBOM material, legal/privacy/install text, and the candidate-specific evidence
listed in [Release readiness](release-readiness.md).

Updater signing and release-secret administration have a live owner in
[#720](https://github.com/teamleaderleo/preflight/issues/720). Branch and release-tag protection have
a live owner in [#607](https://github.com/teamleaderleo/preflight/issues/607). Use those issue bodies
for current Environment names, admission rules, secret migration, and repository-setting state
instead of copying an older runbook.

Public publication is a separate reviewed operation over the accepted candidate bytes. A new source
revision produces a new candidate generation and new candidate evidence.

## Platform notes for the first beta

### Windows

The first beta uses an NSIS installer without paid Authenticode identity. State the expected
SmartScreen warning before download and publish the exact package checksum beside the installer.

Verify the installer against the manifest before running it. In PowerShell, the final expression
must return `True`:

```powershell
$actual = (Get-FileHash .\Preflight-Windows-x86_64.exe -Algorithm SHA256).Hash.ToLower()
$line = (Get-Content .\SHA256SUMS-win32-x64.txt | Select-String '  Preflight-Windows-x86_64.exe$').Line
$expected = ($line -split '\s+')[0]
$actual -eq $expected
```

If Windows shows **Windows protected your PC**, and the source and digest are correct and local
policy permits the install, use **More info → Run anyway**. Preflight does not ask users to disable
SmartScreen or another system-wide protection.

### macOS

The beta package is Apple silicon. Intel macOS remains outside the first package matrix. The DMG
ships without paid Developer ID notarization, so installation guidance should explain the expected
Gatekeeper flow and provide the exact checksum.

From the download directory, verify the DMG before opening it:

```bash
grep '  Preflight-macOS-arm64.dmg$' SHA256SUMS-darwin-arm64.txt | shasum -a 256 -c -
```

If Gatekeeper blocks the verified app after the first open attempt, use **System Settings → Privacy
& Security → Open Anyway** for that app. Preflight does not ask users to turn off Gatekeeper or
apply a system-wide override.

### Linux

Publish both AppImage and Debian package forms for x86-64. The `.deb` follows the package manager for
updates; the AppImage is the desktop self-update artifact where the signed updater path applies.

Verify the package selected for installation from the download directory:

```bash
grep '  Preflight-Linux-x86_64.deb$' SHA256SUMS-linux-x64.txt | sha256sum -c -
grep '  Preflight-Linux-x86_64.AppImage$' SHA256SUMS-linux-x64.txt | sha256sum -c -
```

For Debian-family systems, install the verified package with:

```bash
sudo apt install ./Preflight-Linux-x86_64.deb
```

For the portable AppImage:

```bash
chmod +x Preflight-Linux-x86_64.AppImage
./Preflight-Linux-x86_64.AppImage
```

Desktop packages include the reviewed Preflight Java runtime and do not require a system JDK for
ordinary use.

## Installation and removal documentation

Keep public links pointed at current user documentation instead of embedding release-day procedure in
multiple files:

- [Getting started](getting-started.md)
- [Known limitations](known-limitations.md)
- [Versioning and updates](versioning-and-updates.md)
- [Release dependency inventory](dependency-inventory.md)
- [Release readiness](release-readiness.md)

Checksums, platform warnings, removal behavior, privacy/support boundaries, and release notes should
all describe the same accepted candidate generation.

## Historical release-day kit

The previous version of this file contained a longer release-day link kit and detailed operator
instructions. It also carried the superseded Fractal-response publication gate and duplicated live
signing/runbook state. The full version is retained here for reference:

[Historical pre-cleanup downloads/runbook snapshot](https://github.com/teamleaderleo/preflight/blob/6bee58e44264d222fded7ad51c04caa013d360be/docs/downloads.md)
