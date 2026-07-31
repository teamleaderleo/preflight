# Two bugs the operator was hiding

**Date:** 2026-08-01
**Install:** Starsector 0.98a-RC8, macOS 15 (Darwin 25.5.0), M5 MacBook Air, 24 GB
**Status:** both fixed and validated against real launches

The first unattended campaign hung on its first launch. So did the second, for a different
reason. Both bugs had been in the harness for as long as it has existed, and neither could show
while a human was driving it — because the thing the human did at the end of every run, quitting
the game, happened to paper over both.

## One: the load ended, the harness kept waiting

The measurement was complete. GraphicsLib's preload marker landed at 94769 ms; the load had
finished at 94.8s. The harness would not write the result, because it waited for six seconds of
log silence first.

Starsector never goes quiet. It keeps emitting `TextureLoader - Cleaned buffer for texture …`
from the main menu, in irregular bursts, indefinitely:

```
 94769  VRAM after unload/preload: 450555 bytes     <- load finished here
154684  Cleaned buffer for texture …/hellbore_cannon_turret_base
174426  Cleaned buffer for texture …/pd_laser_turret_base_material
…
231795  Cleaned buffer for texture …                <- still going, 137s later
```

Gaps between those lines run 0.0s, 0.0s, 20.8s, 0.0s — so a six-second window sometimes closes
and sometimes does not, decided by whether a burst lands first. Under the clicked protocol the
operator quit the game, the log stopped, and silence arrived by construction every time.

**This also explains `trailingLogActivityMs`.** It ranged 0.0–9.3s across otherwise identical
runs on 2026-07-31 and was recorded as unexplained noise. It was measuring when the cleanup
trickle happened to pause. Nothing about the game.

The quiet window was what proved the phase had ended, back when the measurement ran to *the last
line before it*. Once the boundary became the preload marker it proved nothing extra. Both
boundaries are now markers the game logs itself, and completion is immediate. `trailingLogActivityMs`
is null.

## Two: a launch that straddles a log rotation was two launches

The detector kept its three markers in dictionaries keyed by inode and required all of them on
one:

```python
matching = set(descriptor_lines) & set(preload_lines) & set(starts)
```

log4j rotates `starsector.log` at 51 MB. A single launch of this profile writes more than that
whenever the previous run left the file nearly full, so a launch routinely straddles the
rotation. On the campaign's first `prepared` run:

| file | inode | markers |
| --- | --- | --- |
| `starsector.log.1` (rotated 30s into the run) | 19452847 | the start marker |
| `starsector.log` | 19454300 | save descriptor, preload |

Intersection empty. The watch ran to its 600-second timeout holding a finished measurement —
descriptor at 90.7s, preload at 92.4s — in two halves. The stuck launch from bug one had left the
log thirty seconds short of rotating, which is why this surfaced immediately after that fix.

Rotation timing is luck, so this was always live under the clicked protocol too; it just needed a
long enough preceding run to line up.

The snapshot is what separates launches: everything read was written after it, and the harness
terminates the previous game before taking it. Within that window the first start marker opens
the launch, and the two end markers are only accepted at or after it, so a straggling line from an
earlier game cannot close this one. Inode is now reported and never used to decide.

## What they have in common

Both are the 2026-07-31 anchor bug wearing different clothes:

- a **proxy** standing in for a signal already held (silence for "the load ended"; the anchor's
  "first line after the snapshot" for "the game started");
- a **grouping key that is not the thing being grouped** (inode for "one launch"; flush timing
  for "the measurement boundary").

And all three were invisible for the same reason: every component agreed with every other
component. The harness, the detector, and the recorded runs were mutually consistent the entire
time. What broke each open was reading Starsector's own log independently of the tool that drove
it — which is why `scripts/starsector_log_load_times.py` exists.

## Validation

Both fixes were checked against real launches before the campaign was restarted, not against
fixtures alone:

| condition | result |
| --- | --- |
| `fast` | 94.1s, accepted, 6651 textures served, 3 fallbacks |
| `prepared` | 90.5s, accepted, 6651 conversions bypassed, **0 fallbacks** |

The `prepared` run is the first end-to-end proof of the prepared-pixel bridge on the reviewed
installation: every texture carried, none falling back to the game's own conversion.
