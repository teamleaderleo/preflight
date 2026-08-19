# Downloads and installation

Preflight is designed to ship as a desktop application plus a runnable and agent-capable JAR. The
same Java engine provides the CLI, launch wrapper, profile census, cache preparation, diagnostics,
and startup profiler.

> **No public download yet.** The packaging pipeline below is verified. Public release waits on an
> accepted hosted candidate, final package-lifecycle checks, and the maintainer's publication
> decision after the requested Fractal Softworks guidance window. The exact state is in
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

## Release-day link kit

`preflight-desktop/scripts/collect-bundles.mjs` renames every native package to
`Preflight-<platform>-<architecture>.<extension>` before upload, so the links below resolve as soon
as a release attaches those assets and keep resolving for every release after it. Write these, never
a versioned URL — a versioned link in a forum post is wrong the moment you ship again.

One canonical link for anything that needs a single destination, because it carries the packages,
the checksums, and the notes together:

```
https://github.com/teamleaderleo/preflight/releases/latest
```

### README

```markdown
[![release downloads](https://img.shields.io/github/downloads/teamleaderleo/preflight/total?label=release%20downloads)](https://github.com/teamleaderleo/preflight/releases/latest)

### Download

| | |
| --- | --- |
| **Windows** | [Preflight-Windows-x86_64.exe](https://github.com/teamleaderleo/preflight/releases/latest/download/Preflight-Windows-x86_64.exe) |
| **macOS** (Apple silicon) | [Preflight-macOS-arm64.dmg](https://github.com/teamleaderleo/preflight/releases/latest/download/Preflight-macOS-arm64.dmg) |
| **Linux** | [.AppImage](https://github.com/teamleaderleo/preflight/releases/latest/download/Preflight-Linux-x86_64.AppImage) · [.deb](https://github.com/teamleaderleo/preflight/releases/latest/download/Preflight-Linux-x86_64.deb) |

Checksums and every other artifact are on the [latest release](https://github.com/teamleaderleo/preflight/releases/latest).
New here? [Getting started](docs/getting-started.md) is the whole path from download to a faster launch.
```

### Starsector forum

The forum runs SMF, which takes BBCode rather than Markdown — pasting the Markdown post there
renders literal asterisks and bare link syntax. Keep a BBCode copy of the announcement.

```bbcode
[b]Download[/b]

[list]
[li][url=https://github.com/teamleaderleo/preflight/releases/latest/download/Preflight-Windows-x86_64.exe]Windows (.exe installer)[/url][/li]
[li][url=https://github.com/teamleaderleo/preflight/releases/latest/download/Preflight-macOS-arm64.dmg]macOS, Apple silicon (.dmg)[/url][/li]
[li][url=https://github.com/teamleaderleo/preflight/releases/latest/download/Preflight-Linux-x86_64.AppImage]Linux (.AppImage)[/url] or [url=https://github.com/teamleaderleo/preflight/releases/latest/download/Preflight-Linux-x86_64.deb].deb[/url][/li]
[/list]

Checksums and source: [url=https://github.com/teamleaderleo/preflight/releases/latest]github.com/teamleaderleo/preflight[/url]
```

### Reddit and Discord

Reddit tables render inconsistently on old.reddit, so use a list. Both accept Markdown links.

```markdown
**Download** — [Windows](https://github.com/teamleaderleo/preflight/releases/latest/download/Preflight-Windows-x86_64.exe) · [macOS (Apple silicon)](https://github.com/teamleaderleo/preflight/releases/latest/download/Preflight-macOS-arm64.dmg) · [Linux AppImage](https://github.com/teamleaderleo/preflight/releases/latest/download/Preflight-Linux-x86_64.AppImage) · [.deb](https://github.com/teamleaderleo/preflight/releases/latest/download/Preflight-Linux-x86_64.deb)

Checksums, source, and release notes: <https://github.com/teamleaderleo/preflight/releases/latest>
```

### Intel Macs: not in the beta

The desktop matrix builds macOS on `macos-latest`, which is Apple silicon, so the only macOS asset
is `Preflight-macOS-arm64.dmg`. An Intel Mac user who follows a macOS link gets a package that will
not run, so every macOS link has to be labelled **Apple silicon** and the announcement's known-limits
list has to say there is no Intel build. That is the decision for the beta: no `x86_64` macOS matrix
entry, and the gap stated rather than papered over. Adding the entry later is a matrix change, not a
code change, so nothing here forecloses it.

A manually dispatched Distribution workflow currently produces the same files as private workflow
artifacts without creating a release. Desktop packages contain their own minimal Java runtime and
don't require a system JDK. They also contain the project license, third-party notices, privacy
statement, and known limitations under the bundled resources. The first beta will also lack paid
Apple and Windows publisher identities. Its public install experience must state the resulting OS
warnings before download and pair every artifact with a SHA-256 manifest.

The release dependency files are described in
[Release dependency inventory](dependency-inventory.md). They are generated from the exact release
commit and published beside the platform packages; the standalone archives carry their own copies.

Tagged builds are stricter. Updater-signing jobs run behind the GitHub Environment named
`release-signing` and require its `RELEASE_TAURI_SIGNING_PRIVATE_KEY` and
`RELEASE_TAURI_SIGNING_PRIVATE_KEY_PASSWORD` secrets plus the matching
`PREFLIGHT_UPDATER_PUBLIC_KEY` variable. They sign every supported updater artifact and assemble
`latest.json` only after Linux, macOS, and Windows are present. Distribution stages the verified
assets as a draft release. Public publication happens later through **Publish verified release**,
which rechecks the exact preserved Distribution bytes, requires `release-signing` approval, and
requires the tag commit to be reachable from `main` before undrafting. This Tauri signature is free
and separate from Apple Developer ID or Windows Authenticode. The desktop app checks that fixed
HTTPS feed quietly and waits for an explicit **Install and restart** action. Tauri verifies the
downloaded signature before installation. A failed download or verification leaves the current
version installed. `.deb` packages don't use the AppImage update payload; they continue through the
package manager used to install them.

## Platform warnings in the first beta

The macOS DMG has no paid Developer ID identity or Apple notarization. After the first blocked open,
users who have verified the GitHub source and published checksum can approve it under **System
Settings → Privacy & Security → Open Anyway**. Apple documents that override and its security
tradeoff in [Open a Mac app from an unknown developer](https://support.apple.com/guide/mac-help/mh40616/mac).

The Windows NSIS installer has no Authenticode publisher identity. Microsoft says an unsigned new
file can show **Windows protected your PC**, and managed systems can forbid bypassing the warning.
The exact behavior and reputation model are documented in
[SmartScreen reputation for Windows app developers](https://learn.microsoft.com/windows/apps/package-and-deploy/smartscreen-reputation).

After the first public release, the project can apply to
[SignPath Foundation's free open-source signing program](https://signpath.org/terms.html). Its
current eligibility rules require an already released, maintained OSI-licensed project, reviewed
repository-to-binary provenance, and manual approval for each signing request. Acceptance isn't
assumed, and this optional follow-up doesn't change the unsigned first-beta boundary.

Linux CI builds both `.deb` and AppImage artifacts on Ubuntu 22.04. `.deb` is the Debian-family
package format used by Ubuntu and Debian; it doesn't mean Debian is the only supported target.
AppImage is the portable fallback for other compatible x86-64 distributions. Real installation and
real-game testing still determine the initial supported distribution list.

## Install the desktop app

Download the package and the matching `SHA256SUMS-<platform>-<architecture>.txt` from the same
GitHub release. Verify both came from `teamleaderleo/preflight` before overriding an operating
system warning. Preflight never asks users to disable Gatekeeper, SmartScreen, antivirus, or another
system-wide protection.

### macOS (Apple silicon)

From the download directory, verify the DMG:

```bash
grep '  Preflight-macOS-arm64.dmg$' SHA256SUMS-darwin-arm64.txt | shasum -a 256 -c -
```

Open `Preflight-macOS-arm64.dmg` and drag **Preflight** to **Applications**. Try to open the copied
app once. If Gatekeeper blocks it, open **System Settings → Privacy & Security**, find the blocked
Preflight message under **Security**, choose **Open Anyway**, and authenticate. Apple makes that
override available for about an hour after the blocked attempt and warns that it should be used
only when the app's source has been checked. See
[Apple's current unknown-developer instructions](https://support.apple.com/guide/mac-help/mh40616/mac).

To remove only the desktop package, quit Preflight and move `/Applications/Preflight.app` to the
Trash. To remove its caches and other owned data too, first use **Settings → Remove Preflight → All
Preflight data** inside the app, review the paths, and confirm; then remove the app.

### Windows 11 (x86-64)

PowerShell compares the package digest with its line in the manifest. The final command must print
`True`:

```powershell
$actual = (Get-FileHash .\Preflight-Windows-x86_64.exe -Algorithm SHA256).Hash.ToLower()
$line = (Get-Content .\SHA256SUMS-win32-x64.txt | Select-String '  Preflight-Windows-x86_64.exe$').Line
$expected = ($line -split '\s+')[0]
$actual -eq $expected
```

Run the installer only after that comparison succeeds. An unsigned new build can show
**Windows protected your PC**. If the source and digest are correct and local policy permits it,
choose **More info → Run anyway**. Microsoft documents that unsigned files start without publisher
reputation and that managed policy can remove the bypass entirely; Preflight doesn't ask users to
weaken that policy. See
[Microsoft's SmartScreen reputation guidance](https://learn.microsoft.com/windows/apps/package-and-deploy/smartscreen-reputation).
Windows in S mode can't install this package without the separate, one-way decision to leave S
mode.

Remove the desktop package through **Settings → Apps → Installed apps → Preflight → Uninstall**.
Use Preflight's reviewed **All Preflight data** removal first when caches, profiles, evidence, and
backups should also be removed.

### Ubuntu or Debian family (x86-64)

Verify the downloaded `.deb` from the download directory:

```bash
grep '  Preflight-Linux-x86_64.deb$' SHA256SUMS-linux-x64.txt | sha256sum -c -
```

For a normal system installation, use the `.deb`; APT can resolve dependencies from the configured
distribution repositories:

```bash
sudo apt install ./Preflight-Linux-x86_64.deb
```

Ubuntu documents this local-package form in its
[software-management guide](https://ubuntu.com/server/docs/tutorial/managing-software/). Remove the
package with `sudo apt remove preflight`. As on the other platforms, use the app's reviewed all-data
removal first if its separate data shouldn't remain.

### Other compatible Linux systems (x86-64)

The AppImage is a portable fallback and doesn't install system files. Make the verified file
executable and run it:

```bash
grep '  Preflight-Linux-x86_64.AppImage$' SHA256SUMS-linux-x64.txt | sha256sum -c -
chmod +x Preflight-Linux-x86_64.AppImage
./Preflight-Linux-x86_64.AppImage
```

Those are the steps in the [AppImage quick start](https://docs.appimage.org/introduction/quickstart.html).
Delete the AppImage to remove the application itself. Use **All Preflight data** inside Preflight
before deleting it when its caches and other owned data should also be removed. Initial support for
a particular distribution still depends on the native beta evidence described below.

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

## Verify the standalone JAR

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
mkdir -p docs/releases
# Write and review docs/releases/0.1.0.md first.
npm --prefix preflight-desktop run release:version -- 0.1.0
node preflight-desktop/scripts/validate-release-version.mjs v0.1.0
git tag -a v0.1.0 -m "Preflight v0.1.0"
git push origin v0.1.0
```

The Distribution workflow checks that the tag, frontend package and lockfile, Tauri application,
Rust package and lockfile, and every Maven reactor module agree. It then runs the full verification
suite, assembles archives, smoke-tests the packaged JAR, and builds the desktop host and its
platform-native Java runtime independently on Linux, macOS, and Windows. Platform jobs upload
private workflow artifacts. The final job builds the signature-verified static update feed and
uploads every verified asset to a draft GitHub release. The separate manually dispatched
**Publish verified release** workflow requires `release-signing` approval, verifies that the tag
commit is reachable from `main`, downloads the preserved successful Distribution artifact, compares
the draft assets byte-for-byte with it, runs the complete-release verifier again, and only then
undrafts the release. The reviewed `docs/releases/<version>.md` file supplies both the updater notes
and GitHub release body; a missing or empty file stops candidate assembly. Any failed verification,
missing updater signature, failed upload, or failed desktop platform leaves the tag without a public
release.

### Provision the updater key

Generate the updater key outside the repository and protect it with a password:

```bash
cd preflight-desktop
npm run tauri signer generate -- -w ~/.tauri/preflight.key
```

Back up that private key and password separately. In GitHub, create/configure the Environment named
`release-signing`, admit branch `main` and release tags `v*`, and add the private key contents as the
Environment secret `RELEASE_TAURI_SIGNING_PRIVATE_KEY` plus its password as
`RELEASE_TAURI_SIGNING_PRIVATE_KEY_PASSWORD`. Keep the generated public key in the variable
`PREFLIGHT_UPDATER_PUBLIC_KEY`. The public key is compiled into tagged desktop packages; the private
key becomes available to updater-signing jobs only after the Environment protection rules pass and
must never enter the repository or a release asset. This follows
[Tauri's signed-updater contract](https://v2.tauri.app/plugin/updater/), whose signature verification
can't be disabled.

The project key was provisioned on 2026-08-08. Its encrypted recovery copy is outside the repository
with owner-only permissions, and its password is stored separately in macOS Keychain. GitHub does
not permit reading secret values back, so the local encrypted recovery copy must be retained after a
successful release. The release workflow now reads only the two `RELEASE_*` Environment secret
names. After the Environment-backed signed candidate passes, remove repository-level legacy secrets
named `TAURI_SIGNING_PRIVATE_KEY` and `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`, plus any temporary
repository-level duplicate of the two `RELEASE_*` names.

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
