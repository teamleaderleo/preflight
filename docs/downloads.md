# Downloads and installation

Preflight is designed to ship as a desktop application plus a runnable and agent-capable JAR. The
same Java engine provides the CLI, launch wrapper, profile census, preparation, diagnostics, and
startup measurement tools.

> **No public download yet.** Current beta readiness is operational candidate work: release-signing
> setup/private rehearsal, freezing one candidate generation, native Windows/Linux game exercise,
> and the package-bound benchmark/lifecycle/report evidence in
> [Release readiness](release-readiness.md) and the live coordination board
> [#652](https://github.com/teamleaderleo/preflight/issues/652). The 2026-08-07 Fractal Softworks
> request is courtesy correspondence under the publication decision in
> [#950](https://github.com/teamleaderleo/preflight/issues/950).

## Planned release downloads

The first public tagged release is expected to include:

- `preflight.jar`, its SHA-256, and standalone `.zip` / `.tar.gz` archives;
- `LICENSE`, `THIRD_PARTY_NOTICES.md`, `PRIVACY.md`, and `KNOWN_LIMITATIONS.md`;
- `DEPENDENCY_INVENTORY.md`, the release CycloneDX SBOMs, and their checksum manifest;
- Windows: `Preflight-Windows-x86_64.exe` with `SHA256SUMS-win32-x64.txt`;
- macOS: `Preflight-macOS-arm64.dmg` with `SHA256SUMS-darwin-arm64.txt`;
- Linux: `Preflight-Linux-x86_64.AppImage` and `Preflight-Linux-x86_64.deb` with
  `SHA256SUMS-linux-x64.txt`;
- signed updater artifacts where the desktop updater applies; and
- `latest.json` plus its checksum for the fixed update feed.

The canonical public destination, once a release exists, is:

```text
https://github.com/teamleaderleo/preflight/releases/latest
```

Until then, private workflow artifacts are candidate evidence rather than public downloads.

## Candidate and publication flow

The package pipeline assembles and verifies candidate artifacts before publication. Candidate
acceptance is tied to package hashes, one reviewed source revision, capability receipts, checksums,
dependency/SBOM material, legal/privacy/install text, and the candidate-specific evidence listed in
[Release readiness](release-readiness.md).

Updater signing and release-secret administration have a live owner in
[#720](https://github.com/teamleaderleo/preflight/issues/720). Repository rulesets are intentionally
not part of the current release gate; #607 was retired on 2026-08-21 under the owner-selected policy.
Before approving a tagged deployment, verify through the `release-signing` Environment that the
selected tag/commit is the intended frozen accepted `main` identity.

Public publication is a separate reviewed operation over the accepted candidate bytes. A new source
revision produces a new candidate generation and requires affected package evidence to be collected
again.

## Verify a native download before opening it

Use the checksum manifest downloaded from the same accepted release. These commands verify only the
named package; they do not disable an operating-system security control.

### macOS

```bash
grep '  Preflight-macOS-arm64.dmg$' SHA256SUMS-darwin-arm64.txt | shasum -a 256 -c -
```

The beta package is Apple silicon. Intel macOS remains outside the first package matrix. The DMG
ships without paid Developer ID notarization, so Gatekeeper can block the first launch. After the
checksum succeeds, use **System Settings → Privacy & Security** and the specific **Open Anyway**
control for Preflight if macOS presents it. Keep Gatekeeper enabled and use only the per-app
override above; do not strip quarantine metadata broadly.

### Windows

In PowerShell:

```powershell
$expected = (Get-Content SHA256SUMS-win32-x64.txt | Where-Object { $_ -match '  Preflight-Windows-x86_64.exe$' }).Substring(0, 64).ToLowerInvariant()
$actual = (Get-FileHash -Algorithm SHA256 .\Preflight-Windows-x86_64.exe).Hash.ToLowerInvariant()
$actual -eq $expected
```

The first beta uses an NSIS installer without paid Authenticode identity, so SmartScreen can warn on
first run. After the checksum succeeds, use the warning's **More info → Run anyway** path for that
specific installer if Windows presents it. Keep SmartScreen and antivirus enabled; use only the
per-installer path above.

### Linux

For the Debian package:

```bash
grep '  Preflight-Linux-x86_64.deb$' SHA256SUMS-linux-x64.txt | sha256sum -c -
sudo apt install ./Preflight-Linux-x86_64.deb
```

For the AppImage:

```bash
grep '  Preflight-Linux-x86_64.AppImage$' SHA256SUMS-linux-x64.txt | sha256sum -c -
chmod +x Preflight-Linux-x86_64.AppImage
./Preflight-Linux-x86_64.AppImage
```

The `.deb` follows the package manager for updates; the AppImage is the desktop self-update artifact
where the signed updater path applies.

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
