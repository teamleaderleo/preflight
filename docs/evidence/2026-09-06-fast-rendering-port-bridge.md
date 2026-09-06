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
