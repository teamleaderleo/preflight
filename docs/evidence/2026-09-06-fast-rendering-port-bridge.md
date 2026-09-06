# Fast Rendering 0.8.7 port prepared-texture bridge

This continues the pinned release review in `2026-09-06-mac-linux-renderer-port-review.md`.
The archive identities remain in `docs/fast-rendering-port-lock.json`. No renderer dependency or
vendored renderer implementation is added. The bridge is separately opt-in and not release-ready
native compatibility evidence by itself.

The reviewed loader stores `Blacklist.doNotModify(path)` in local 3, attempts DDS first, then
constructs its buffered input stream. The new shortcut is inserted at that final seam and branches
around itself when local 3 is true. AlphaAdder and non-ResourceHandle input streams decline.
The port carrier records image dimensions separately from upload dimensions and identifies DDS
through a nullable Path instead of the old boolean. Class and platform-specific archive hashes,
application loader identity, and the independent plan kill switch remain exact.

Source review also found the port upload sets unpack alignment to 1 and snapshots the supplied
ByteBuffer before queuing GL work. The bridge retains the prepared buffer view through its carrier;
it does not take over the port's upload, DDS hooks, queue, or snapshot release.

## Automated evidence and excluded results

The actual Mac and Linux release JARs passed class/archive matching, transformed-frame analysis,
second-weave rejection, and public carrier field/pixel checks. The old 0.8.4 installed-archive test
was skipped because that unrelated archive was not supplied.

Direct pixel parity against both released TextureBuilders exposed a real difference for zero-alpha
TYPE_INT_ARGB pixels: a reused scanline clears RGB but leaves alpha from its previous row. A 1x2
fixture retains 255 in the transparent pixel where the prepared reference stores 0. Preserve this
excluded observation; it is not proof of the earlier launcher-window corruption. Because prepared
metadata does not retain the decoded image type, the new bridge conservatively declines all images
with zero alpha. RGB and nonzero-alpha ARGB/BGR/ABGR fixtures match both builders byte-for-byte,
including dimensions and all three colors. The regression suite also retains the excluded stale-
alpha behavior explicitly.

Local verification logs are under `benchmark-results/fr-port-bridge/`. The initial broad check
failed the plan inventory count (84 became 85); the catalog fixture now includes both new exact
targets. The initial parity failure, corrected inventory check, and narrowed passing parity run
are retained separately. No startup speedup is claimed from these tests.

## Native Linux follow-up

Candidate code: `a10ca735f821956c8fb30a873f8e188fe76046a7` (merged by #1268).
Candidate engine SHA-256: `6419b231798ce8785b595e018e015fc698d03d627faf133f022ae4669ff76cc6`.
Full Java verification passed on Windows, macOS and Linux in run `34058215324`; all PR package,
frontend, operator, boundary and analysis checks passed. These are distinct from native results.

Big Red retained i915 and active GDM; the Windows VM stayed off. All 83 mods remained enabled.
Native logs, staging identities, excluded results and cleanup receipt are under Big Red's
`benchmark-results/fr-port-087/`; RDP-window screenshots are in the Mac checkout's
`benchmark-results/fr-port-bridge/`. No game ran on the Mac during this slice.

The published Linux JRE archive (`fr-linux-x64-jre.zip`, SHA-256
`6d9f222c005cbddc760da1bd4541a0d837f41a10e2f06883ee8a82f2529b3b2b`) contains matching pinned
renderer JARs and Zulu 27+47. Its launcher fails on `AlwaysAtomicAccesses`; a JVM-only flag probe
also identifies `UseVectorStubs` as rejected. Subsequent native checks used a temporary staged
script with only those two flags removed, SHA-256
`16cb4a97dc7fe1c665732a3487026ff4ff56c90002b0a55b0c6dbfde1b5cb966`.
The staged JRE needed executable permission on `lib/jspawnhelper`; the first adjusted launch
failed LWJGL display enumeration without it. These setup failures are not bridge failures and
are not acceptance of the unmodified published launcher.

Recommended Preflight plus the port, with the new bridge still off, then failed verification in
`WeaponSpecLoader.Ò00000(String)`: Object was not assignable to Linux `loading/specs/N` at offset
139. Run `20260906-203829-172-39946f61` and its log retain the failure. Original bytecode joins
`specs/N` and `specs/if` at that site; the application-hierarchy fallback in the existing frame
writer is a concrete investigation lead. This blocks combined-preset acceptance; follow-up #1269
owns the repair and native Mac/full-preset gates. The new bridge remains independently opt-in.

The isolated run `20260906-204257-456-57be6c65` enabled only the new bridge and the reviewed
main-menu-interactive marker. It served 2,096 prepared textures / 626,102,245 bytes across four
threads, with one installed bridge, zero internal failures, zero circuit-breaker activation,
and zero transformation declines. Fallbacks remained active (6,635 layout declines, one type
decline, eight resource declines, 12,971 misses). The native menu rendered without obvious color
corruption in the captured view, and Escape opened its exit dialog through the saved RDP route.
This is not a full gameplay fidelity pass. The 300-second watchdog expired before exit interaction
was completed, so native exit is **not** accepted for this renderer condition. The reviewed clock
was unchanged (`processStartedAt` to `mainMenuInteractiveAt`, about 16.029 seconds), but the
isolated configuration/JRE/heap is not a comparable acceleration benchmark.

## Linux menu appearance control and cleanup

In response to the maintainer's starfield-only/static-menu observation, a stock Linux renderer run
used `starsector.sh`, its stock Java 17 and the same 83 mods, with Preflight optimizations off and
the staged Fast Rendering files already removed. It showed the same starfield menu. Two captures
changed 110,644 of 247,000 sampled background pixels (mean absolute RGB delta 3.578), so the
background is not a completely frozen frame. This does not establish the reason for a different
scene on the Mac/Windows installations or distinguish every animation from remote-display effects.
Stock Escape/Yes interaction exited normally before the watchdog (service duration 280 seconds).

All staged renderer files, the JRE symlink/runtime, and the downloaded Linux runtime ZIP were
removed. Settings readback remained 2048x1280, fullscreen true, sound off, 2 GiB heap, UI scale 1.0,
battle size 400 and AA off. No mod was disabled. Failed observations and run history were retained.
