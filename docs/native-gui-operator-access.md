# Native GUI operator access

Use this with the live release operator board, not as evidence that a selected release package has
passed. Dated native observations belong in `docs/evidence/`; browser previews and CI are separate.

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
recovery. Windowed RDP avoids the observed fullscreen automation `noWindowsAvailable` failures.
Allow the remote display to update before using coordinates from a screenshot. In the GTK chooser,
traverse Home → Games, select `starsector-0.98a-RC8`, then activate Open. Opening the chooser alone
does not verify installation selection: Preflight must reach Ready with that installation.

Build with the repository's Node version and the normal `npm run desktop:build` command in
`preflight-desktop`. Noninteractive SSH may need `/home/leo/.cargo/bin` added to PATH. The Debian
package installs `/usr/bin/starsector-preflight-desktop`. Starting it through a transient user
service uses the existing graphical session environment:

```sh
systemd-run --user --unit=preflight-gui-audit /usr/bin/starsector-preflight-desktop
# At the end of the observation:
systemctl --user stop preflight-gui-audit
```

## Windows and Moonlight

Discover the existing procedure first at
`/home/leo/Projects/compute-node-bootstrap/docs/BIG_RED_WINDOWS_MOONLIGHT.md` on Big Red.
`/home/leo/Windows-Restore/tools/winvm` owns guest command access. Its `run` command uses the
existing guest SSH key and discovers the address through libvirt; do not copy the key or guess an IP.
**`winvm run`, `open`, and some other commands can start the VM. Use them only after GPU handover.**
`winvm status` and `virsh -c qemu:///system domstate win11-starsector` inspect state.

The checked Windows checkout is `C:\Users\Leo\Projects\preflight`; another checkout exists at
`C:\Projects\starsector-preflight`, so verify the intended path and SHA. The game is
`C:\Games\Starsector`. `Z:\` maps to `/home/leo/Windows-Share`; use it for exchange, not live source
or game files. `winvm run hostname` verifies the existing SSH route before claiming it works.

For Moonlight, read Big Red's current `Self.DNSName` from `tailscale status --json` over SSH and use
that exact existing host target. Do not invent a tailnet suffix or add a new pairing. On the Mac:

```sh
/Applications/Moonlight.app/Contents/MacOS/Moonlight list HOST_FROM_TAILSCALE
/Applications/Moonlight.app/Contents/MacOS/Moonlight stream \
  --display-mode windowed --absolute-mouse HOST_FROM_TAILSCALE Desktop
```

`list` must include Desktop; the stream must render and accept input. The bootstrap runbook owns
the existing tailnet-only forwarding and Sunshine recovery. Do not expose its Web UI or rotate
pairing credentials for a GUI audit. The old Windows Quick Start's llvmpipe description predates
the passthrough GPU and is not current renderer evidence.

The NSIS installer respects an existing installation directory, including old temporary lifecycle
test directories. Verify the shortcut target, installed engine, and package hashes. For a durable
operator installation, the tested explicit destination is `%LOCALAPPDATA%\Programs\Preflight`;
use the installer's `/S` and final `/D=...` arguments, wait for its exit, and verify the resulting
files before launching. Keep failed attempts rather than assigning them successful evidence.

## Shared GPU handover

The VM and Linux desktop share PCI `0000:00:02.0`. Inspect the live domain XML, device driver,
GDM state, and `/etc/libvirt/hooks/qemu` before acting. The inspected hook manages CPU allocation
only; it does not stop GDM. Persistent VFIO boot configuration is separate from a runtime handover.
Do not reboot while depending on the Linux desktop, and do not change boot configuration for an audit.

Before handing Linux's GPU to Windows, close test GUI/game processes and verify no game remains.
Keep SSH available. The runtime sequence exercised from Linux mode is:

```sh
sudo -n systemctl stop gdm
sudo -n virsh -c qemu:///system nodedev-detach pci_0000_00_02_0 --driver vfio-pci
virsh -c qemu:///system start win11-starsector
```

Verify `domstate` is running and `/sys/bus/pci/devices/0000:00:02.0/driver` resolves to `vfio-pci`
before using guest access. Linux RDP is unavailable while Windows owns the GPU.

For return, request guest shutdown and verify the VM is **shut off** before rebinding anything.
Inspect the resulting driver rather than assuming libvirt restored Linux ownership. The prior
Linux handback used `i915`; this kernel declined `xe` without force-probe. Do not force another
driver. Start GDM only after `i915` owns the device and verify desktop access again. Preserve the
exact handback receipt with the dated audit.

The verified handback on 2026-09-06 used `virsh -c qemu:///system shutdown win11-starsector
--mode agent`. After a separate `domstate` check returned `shut off`, the device still had a
`vfio-pci` driver and override. As root, write `i915` to the device's `driver_override`, write
`0000:00:02.0` to its current `driver/unbind`, load `i915` with `modprobe`, then write
`0000:00:02.0` to `/sys/bus/pci/drivers_probe`. Verify the device's `driver` symlink resolves to
`i915` before `systemctl start gdm`. These are runtime sysfs writes; repeat the state checks before
using this sequence, and never unbind a device still owned by a running VM. SSH and the saved RDP
route were verified again after this handback.

## Evidence contracts

Record source SHA, package and installed-engine identities, installation, changed values, Apply
result, reopen readback, and launch/cleanup result. Restore prior settings after temporary checks.
Remote display modes can differ from ordinary desktop modes; a previously saved custom resolution
may disappear from the choices after selecting another value. Retain the original value first.

GUI launches are correctness observations unless a measurement protocol was explicitly run. Keep
`processStartedAt → mainMenuInteractiveAt` for startup timing; screenshots, graphics-preload, and
overlay removal do not replace it. Preserve all current mods for acceleration comparisons. No
new benchmark campaign is implied by a package or GUI check.
