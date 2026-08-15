# Getting started with Preflight

Preflight makes a heavily modded Starsector start faster. It does the slow, repetitive work once —
decoding textures, merging mod data, generating bytecode — keeps the result, and reuses it on every
later launch of the same game and mod profile.

This page is the whole path from download to a faster launch. It assumes nothing except that you
have Starsector installed and some mods.

## 1. Download

Grab the package for your system from the
**[latest release](https://github.com/teamleaderleo/preflight/releases/latest)**:

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

## 4. Prepare and launch

Click **Prepare and launch**. Preflight automatically uses the reviewed optimizations and its normal
storage choice, then opens Starsector when preparation finishes. You don't need to choose a mode.

Before it writes anything, Preflight calculates how much disk the prepared data will need and
refuses to start if the conservative estimate wouldn't leave at least 1 GiB free. On the 83-mod
profile used in development the finished cache was about 4.5 GB. If that doesn't fit, the same
screen offers a much smaller preparation instead.

## 5. Let the first preparation finish

Preparation is the slow step, and it only happens when your game or mod profile changes. It shows
which phase it's on and can be cancelled at any point without leaving anything half-written.

Expect a couple of minutes on a large profile. This is the work you're moving off every future
launch.

Preflight isn't linking or rewriting the installation. It reads the game build, enabled mods, and
the files those mods contribute, gives that exact combination an identity, then writes reusable
answers under its own data folder. A different combination gets a different identity. If the check
can't be completed, Preflight leaves the prepared data alone and still offers a normal-speed launch.

## 6. Launch

After that first run, the big button simply starts Starsector. Resolution, battle size, RAM,
antialiasing, UI scale, fullscreen, and sound sit beside it, so you don't need the vanilla launcher
for the usual settings.

![Preflight ready to launch an 83-mod profile](images/walkthrough-ready.png)

## 7. Optional: measure it

The built-in benchmark runs one normal launch and one Preflight launch and compares the time each
took to reach the main menu. That's your number, on your machine, with your mods — more useful than
anyone else's.

![Preflight startup benchmark](images/walkthrough-benchmark.png)

## If something goes wrong

Preflight doesn't rewrite game or mod archives or save files. Every optimization is pinned to the
exact game and mod code it was reviewed against; if what it finds doesn't match, that optimization
declines and the original code runs. A future Starsector release can still need a Preflight update.

There's also a global switch that turns every runtime change off, and the game still launches.

If something goes wrong, open **Help**. It can build a support file that shows you the exact
contents, the exclusions, the size, and the checksum before anything is sent, and sending is a
separate, explicit choice. It never includes saves, game or mod assets, screenshots, audio, or
arbitrary logs. A failed launch offers the same page directly from the card that reports it.

## Removing it

To reclaim space while keeping Preflight, choose **Free space** on Home. The review keeps the
current and saved profiles ready, keeps a small recent set of reports and benchmarks, and removes
older unreachable data. Nothing is removed until you confirm the measured plan.

Routine reports don't accumulate indefinitely: while the desktop is open and idle, Preflight keeps
the 10 newest launch reports and 5 newest benchmarks automatically. **Free space** remains useful
for inspecting prepared-data cleanup or retrying maintenance that couldn't run earlier.

Two separate choices, both preview-first:

- **App only** — removes Preflight and leaves the prepared caches, in case you reinstall.
- **All Preflight data** — also clears caches, profiles, evidence, and backups.

Neither one touches Starsector, your mods, your saves, or your game preferences.
