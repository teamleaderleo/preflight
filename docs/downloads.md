# Downloads and installation

Preflight is designed to ship as a desktop application plus a runnable and agent-capable JAR. The
same Java engine provides the CLI, launch wrapper, profile census, cache preparation, diagnostics,
and startup profiler.

> **No public download yet.** The packaging pipeline below is verified, but public distribution is
> blocked on written Fractal Softworks authorization and disclaimer guidance, unsigned-package
> installation guidance, release-candidate compatibility testing, and the remaining
> product-lifecycle work in
> [Release readiness](release-readiness.md).

## Planned release downloads

A public tagged release is expected to attach:

- `preflight.jar` — the smallest download
- `preflight.jar.sha256` — checksum for the JAR
- `preflight.zip` — JAR, checksum, and a quick-start text file
- `preflight.tar.gz` — the same files for Unix-like systems
- `archives.sha256` — checksums for both archives
- `LICENSE`, `THIRD_PARTY_NOTICES.md`, `PRIVACY.md`, and `KNOWN_LIMITATIONS.md` in both standalone
  archives and native application resources
- `DEPENDENCY_INVENTORY.md` and five CycloneDX JSON SBOMs covering the release Java graph, the
  production desktop web graph, and each supported native Rust target
- `SBOM-SHA256SUMS.txt` with checksums for those five inventories
- Windows: an NSIS `.exe` desktop installer
- macOS: a `.dmg` containing the desktop application
- Linux: `.AppImage` and `.deb` desktop packages
- a platform-qualified `SHA256SUMS-<platform>-<architecture>.txt` manifest beside each native package
- signed updater artifacts for the Windows installer, macOS application archive, and Linux AppImage
- `latest.json` and `latest.json.sha256` for the fixed GitHub update feed

Native installers use stable names such as `Preflight-Windows-x86_64.exe`,
`Preflight-macOS-arm64.dmg`, and `Preflight-Linux-x86_64.AppImage`. That lets the README and future
download page use GitHub's permanent `releases/latest/download/<asset>` redirects instead of
embedding a version in every link.

The public README will place Windows, macOS, and Linux download buttons immediately below the
project description once release packages exist. Until then it doesn't present private workflow
artifacts as public downloads.

GitHub records a download count for each attached release asset. The README may expose their total
through a small badge; it should be labelled **release downloads**, since it counts asset requests
rather than unique people or installations.

A manually dispatched Distribution workflow currently produces the same files as private workflow
artifacts without creating a release. Desktop packages contain their own minimal Java runtime and
don't require a system JDK. They also contain the project license, third-party notices, privacy
statement, and known limitations under the bundled resources. The first beta will also lack paid
Apple and Windows publisher identities. Its public install experience must state the resulting OS
warnings before download and pair every artifact with a SHA-256 manifest.

The release dependency files are described in
[Release dependency inventory](dependency-inventory.md). They are generated from the exact release
commit and published beside the platform packages; the standalone archives carry their own copies.

Tagged builds are stricter. The workflow first requires the updater private key, its password, and
the matching public key. It signs every supported updater artifact, assembles `latest.json` only
after Linux, macOS, and Windows are present, and makes the staged GitHub release public only after
every asset uploads. This Tauri signature is free and separate from Apple Developer ID or Windows
Authenticode. The desktop app checks that fixed HTTPS feed quietly and waits for an explicit
**Install and restart** action. Tauri verifies the downloaded signature before installation. A
failed download or verification leaves the current version installed. `.deb` packages don't use
the AppImage update payload; they continue through the package manager used to install them.

## Platform warnings in the first beta

The macOS DMG has no paid Developer ID identity or Apple notarization. After the first blocked open,
users who have verified the GitHub source and published checksum can approve it under **System
Settings → Privacy & Security → Open Anyway**. Apple documents that override and its security
tradeoff in [Open a Mac app from an unknown developer](https://support.apple.com/guide/mac-help/mh40616/mac).

The Windows NSIS installer has no Authenticode publisher identity. Microsoft says an unsigned new
file can show **Windows protected your PC**, and managed systems can forbid bypassing the warning.
The exact behavior and reputation model are documented in
[SmartScreen reputation for Windows app developers](https://learn.microsoft.com/windows/apps/package-and-deploy/smartscreen-reputation).

Linux CI builds both `.deb` and AppImage artifacts on Ubuntu 22.04. `.deb` is the Debian-family
package format used by Ubuntu and Debian; it doesn't mean Debian is the only supported target.
AppImage is the portable fallback for other compatible x86-64 distributions. Real installation and
licensed-game testing still determine the initial supported distribution list.

## Requirements

Java 17 or newer is required only for the standalone JAR. The native desktop package includes its
own minimal Java runtime. Starsector and Fast Rendering continue using their own bundled runtime.
Preflight launches the game through its existing launcher and passes the agent through the child
environment.

Check Java:

```bash
java -version
```

## First run

```bash
java -jar preflight.jar doctor
java -jar preflight.jar run --optimization-preset recommended
```

`doctor` prints discovered launchers and the selected candidate without starting the game.

Create a convenient platform launcher:

```bash
java -jar preflight.jar install
```

This copies the JAR into the user's Preflight directory and creates:

- macOS: `~/Applications/Preflight.app`
- Linux: `~/.local/bin/preflight` and a desktop entry
- Windows: a command launcher under Local AppData

The original Starsector installation remains untouched.

## Verify downloads

macOS or Linux:

```bash
shasum -a 256 -c preflight.jar.sha256
```

Many Linux systems also provide:

```bash
sha256sum -c preflight.jar.sha256
```

Windows PowerShell:

```powershell
(Get-FileHash .\preflight.jar -Algorithm SHA256).Hash.ToLower()
```

Compare the result with the hash in `preflight.jar.sha256`.

## Maintainer release process

Don't create a public tag until every blocking item in
[Release readiness](release-readiness.md) is closed and the bundle identifier,
updater public key, unsigned-package instructions, and disclaimer are final.

A maintainer creates and pushes an annotated version tag:

```bash
git tag -a v0.1.0 -m "Preflight v0.1.0"
git push origin v0.1.0
```

The Distribution workflow checks that the tag, frontend package, Tauri application, and Rust
package versions agree, runs the full verification suite, assembles archives, smoke-tests the
packaged JAR, then builds the desktop host and its platform-native Java runtime independently on
Linux, macOS, and Windows. Platform jobs upload private workflow artifacts. The final job builds the
signature-verified static update feed, uploads every asset to a draft, then publishes it. Any failed
verification, missing updater signature, failed upload, or failed desktop platform leaves the tag
without a public release.

### Provision the updater key

Generate the updater key outside the repository and protect it with a password:

```bash
cd preflight-desktop
npm run tauri signer generate -- -w ~/.tauri/preflight.key
```

Back up that private key and password separately. Add the private key contents to the GitHub Actions
secret `TAURI_SIGNING_PRIVATE_KEY`, its password to
`TAURI_SIGNING_PRIVATE_KEY_PASSWORD`, and the generated public key to the repository variable
`PREFLIGHT_UPDATER_PUBLIC_KEY`. The public key is compiled into tagged desktop packages; the private
key is available only to the packaging jobs and must never enter the repository or a release asset.
This follows [Tauri's signed-updater contract](https://v2.tauri.app/plugin/updater/), whose signature
verification can't be disabled.

The project key was provisioned on 2026-08-08. Its encrypted recovery copy is outside the
repository with owner-only permissions, its password is stored separately in macOS Keychain, and
GitHub Actions contains the private key and password as secrets. The public key is a repository
variable. GitHub doesn't permit reading secret values back, so the local encrypted recovery copy
must be retained even after a successful release.

### Build a private signed candidate

Run the **Distribution** workflow manually with `signed_candidate` enabled. It uses the same updater
key and exact native packaging paths as a tag, compiles the reviewed report-intake origin into the
candidate, and requires the update artifact/signature pairs on every platform. Because this is a
public repository, every candidate file is encrypted and authenticated before its first workflow-
artifact upload. The final candidate job decrypts only on its ephemeral runner, verifies complete
feed assembly, and uploads a newly encrypted set retained for 14 days. Its repository permission is
read-only, and it can't create or edit a GitHub release.

The candidate also contains `candidate-latest.json` and its checksum. Its package URLs intentionally
use the reserved `.invalid` domain, so the file proves complete feed assembly without becoming an
installable public update channel. The isolated macOS rehearsal has completed signed forward
update, rejected-signature recovery, and checked-package rollback through a temporary HTTPS
endpoint. Windows, Linux, and the final hosted candidate still need the same installed lifecycle.
Changing the candidate feed to a public release URL isn't part of this workflow.

Candidate encryption uses a separate random secret, never the updater-signing key. The secret is
stored as `PREFLIGHT_CANDIDATE_ARCHIVE_PASSWORD` in GitHub Actions and under the macOS Keychain
service `dev.starsector.preflight.candidate-artifact`. The repository contains only the versioned
AES-256-GCM envelope implementation and tests. After a successful run, download and authenticate the
candidate without printing the secret:

```bash
scripts/download-private-candidate.sh RUN_ID
```
