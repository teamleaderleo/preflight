# Getting started with Preflight

Preflight makes a heavily modded Starsector start faster. It does the slow, repetitive work once —
decoding textures, merging mod data, generating bytecode — keeps the result, and reuses it on every
later launch of the same game and mod profile.

This page is the whole path from download to a faster launch. It assumes nothing except that you
have Starsector installed and some mods.

## 1. Download

Grab the package for your system from **[RELEASE URL]**:

| System | File |
| --- | --- |
| Windows | `Preflight-Windows-x86_64.exe` |
| macOS (Apple silicon) | `Preflight-macOS-arm64.dmg` |
| Linux | `Preflight-Linux-x86_64.AppImage` or the `.deb` |

There is no Intel Mac build in the beta.

Each package has a `SHA256SUMS-<platform>-<architecture>.txt` beside it if you want to check the
download before opening it.

## 2. Get past the security warning

Preflight isn't signed with a paid Apple or Microsoft developer identity, so both systems will warn
you the first time. This is expected, and the warning is about the absence of a paid certificate,
not about anything found in the file.

- **macOS** — the first open is refused. Open **System Settings → Privacy & Security**, scroll to
  the message naming Preflight, and choose **Open Anyway**.
- **Windows** — SmartScreen shows "Windows protected your PC". Choose **More info**, then
  **Run anyway**.
- **Linux** — mark the AppImage executable (`chmod +x`), or install the `.deb` with your package
  manager.

If you would rather not do this, the checksums and the full source are published; you can build it
yourself.

## 3. Point it at Starsector

On first open, Preflight looks in the usual install locations. If it finds your game, there's
nothing to do.

If it doesn't, choose the folder that contains `Starsector.app`, `starsector.exe`, or
`starsector.sh`. Preflight reads the mod profile you already have enabled — it doesn't manage or
change your mod list.

![Preflight asking for a Starsector installation](images/walkthrough-setup.png)

## 4. Leave the defaults alone

**Recommended** and **Balanced** are the defaults and are what you want. Recommended enables the
reviewed set of optimizations; Balanced trades some disk for speed without going overboard.

Before it writes anything, Preflight calculates how much disk the prepared data will need and
refuses to start if the conservative estimate wouldn't leave at least 1 GiB free. On the 83-mod
profile used in development the finished cache was about 4.5 GB.

## 5. Prepare, once

Preparation is the slow step, and it only happens when your game or mod profile changes. It shows
which phase it's on and can be cancelled at any point without leaving anything half-written.

Expect a couple of minutes on a large profile. This is the work you're moving off every future
launch.

## 6. Launch

The big button starts Starsector. Resolution, battle size, RAM, antialiasing, UI scale, fullscreen,
and sound sit beside it, so you don't need the vanilla launcher for the usual settings.

![Preflight ready to launch an 83-mod profile](images/walkthrough-ready.png)

## 7. Optional: see what it did for you

The built-in benchmark runs one normal launch and one Preflight launch and compares the time each
took to reach the main menu. That's your number, on your machine, with your mods — more useful than
anyone else's.

![Preflight benchmark and support controls](images/walkthrough-benchmark.png)

## If something goes wrong

Preflight is built to fail quietly rather than break your game. Every optimization is pinned to the
exact game and mod code it was reviewed against; if what it finds doesn't match, it declines and the
original code runs. An unknown Starsector version means fewer optimizations apply, not a broken
install.

There's also a global switch that turns every runtime change off, and the game still launches.

If you want to report something, the app can build a support ZIP. It shows you the exact contents,
the exclusions, the size, and the checksum before anything is sent, and sending is a separate,
explicit choice. It never includes saves, game or mod assets, screenshots, audio, or arbitrary logs.

## Removing it

Two separate choices, both preview-first:

- **App only** — removes Preflight and leaves the prepared caches, in case you reinstall.
- **All Preflight data** — also clears caches, profiles, evidence, and backups.

Neither one touches Starsector, your mods, your saves, or your game preferences.
