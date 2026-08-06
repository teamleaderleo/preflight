# Downloads and installation

Preflight is distributed as one runnable and agent-capable JAR. The same file provides the CLI, launch wrapper, profile census, resource index tools, and startup profiler.

## Release downloads

Tagged releases attach:

- `preflight.jar` — the smallest download
- `preflight.jar.sha256` — checksum for the JAR
- `starsector-preflight.zip` — JAR, checksum, and a quick-start text file
- `starsector-preflight.tar.gz` — the same files for Unix-like systems
- `archives.sha256` — checksums for both archives
- Windows: an NSIS `.exe` desktop installer
- macOS: a `.dmg` containing the desktop application
- Linux: `.AppImage` and `.deb` desktop packages
- a platform-qualified `SHA256SUMS-<platform>-<architecture>.txt` manifest beside each native package

A manually dispatched Distribution workflow produces the same files as 30-day workflow artifacts
without creating a release. Desktop packages contain their own minimal Java runtime and do not
require a system JDK. They are currently unsigned beta artifacts, so Windows and macOS may show
publisher or Gatekeeper warnings until release signing credentials are configured.

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
java -jar preflight.jar run
```

`doctor` prints discovered launchers and the selected candidate without starting the game.

Create a convenient platform launcher:

```bash
java -jar preflight.jar install
```

This copies the JAR into the user's Preflight directory and creates:

- macOS: `~/Applications/Starsector Preflight.app`
- Linux: `~/.local/bin/starsector-preflight` and a desktop entry
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

## Release process

A maintainer creates and pushes an annotated version tag:

```bash
git tag -a v0.1.0 -m "Starsector Preflight v0.1.0"
git push origin v0.1.0
```

The Distribution workflow runs the full verification suite, assembles archives, smoke-tests the
packaged JAR, then builds the desktop host and its platform-native Java runtime independently on
Linux, macOS, and Windows. It uploads workflow artifacts and adds successful native packages to the
GitHub release created from the existing tag. A failed core verification leaves the tag without a
published release; one failed desktop platform does not cancel the other platform builds.
