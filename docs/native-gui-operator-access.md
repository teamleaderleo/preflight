# Native GUI operator access

Use this with the live release operator board, not as evidence that a selected release package has
passed. Dated native observations belong in `docs/evidence/`; browser previews and CI are separate.

For game input, screenshots, and shutdown, use the [automation route selector](desktop-smoke-automation.md#choose-the-operator-route).
This document owns access to the desktop, not game-process attachment.

## Big Red and Linux

The canonical Linux checkout is `/home/leo/Projects/preflight`. From the Mac, `ssh big-red` uses
the existing SSH configuration. Check `git status`, fetch `main`, and read the repository handoff
before changing source. The game is `/home/leo/Games/starsector-0.98a-RC8`.

For visible Linux interaction, use the existing Mac connector:

```sh
/Users/leoli/.codex/skills/big-red-rdp/scripts/connect-big-red-rdp.sh --keep-mac-awake 3600
```

Its `rdp_session=ready` receipt precedes interaction with the saved Windows App device
`big-red (Tailscale tunnel)`. Credentials stay in the existing stores. The skill owns credential
recovery. The maintainer prefers windowed connections, without an initial fullscreen transition.
The existing bookmark was updated through Windows App's native `--script bookmark write` interface
with `--fullscreen false --scaling true`; reconnecting on 2026-09-06 verified a normal window.
An exact 1280×800 initial client size was not established: exported desktop dimensions stayed zero.
Preserve the saved bookmark and its credentials. Windowed RDP also avoids the observed fullscreen
automation `noWindowsAvailable` failures.
Allow the remote display to update before using coordinates from a screenshot. In the GTK chooser,
traverse Home → Games, select `starsector-0.98a-RC8`, then activate Open. Opening the chooser alone
does not verify installation selection: Preflight must reach Ready with that installation.

Build with the repository's Node version and the normal `npm run desktop:build` command in
`preflight-desktop`. Noninteractive SSH may need `/home/leo/.cargo/bin` added to PATH. The Debian
package installs `/usr/bin/starsector-preflight-desktop`. For launch/exit/recovery acceptance,
open the installed Preflight application from GNOME's application grid. Its application scope
allows the game to outlive the GUI. Starting it through a transient user service is suitable
only for bounded rendering/settings checks:

```sh
systemd-run --user --unit=preflight-gui-audit /usr/bin/starsector-preflight-desktop
# At the end of the observation:
systemctl --user stop preflight-gui-audit
```

A default systemd service uses `KillMode=control-group`: when its GUI exits, systemd also
terminates the child game. Do not interpret that service-induced exit as a product recovery
failure or use it to verify the normal close-and-reopen contract. Record the process cgroup
when investigating an unexpected child exit. The 2026-09-06 audit preserved this excluded case.

On Big Red, the AppImage bundler needs the same environment already used by desktop CI. The
2026-09-06 local failure first lacked `libfuse.so.2`, then could not resolve the bundled
`libjvm.so`. Both were resolved without changing system packages:

```sh
cd /home/leo/Projects/preflight/preflight-desktop
PATH=/home/leo/.cargo/bin:$PATH \
APPIMAGE_EXTRACT_AND_RUN=1 \
LD_LIBRARY_PATH="$PWD/src-tauri/target/engine/runtime/lib/server" \
./node_modules/.bin/tauri bundle --verbose --bundles appimage
```

This bundles an already normally built native host. Run the package verifier afterward.
`APPIMAGE_EXTRACT_AND_RUN=1` also allowed the resulting AppImage to open and launch the game in
the actual Linux desktop. Keep the initial failed logs alongside the successful receipt.

## Windows and Moonlight

Inspect the current domain and the host repository's `docs/BIG_RED_WINDOWS_SHARED.md` before
assuming Windows needs the GPU. The shared QXL/SPICE profile runs alongside Linux through
`big-red-windows-desktop`; the older passthrough procedure below is a separate recovery path.
The launcher classifies the domain and refuses a passthrough profile. Never infer the current
profile from the VM name or an old screenshot.

Windows desktop tests are silent by default at the maintainer's request. On September 15 the
shared domain's audio backend was changed from `spice` to `none` while Windows was shut off;
the sound device remains present, but QEMU discards its output instead of sending it to the
viewer, Linux speakers, or the Mac's RDP playback. This preserves the game's sound-processing
setting for test comparability. Original and silent domain XML are retained on Big Red under
`/home/leo/Projects/preflight/benchmark-results/windows-audio-20260915/`. Check the inactive XML
after replacing a domain definition. A separate Sunshine/Moonlight audio-capture path would need
its own check; this setting governs the current shared QXL/SPICE route.

Discover the existing procedure first at
`/home/leo/Projects/compute-node-bootstrap/docs/BIG_RED_WINDOWS_MOONLIGHT.md` on Big Red.
`/home/leo/Windows-Restore/tools/winvm` owns guest command access. Its `run` command uses the
existing guest SSH key and discovers the address through libvirt; do not copy the key or guess an IP.
**`winvm run`, `open`, and some other commands can start the VM.** For the shared profile,
first classify and start it with the guarded shared-display launcher above. For passthrough,
follow the host's current GPU procedure before using these commands; never detach live i915.
`winvm status` and `virsh -c qemu:///system domstate win11-starsector` inspect state.

The checked Windows checkout is `C:\Users\Leo\Projects\preflight`; another checkout exists at
`C:\Projects\starsector-preflight`, so verify the intended path and SHA. The game is
`C:\Games\Starsector`. Discover the exchange share from the current domain XML and guest volume
mapping: the September 15 shared profile used `Z:\` backed by
`/var/lib/libvirt/shares/win11-starsector`, rather than the older `/home/leo/Windows-Share`.
Use it for exchange, not live source or game files.
`winvm run hostname` verifies the existing SSH route before claiming it works.

For Moonlight, read Big Red's current `Self.DNSName` from `tailscale status --json` over SSH and use
that exact existing host target. Do not invent a tailnet suffix or add a new pairing. On the Mac:

```sh
/Applications/Moonlight.app/Contents/MacOS/Moonlight list HOST_FROM_TAILSCALE
/Applications/Moonlight.app/Contents/MacOS/Moonlight stream \
  --display-mode windowed --absolute-mouse HOST_FROM_TAILSCALE Desktop
```

`list` must include Desktop; the stream must render and accept input. The bootstrap runbook owns
the existing tailnet-only forwarding and Sunshine recovery. Do not expose its Web UI or rotate
pairing credentials for a GUI audit. A shared QXL profile can use Mesa llvmpipe for Starsector;
missing OpenGL acceleration is not proof that GPU passthrough is required. Inspect the retained
Mesa shim at `C:\Games\Starsector\jre\bin\opengl32.mesa26.2.0.llvmpipe-disabled.dll` and its
`libgallium_wgl.dll` dependency. The shim was disabled during the earlier passthrough setup.
The legacy `Play-Starsector-VM.cmd` selects `GALLIUM_DRIVER=llvmpipe`, but a normal Preflight launch
through `fr.bat` does not inherit that script's environment. Verify both the active OpenGL DLL
and the environment of the actual launch. Keep the disabled shim backup and record any host
environment change; neither a driver variable nor an old Quick Start proves the loaded renderer.

The NSIS installer respects an existing installation directory, including old temporary lifecycle
test directories. Verify the shortcut target, installed engine, and package hashes. For a durable
operator installation, the tested explicit destination is `%LOCALAPPDATA%\Programs\Preflight`;
use the installer's `/S` and final `/D=...` arguments, wait for its exit, and verify the resulting
files before launching. Keep failed attempts rather than assigning them successful evidence.

## GPU ownership and recovery

Keep `0000:00:02.0` on Linux's i915 driver for the shared Windows desktop. Do not detach live
i915, stop GDM, or introduce GPU passthrough for Preflight startup testing. The September 6
live-handover procedure is historical evidence and was superseded after the later kernel crash;
it is not an operator recipe. Preserve the installed GPU guard and crash evidence.

Before any separate recovery work, inspect the live domain and current procedures in
`/home/leo/Projects/compute-node-bootstrap`. A read-only observer may report ownership and client
conditions but cannot establish that live detachment is safe. Any reboot-based VFIO work is a
separate task; do not begin it as part of a GUI audit. Normal cleanup closes the owned viewer and
shuts down only the Windows guest, leaving Linux, SSH, and Tailscale available.

## Evidence contracts

Record source SHA, package and installed-engine identities, installation, changed values, Apply
result, reopen readback, and launch/cleanup result. Restore prior settings after temporary checks.
Remote display modes can differ from ordinary desktop modes; a previously saved custom resolution
may disappear from the choices after selecting another value. Retain the original value first.

GUI launches are correctness observations unless a measurement protocol was explicitly run. Keep
`processStartedAt → mainMenuInteractiveAt` for startup timing; screenshots, graphics-preload, and
overlay removal do not replace it. Preserve all current mods for acceleration comparisons. No
new benchmark campaign is implied by a package or GUI check.
