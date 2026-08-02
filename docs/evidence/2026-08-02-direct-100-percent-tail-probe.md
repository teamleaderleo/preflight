# The exact 100% tail is 27 seconds of mod callbacks, not audio drain

**Date:** 2026-08-02

**Install:** Starsector 0.98a-RC8, current 77-mod profile

**Run:** `~/.starsector-preflight/runs/20260802-011928-415-1a67b3d0`

**Recording:** off; direct monotonic phase markers only

## Result

The operator's estimate was correct: the game remained in `ResourceLoaderState.init` for 26.97
seconds after its final `renderProgress(1.0f)` call. Nearly all of that time was spent synchronously
calling enabled mods' `onApplicationLoad()` methods.

| exact boundary | elapsed since prior boundary |
| --- | ---: |
| final progress update (100%) | — |
| two-worker sound executor terminated | 17 ms |
| graphics finalizer returned | 13 ms |
| `ScriptStore` finalizer returned | 91 ms |
| mod callback loop began | 136 ms |
| mod callback loop ended | **26,662 ms** |
| `ResourceLoaderState.init` returned | 50 ms |

The two dominant callbacks were:

| plugin | `onApplicationLoad()` |
| --- | ---: |
| `ashlib.data.plugins.AshLibPlugin` | **10,995 ms** |
| `org.dark.shaders.ShaderModPlugin` | **12,064 ms** |
| all other callbacks and gaps | about 3,603 ms |

This directly rejects the initial audio-tail hypothesis for this launch. The loader does create a
fixed pool of two sound workers and waits for it after drawing 100%, but those workers had already
finished by the time the resource loop ended. Audio remains the largest category in the earlier,
partially covered JFR sample; it is just not the explanation for the visible 100% tail.

## What those two callbacks do

The exact installed AshLib callback has only two operations. Its 11-second operation is
`ShipRenderInfoRepo.populateRenderInfoRepo()`, which walks every ship hull and fighter wing and
constructs `ShipRenderInfo` objects. For modular ships it resolves variants and reads variant data
to discover module slots. This is deterministic for an exact enabled profile and is a strong
profile-bound preparation/cache candidate.

The installed GraphicsLib source ends its callback with
`TextureData.autoGenMissingNormalMaps()`. Even when its generated-normal cache is current, the method
deliberately traverses hull styles, every hull, fighter wing, weapon, and related sprites once per
JVM to rebuild the links between sprites and material/normal/surface maps. The current log shows the
callback's traversal reaching its estimated-VRAM message near the end of the measured interval.
Persisting that resolved link index, keyed by stronger content identity than the current concatenated
mod-version `String.hashCode()`, is the most plausible route to removing this repeated work.

Neither optimization should be implemented as a blind skip. AshLib's populated map and GraphicsLib's
linked texture entries are runtime outputs consumed later. A cache needs an exact profile/content
key, fail-open rebuild, behavioral comparison, and campaign/combat visual acceptance.

The matching game-log windows show the scale of the repeated work. During AshLib's measured callback
the game emitted 25,443 JSON-load lines, including 1,974 variant paths, plus 164 CSV loads. During
GraphicsLib's callback it emitted 7,759 JSON-load lines and 6,191 texture-buffer cleanup lines. Log
line counts are work evidence, not time attribution independent of the direct callback markers.

## Follow-up upload-ready prepared-pixel diagnostic

A consecutive no-record run selected the opt-in prepared-pixel coherent-direct path with the same
profile and direct phase probe:

`~/.starsector-preflight/runs/20260802-013011-604-5ffee33b`

| phase | compatibility diagnostic | prepared-pixel diagnostic | difference |
| --- | ---: | ---: | ---: |
| resource loop | 49.03 s | 35.17 s | -13.86 s |
| audio join after exact 100% | 0.02 s | 1.72 s | +1.70 s |
| all mod callbacks | 26.66 s | 22.34 s | -4.33 s |
| AshLib callback | 11.00 s | 9.78 s | -1.22 s |
| GraphicsLib callback | 12.06 s | 9.83 s | -2.24 s |
| `ResourceLoaderState.init` | 76.00 s | 59.38 s | **-16.63 s** |

This is a strong diagnostic signal, not an accepted benchmark pair: the order was not randomized,
the second launch may benefit from warmer filesystem/JIT/mod caches, and the phase probe adds small
diagnostic I/O to both runs. It does show that the lower prepared-pixel path reaches the audio join
early enough to expose 1.7 seconds of remaining decode work, while the two deterministic mod
traversals still dominate the exact post-100 tail at a combined 19.6 seconds.

The prepared-pixel runtime remained healthy through clean exit: 21,679 hits and 2.53 GB of source
pixel work bypassed, including 17,536 coherent-direct NPOT hits; zero prepared-pixel fallbacks,
dimension fallbacks, internal errors, corruptions, or quarantines; and zero active/pending/direct
buffers at shutdown. The compatibility manifest had three ordinary missing-entry fallbacks.

## Probe implementation

The agent now has an exact-gated `startup-phase-probe-v1` rewrite for the reviewed
`ResourceLoaderState` class and source archive. It marks entry, exact 100%, audio termination,
graphics and script finalization, the overall mod callback loop, every individual plugin callback,
and method return. Every boundary is atomically persisted to `adapter-startup-phases.json`, so a
hang or force-quit still leaves the last reached callback's class name.

The probe is opt-in through `--startup-phase-probe`. Ordinary accelerated launches do not register
the target, weave callbacks, or perform diagnostic writes.

## Consequence for the startup roadmap

The major remaining paths are now separate rather than one ambiguous “tail”:

1. Early/resource phase: audio decode, texture work, Janino, and JSON/spec loading.
2. Exact post-100 phase: AshLib render-info construction and GraphicsLib texture-map traversal.
3. Measurement infrastructure: the JFR execution-sampling coverage hole remains real, but it no
   longer blocks attribution of the post-100 phase because direct markers cover it.
