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
