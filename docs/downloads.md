# Downloads and installation

Preflight is designed to ship as a desktop application plus a runnable and agent-capable JAR. The
same Java engine provides the CLI, launch wrapper, profile census, cache preparation, diagnostics,
and startup profiler.

> **No public download yet.** The packaging pipeline below is verified, but public distribution is
> blocked on written Fractal Softworks authorization and disclaimer guidance, signing
> and updater setup, release-candidate compatibility testing, and the product-lifecycle work in
> [Release readiness](release-readiness.md).

## Planned release downloads

A public tagged release is expected to attach:

- `preflight.jar` — the smallest download
- `preflight.jar.sha256` — checksum for the JAR
- `preflight.zip` — JAR, checksum, and a quick-start text file
- `preflight.tar.gz` — the same files for Unix-like systems
- `archives.sha256` — checksums for both archives
- Windows: an NSIS `.exe` desktop installer
- macOS: a `.dmg` containing the desktop application
- Linux: `.AppImage` and `.deb` desktop packages
- a platform-qualified `SHA256SUMS-<platform>-<architecture>.txt` manifest beside each native package

Native installers use stable names such as `Preflight-Windows-x86_64.exe`,
`Preflight-macOS-arm64.dmg`, and `Preflight-Linux-x86_64.AppImage`. That lets the README and future
download page use GitHub's permanent `releases/latest/download/<asset>` redirects instead of
embedding a version in every link.

The public README will place Windows, macOS, and Linux download buttons immediately below the
project description once signed release packages exist. Until then it doesn't present private,
unsigned workflow artifacts as public downloads.

GitHub records a download count for each attached release asset. The README may expose their total
through a small badge; it should be labelled **release downloads**, since it counts asset requests
rather than unique people or installations.

A manually dispatched Distribution workflow currently produces the same files as private workflow
artifacts without creating a release. Desktop packages contain their own minimal Java runtime and
don't require a system JDK. Those development packages are unsigned; they aren't the intended
public install experience.

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
signing identity, updater public key, and disclaimer are final.

A maintainer creates and pushes an annotated version tag:

```bash
git tag -a v0.1.0 -m "Preflight v0.1.0"
git push origin v0.1.0
```

The Distribution workflow runs the full verification suite, assembles archives, smoke-tests the
packaged JAR, then builds the desktop host and its platform-native Java runtime independently on
Linux, macOS, and Windows. It uploads workflow artifacts and adds successful native packages to the
GitHub release created from the existing tag. A failed core verification leaves the tag without a
published release; a failed desktop platform doesn't cancel the other platform builds.
