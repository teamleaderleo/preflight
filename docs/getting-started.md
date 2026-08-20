# Getting started with Preflight

Preflight makes a heavily modded Starsector start faster by preparing work the game and mods would
otherwise repeat during startup, then reusing that work while the relevant game and mod inputs still
match.

This is the ordinary path from download to launch. The deeper profile, benchmark, storage, support,
and CLI tools can wait until you want them.

## 1. Download

Grab the package for your system from the
**[latest release](https://github.com/teamleaderleo/preflight/releases/latest)**:

| System | File |
| --- | --- |
| Windows | `Preflight-Windows-x86_64.exe` |
| macOS (Apple silicon) | `Preflight-macOS-arm64.dmg` |
| Linux | `Preflight-Linux-x86_64.AppImage` or the `.deb` |

The first beta has no Intel Mac package. Each native package has a matching
`SHA256SUMS-<platform>-<architecture>.txt` on the release page for anyone who wants to verify the
download before opening it.

## 2. Open the package

The first beta ships without paid Apple Developer ID or Windows Authenticode publisher identities,
so macOS and Windows can show their standard unknown-developer warnings.

- **macOS:** try to open Preflight once, then use **System Settings → Privacy & Security → Open
  Anyway** for the blocked app.
- **Windows:** if SmartScreen shows **Windows protected your PC** and local policy allows it, choose
  **More info → Run anyway**.
- **Linux:** install the `.deb` through the package manager, or mark the AppImage executable with
  `chmod +x` and run it.

The release page also carries the source and checksums. More detailed platform instructions live in
[Downloads and installation](downloads.md).

## 3. Point Preflight at Starsector

On first open, Preflight checks the usual installation folders. If it finds Starsector, keep going;
if it does not, choose the folder containing `Starsector.app`, `starsector.exe`, or `starsector.sh`.

Preflight starts from the mod profile you already have enabled. Later, named profiles can switch that
enabled-mod selection after showing the change and saving a backup; Preflight does not install or
update mods.

![Preflight asking for a Starsector installation](images/walkthrough-setup.png)

## 4. Prepare and launch

Press **Prepare and launch**. Recommended optimization and Balanced storage are the normal choices,
so the first run does not require a preset seminar before you can play the game.

Before preparation begins, Preflight calculates the current profile's disk requirement, accounts for
reusable data and free space, and keeps a reserve. On the reviewed 83-mod development profile,
Balanced retained **4.76 GB** after one measured preparation; Minimal disk retained **10.9 MB** and
Fastest retained **10.03 GB**. Those are reference numbers from one profile, and the desktop shows
the plan for the installation in front of you.

If Balanced will not fit, Preflight can offer **Prepare with minimal disk** instead.

## 5. Let the first preparation finish

A large first preparation can take several minutes because this is the work being moved out of later
launches. The desktop shows progress and can stop safely; completed reusable work can survive an
interruption, while a new prepared profile becomes active only after preparation finishes.

Prepared data lives under Preflight's own data area. A changed game or mod input gets checked again,
and work that can no longer be reused goes through the ordinary Starsector path. If preparation data
cannot be used at all, Home can still offer **Launch at normal speed**.

## 6. Launch Starsector

After the profile is prepared, the large Home button starts Starsector and the routine settings sit
beside it: resolution, battle size, RAM, antialiasing, UI scale, fullscreen, and sound. On a standard
installation, the extended battle-size presets can go through **2,000 deployment points** while
still writing Starsector's own preference.

Once Preflight confirms the actual game process is alive, the desktop minimizes by default; Settings
can instead keep it open or quit it after launch, and local playtime recording continues for the
session either way.

![Preflight ready to launch an 83-mod profile](images/walkthrough-ready.png)

## 7. Measure your own result

The built-in benchmark runs an ordinary launch and a Preflight launch on the current installation and
compares their time to the main menu. The development result is useful context; this pair is the one
that tells you what Preflight does on your machine with your mods.

**Copy result** produces a compact shareable comparison without private paths, logs, or the complete
run record.

![Preflight startup benchmark](images/walkthrough-benchmark.png)

## If something goes wrong

Starsector JARs, mod JARs, executables, assets, and saves stay outside the acceleration path. Runtime
optimizations live inside the launched game process, and when reviewed game or mod code changes the
affected shortcut steps aside and Starsector handles that work normally. A sufficiently large game,
launcher, or runtime update can still require a Preflight update.

For troubleshooting, **Off / troubleshooting** disables the runtime optimization layer while leaving
the launcher, profiles, settings, process handling, and support tools available.

If a launch fails, Home offers Relaunch, **Copy setup**, Get help, and Dismiss. **Copy setup** is the
easy public-support summary; Help can create a deeper support ZIP, show what it contains before
sending, and keep sending as a separate action. Ordinary game launches upload no logs or telemetry,
and automatic failed-run reporting starts off.

## Freeing space or removing Preflight

**Free space** previews what Preflight can remove while keeping the current profile and readable named
profiles available. Routine reports and benchmark sessions have their own retention so old
diagnostics can be trimmed independently from the acceleration data.

Removal has two scopes. **App only** removes the application while leaving Preflight data available
for a later reinstall; **All Preflight data** also clears Preflight-owned caches, profiles,
diagnostics, and backups after showing the targets. Starsector, mods, saves, and game preferences sit
outside both removal scopes.
