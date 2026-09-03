# Big Red Arc 140T VFIO passthrough and first native-Windows baseline

Date: 2026-09-03  
Status: physical GPU assigned; Windows driver and LWJGL renderer verified; first native-GPU
startup observation retained

## Outcome

Big Red now runs headless and assigns its complete Intel Arc 140T integrated GPU to the existing
`win11-starsector` VM. The host binds PCI device `0000:00:02.0` to `vfio-pci` from initramfs;
neither `i915` nor `xe` owns it. The Windows guest reports the device cleanly and Starsector's
bundled LWJGL 2 stack reports Intel's OpenGL implementation rather than Mesa llvmpipe:

```text
Intel(R) Arc(TM) 140T GPU (6GB)
Device Manager status: OK
ConfigManagerErrorCode: 0
Driver: 32.0.101.8991

GL_VENDOR=Intel
GL_RENDERER=Intel(R) Arc(TM) 140T GPU (6GB)
GL_VERSION=4.6.0 - Build 32.0.101.8991
```

The existing VM disk, Windows installation, Scheduled Task runner, VirtioFS diagnostics share,
12-GiB memory allocation, 14-vCPU benchmark allocation, CPU pinning, and host/guest power
configuration were preserved.

This is whole-iGPU assignment. Linux no longer has a graphical desktop on this host; SSH and the
Codex remote-control daemon remain available.

## Host inventory and isolation

| Item | Verified value |
| --- | --- |
| Host | XIAOMI REDMI Book Pro 16 2025; Intel Core Ultra 7 255H |
| Distribution | Ubuntu 26.04.1 LTS |
| Kernel | `7.0.0-30-generic #30-Ubuntu SMP PREEMPT_DYNAMIC` |
| QEMU | `10.2.1 (Debian 1:10.2.1+ds-1ubuntu3.2)` |
| libvirt | compiled/library/API `12.0.0`; QEMU hypervisor `10.2.1` |
| Firmware security | UEFI Secure Boot enabled |
| IOMMU | Intel VT-d active; interrupt remapping active; two DMAR units, both 42-bit |
| GPU | `0000:00:02.0`, `[8086:7d51]` rev 03, Xiaomi subsystem `[1d72:2409]` |
| IOMMU group | group 0, containing only `0000:00:02.0` |
| Reset | PCI reset supported |
| Final host driver | `vfio-pci` |
| Host target | `multi-user.target`; display manager inactive |

The chipset audio controller at `00:1f.3` is in IOMMU group 13 and remains with the host. It is not
a companion PCI function of the GPU and was deliberately not assigned.

The isolation follows the kernel's group ownership model: an IOMMU group is the minimum safe unit
of assignment, and every device in that group must be detached from host drivers before VFIO can
own it. See the [Linux VFIO documentation](https://cdn.kernel.org/doc/html/latest/driver-api/vfio.html).

## Persistent host configuration

`/etc/default/grub.d/90-preflight-arc-vfio.cfg`:

```sh
GRUB_CMDLINE_LINUX_DEFAULT="quiet splash intel_iommu=on iommu=pt vfio-pci.ids=8086:7d51 modprobe.blacklist=i915,xe initcall_blacklist=sysfb_init"
```

`/etc/modprobe.d/90-preflight-arc-vfio.conf`:

```text
options vfio-pci ids=8086:7d51
softdep i915 pre: vfio-pci
softdep xe pre: vfio-pci
blacklist i915
blacklist xe
```

Ubuntu 26.04 uses dracut for this installation. `/etc/dracut.conf.d/90-preflight-arc-vfio.conf`:

```text
add_drivers+=" vfio vfio_pci vfio_iommu_type1 "
force_drivers+=" vfio_pci "
```

The configuration was applied with:

```sh
sudo update-initramfs -u -k all
sudo update-grub
sudo systemctl set-default multi-user.target
sudo reboot
```

After reboot, `/proc/cmdline` contains the requested IOMMU/VFIO arguments, `lspci -nnk -s
00:02.0` names `vfio-pci`, the group still contains only the GPU, and the display manager is
inactive.

This libvirt release predates native domain XML for the IOMMUFD device backend. The final VM uses
QEMU's native IOMMUFD arguments, so the libvirt QEMU process is permitted to open the cdev and its
sysfs endpoint:

`/etc/udev/rules.d/90-preflight-vfio-iommufd.rules`:

```text
SUBSYSTEM=="vfio-dev", GROUP="kvm", MODE="0660"
KERNEL=="iommu", SUBSYSTEM=="misc", GROUP="kvm", MODE="0660"
```

`/etc/apparmor.d/local/abstractions/libvirt-qemu`:

```text
/dev/iommu rw,
/dev/vfio/devices/{,**} rw,
/sys/bus/pci/devices/*/vfio-dev/{,**} r,
/sys/devices/pci*/**/vfio-dev/{,**} r,
```

`/etc/libvirt/qemu.conf` sets `namespaces = []` and retains libvirt's default device ACL entries
plus `/dev/vfio/vfio`, `/dev/vfio/0`, `/dev/vfio/devices/vfio0`, and `/dev/iommu`. This was needed
because the older libvirt mount namespace did not expose the IOMMUFD sysfs/cdev endpoint used by
the explicit QEMU backend.

The VM has been restarted repeatedly with this configuration. QEMU has open descriptors for
`/dev/iommu` and `/dev/vfio/devices/vfio0`. A QEMU warning about peer-to-peer BAR mapping remains;
it also occurred with legacy VFIO, and later causal controls showed it was not the source of the
Preflight texture stall described below.

## VM configuration

The preserved libvirt domain is:

```text
name: win11-starsector
UUID: d42eda36-dfaa-4743-8a35-917dae21eb9e
disk: /var/lib/libvirt/images/win11-starsector.qcow2
memory: 12 GiB
vCPU: 14 current / 16 maximum
CPU: host-passthrough; guest vCPUs 0-15 pinned to host CPUs 0-15
```

The relevant final XML is conceptually:

```xml
<cpu mode='host-passthrough' check='none' migratable='on'>
  <maxphysaddr mode='passthrough' limit='42'/>
</cpu>

<video>
  <model type='none'/>
</video>

<hostdev mode='subsystem' type='pci' managed='yes'>
  <source>
    <address domain='0x0000' bus='0x00' slot='0x02' function='0x0'/>
  </source>
  <rom bar='on' file='/usr/share/kvm/ARL_MTL_GOPv22_igd.rom'/>
  <address type='pci' domain='0x0000' bus='0x00' slot='0x02' function='0x0'
           multifunction='on'/>
</hostdev>

<qemu:commandline>
  <qemu:arg value='-global'/>
  <qemu:arg value='vfio-pci.x-igd-opregion=on'/>
  <qemu:arg value='-object'/>
  <qemu:arg value='iommufd,id=iommufd0'/>
  <qemu:arg value='-global'/>
  <qemu:arg value='vfio-pci.iommufd=iommufd0'/>
</qemu:commandline>
```

The eight Q35 root ports formerly occupying slot `00:02.x` were moved as a unit to `00:04.x`;
their downstream device addresses did not change. The guest GPU must be `00:02.0`, has a valid
OpRegion/VBT, and uses a UEFI option ROM. These match QEMU's documented IGD requirements. The
42-bit `maxphysaddr` limit also follows QEMU's documented OVMF workaround and exactly matches both
host IOMMU address widths. See [QEMU's IGD assignment documentation](https://gitlab.com/qemu-project/qemu/-/raw/master/docs/igd-assign.txt).

The GOP file is:

```text
/usr/share/kvm/ARL_MTL_GOPv22_igd.rom
size: 162816 bytes
SHA-256: 96b35a3cc2be0e6ad2fd4e0837303a5491ee7751a8e4f091eef67f79687bc6dc
```

This is a community-built Arrow Lake/Meteor Lake GOP, not an Intel-distributed binary. Removing
the ROM produced Windows Code 43 on this fixture. Conversely, `x-vga=on` also produced an unhealthy
or nonaccelerated combination here. The retained configuration therefore keeps the ROM and
OpRegion but leaves `x-vga` off. QEMU's `x-igd-gms` property is not supported by this Arrow Lake
device; this is consistent with QEMU's note that Meteor Lake and newer devices no longer use the
older guest BDSM setup.

## Windows driver and graphics verification

Intel's generic Windows graphics package was installed with overwrite enabled, then the stale base
OEM package that Windows ranked ahead of it was removed and the staged current driver was applied.
The VM was rebooted after each driver transition.

```text
installer: C:\Temp\gfx_win_101.8991.exe
size: 914563376 bytes
SHA-256: 949CDC134BE730389364B30632D1CCDB17B87C85A139F5A7230B0DE7C7ACB742
install: gfx_win_101.8991.exe --overwrite -s
```

The file size, hash, supported Core Ultra/Arc family, and WHQL version match [Intel's driver
download](https://www.intel.com/content/www/us/en/download/785597/intel-arc-graphics-windows.html).

Final Windows evidence:

- active PnP instance:
  `PCI\VEN_8086&DEV_7D51&SUBSYS_24091D72&REV_03\3&11583659&0&10`;
- Device Manager/WMI status `OK`, `ConfigManagerErrorCode = 0`;
- driver `32.0.101.8991`;
- current reported output `3072x1920`;
- DXGI/DXDiag exposes feature levels through 12_2 and WDDM 3.2;
- Desktop Window Manager loads Intel's 8991 D3D libraries and Windows GPU-engine counters record
  nonzero 3D work;
- a Java 17/LWJGL 2 context created from the installed Starsector libraries reports the Intel
  renderer quoted in the outcome section.

The old QXL and superseded Arc PnP instances remain disconnected with Problem 45. Neither is the
active display device.

The Mesa shim that forced llvmpipe was moved, not deleted:

```text
C:\Games\Starsector\jre\bin\opengl32.dll
  -> opengl32.mesa26.2.0.llvmpipe-disabled.dll
```

There is no longer a local `opengl32.dll` interception. Windows resolves the system OpenGL
dispatcher and loads Intel's `igxelpgicd64.dll`. The startup cohort also leaves `GALLIUM_DRIVER`
unset.

## Native-GPU Preflight startup observation

The first completed native-GPU Recommended launch used the existing exact full profile, current
Preflight JAR, stock one-worker prepared path, explicit 1024x720 game resolution, and the existing
`preflight.padding.maxUnpaddedDimension=1024` compatibility gate. Startup phase timing was enabled
only because this task required total TEXTURE wall; texture-thread CPU, texture-upload, and every
other named intrusive/candidate probe were off.

| Metric | Result |
| --- | ---: |
| Process start -> `mainMenuReadyAt` | 48.707 s |
| Process start -> v2 usable `mainMenuInteractiveAt` | 51.437 s |
| Total TEXTURE resource wall | 26.364 s |
| TEXTURE calls | 15,003 |
| Graphics-preload clock | 48.912 s |
| Adapter healthy | yes |
| Exact transformations / matches / declines | 28 / 29 / 1 |

`mainMenuOverlayRemovedAt` was not observed before the runner began shutdown; it is a diagnostic
timestamp only and is not the v2 usable-menu boundary.

### Exact run identity

```text
launch ID: c2087cc6-4687-4ff0-8fdd-8b7bbf4259c5
source main: cd6730dd4b39eeeef9a7c7f12292a12e0b9683fc
guest run directory:
  C:\Users\Leo\Documents\Starsector Preflight Cohorts\
  20260903-132423-windows-startup-2x2\01-preflight-r1
host archive:
  /home/leo/Windows-Share/Diagnostics/
  20260903-132423-windows-arc140t-native-preflight.zip
archive size: 2204476 bytes
archive SHA-256:
  1a4841c9c7c7993183ad93dfbe748a8f42bfdba3586b6a5c549e5062b5d9a470
Preflight JAR SHA-256:
  c6e3b88a8823799f17b46538bee9145e5beff689b2d71302c2fb598244ad19af
enabled_mods.json SHA-256:
  76227ce91333c202271e541774f3e86fd8711c2542d63a81cfd18a4dc0a6997f
resolved profile fingerprint:
  402a6167f341cdaef42e039d23fc3924550b8c75c4a41c23383217dd857f6dad
texture profile fingerprint:
  cfe95f25f14ce426766539225fd1fdab520d728b117a317413f47d3c40fbae3a
OS: Microsoft Windows NT 10.0.26200.0
guest processors / RAM: 14 / 12814041088 bytes
host profile / guest power: performance / High Performance
Java: Eclipse Adoptium 21.0.12.101 used by the Preflight CLI
```

The benchmark identities record `galliumDriver: null`, `windowsPreparedPrefetchWorkers: 1`, the
1024 ceiling, game/launcher/JAR/Java hashes, display bounds, Defender state and exclusions, and all
probe switches.

The cohort's `accepted` field is false solely because `gracefulShutdown` is false. The run reached
the v2 usable menu, wrote healthy adapter and phase reports, and completed the full 15,003-call
resource loop. The headless Scheduled Task runner could not obtain a nonzero `MainWindowHandle`
through its own session-bound process view and therefore force-terminated the already-measured
game. This is a lifecycle exclusion, not a failed startup or graphics result. It should not be
silently promoted into a controlled performance cohort claim.

### Native-driver compatibility finding

Three initial Recommended attempts stopped reproducibly while entering the GL upload for the first
large texture beyond the ordinary sequence. Intel's OpenGL ICD was loaded, but the resource loop
did not advance. A direct vanilla control using Starsector's original padded texture path passed
that exact image and continued loading for more than 453 seconds. This isolates the stall to the
existing unpadded prepared-texture compatibility path rather than to VFIO, the Windows driver, or
the source asset.

The already-reviewed maximum-unpadded-dimension safety switch was then set to 1024. The completed
run retained:

```text
textures served unpadded: 11448
padding bytes avoided: 842957275
dimension-ceiling declines: 24
prepared-pixel hits: 15445
prepared-pixel fallbacks: 24
prepared-pixel internal errors: 0
active/pending buffers at report: 0 / 0
```

Those 24 cases used the original game path. This is fail-closed driver compatibility evidence, not
a new texture optimization. No texture-path source was changed in this task.

## Preserved workflow

The existing guest Scheduled Task remains configured for ordinary future native launches:

```text
-NoProfile -ExecutionPolicy Bypass
-File C:\Projects\starsector-preflight\scripts\run-windows-startup-cohort.ps1
-PreflightJar C:\Projects\starsector-preflight\preflight-cli\target\preflight.jar
-Iterations 1 -CooldownSeconds 0 -Conditions preflight
-OptimizationPreset recommended -GalliumDriver native -Resolution 1024x720
-WindowsUnpaddedMaxDimension 1024
```

The runner's native mode removes `GALLIUM_DRIVER`; it does not set a Mesa backend. The retained
VirtioFS/shared diagnostics path and the Windows game/save/profile installation are unchanged.

## Recovery and rollback

Pre-change and intermediate host state is retained at:

```text
/var/backups/preflight-vfio/20260903-arc140t-before/
```

It includes the original domain XML, the working GOP/legacy-VFIO XML, GRUB, initramfs module list,
modprobe directory, and pre-IOMMUFD `qemu.conf` copies. The Mesa shim is recoverable under its
renamed filename in the Starsector JRE directory.

To return the GPU to Linux, first shut down the VM. Restore the saved domain XML and host config
files, remove the four `90-preflight-*` VFIO files and local AppArmor additions, restore the saved
`qemu.conf`, regenerate initramfs and GRUB, set the desired graphical target, and reboot. Do not
attempt to rebind this IGD live unless that exact kernel/device combination has been validated;
QEMU recommends preventing host-driver rebinding for IGD when its safety is unknown.

## Codex host control

The obsolete user unit that attempted to launch the graphical ChatGPT host without an X server was
disabled. Big Red now uses the persistent headless app-server daemon:

```sh
codex app-server daemon bootstrap --remote-control
codex remote-control start --json
codex doctor --json
```

Final health: Codex `0.153.0`, background app server running in persistent mode, authentication
configured, and `codex doctor` overall status `ok`. The connected host identity remains `big-red`
(`env_e_6a91904bea2083269cb418de778aaa42`).
