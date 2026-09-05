# Windows native upload stall investigation

Objective: identify and fix the native upload stall before selecting the packed-raster performance
lead. Owner: current Codex task. Phase: validating low-overhead pending-upload diagnostics.
Finish: test a justified fix, integrate main, leave the ordinary Windows task Ready with no actors,
and preserve exact evidence while retiring disposable builds. Do not claim reliability from one fast run.

Baseline main `23819ab16f76c10941a3d8d429226d848ed5173b`, installed JAR
`369d43b415829c5082c29d1762adb9d8fdb73f6cf73d6406087740bd38534cb9`.
The packed-raster lead from `10162d70…` produced 18.113 / 17.537 / 16.690 s menus, then stalled
on the first repeat. Restoring it here is for investigation, not acceptance of that failed trial.

The existing upload checkpoint writes a file before every native call. Replace that diagnostic
behavior with one metadata-only pending record and a daemon observer that checks every ten seconds.
An attempt pending at least ten seconds writes once; successful uploads have no checkpoint I/O.
The record retains no ByteBuffer reference, changes no buffer state, and performs no GL calls.
Session replacement retires the old observer and clears pending state. Ordinary launches have no
checkpoint observer. Tests cover delayed publication, single publication, buffer bounds and state,
completion clearing and session replacement. The stock resource worker count remains one.

Full verification passed in 47.757 s with exact installed common/core and Log4j fixtures.
Observer + packed lead source `5202e52b491d833ff30491e0018c6b301e7150a2`, JAR
`c4fadc61da1479356bc1960aab17254d82e6cd674968b784ab5cc469fd00c3ce`.
Three-platform CI `33990176958` requested. Native reproduction: three Recommended launches,
1024x720, 20-second cooldowns, pending-upload checkpoint enabled. These are diagnostic observations.

Installed TextureLoader bytecode enables GL_GENERATE_MIPMAP (33169) and trilinear minification
for source dimensions <=1024, or paths in its special set. Mipmap generation is therefore a
candidate driver interaction to test after capturing the pending upload, not an established cause.

Diagnostic `20260906-042910`: first two menus 18.695 / 17.666 s (diagnostic only); third stalled.
The observer recorded `graphics/factions/sotf_dustkeepers_burnouts.png`, 410x256, external RGB
(6407), internal RGBA (6408), unsigned byte, position 0, limit/capacity 314,880, direct buffer,
after 5,202 completed uploads. The main-thread dump confirms native `glTexImage2D` at 227.91 s.
Stopped the task and retired exact PIDs 12756/6584/12432 with creation-time checks. Private pending
record and `first-thread-dump.txt` / `first-retirement.json` are under `Diagnostics/upload-stall`.

410 RGB pixels occupy 1,230 bytes per row. With unpack alignment four, stride is 1,232 and the
last row ends at byte 315,390: 510 bytes beyond this buffer. Actual GL unpack state was not yet
captured, so this is a conditional diagnosis. The installed LWJGL GLChecks helper multiplies
width, height and components and does not account for this stride. A diagnostic-only glGetInteger
read now captures GL_UNPACK_ALIGNMENT for potentially misaligned RGB rows on main; successful
observations are counted by alignment as well. No GL state is changed. The watchdog itself still
performs no GL calls and retains no buffers.

Alignment diagnostic full verification passed in 46.855 s. Source
`bb05e5ea71292d3e12dbf0047b0255ed4c96b49c`, JAR
`e9c58e7d3c4b98dc6040324dd7a583cb536b3a9967d5482089d9a52301990dd2`.
One native checkpoint run will read actual alignment before selecting a repair.

Diagnostic `20260906-044307` completed (18.688 s menu, not an ordinary timing sample). It observed
168 potentially misaligned RGB rows, all with actual unpack alignment 4; none used 1, 2 or 8.
The [Khronos pixel-storage contract](https://wikis.khronos.org/opengl/GLAPI/glPixelStore) defines
four-byte initial row alignment and the legal values 1/2/4/8. This confirms a prepared-buffer
layout defect. Whether it explains every historical native stall still requires native testing.

Repair: at the exact Windows TextureLoader upload calls, scope alignment 1 around only a tight
RGB buffer owned by the current prepared converter. Verify buffer identity, dimensions, byte
count, position, type and thread ownership before querying/changing state. Restore the previous
alignment on both normal and exceptional returns, ahead of the original release handlers.
Unknown buffers, formats and non-Windows class identities retain their original behavior.
Sampler/mipmap policy, dimensions, original converter fallbacks, cleanup and GL ownership do not
change. `preflight.texture.scopedUnpackAlignment=false` retains the prior behavior for diagnostics.
Bounded change/restore counters expose scope lifecycle. Installed tests cover success, upload
failure, original-buffer decline, exact buffer identity, opt-out and restored state.
