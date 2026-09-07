# Linux sound-enabled recovery investigation

Sound-enabled launches worked again during the investigation of
[issue #1284](https://github.com/teamleaderleo/preflight/issues/1284). The original OpenAL context
stall could not be reproduced, including after audio-service restarts and one complete Windows
GPU handover. Its cause remains unproven, so the issue stays open. No product code, native library,
audio package, or persistent audio configuration was changed.

## Identity and control

The game remains `/home/leo/Games/starsector-0.98a-RC8`, with all 83 mods enabled and enabled-mods
SHA-256 `76227ce91333c202271e541774f3e86fd8711c2542d63a81cfd18a4dc0a6997f`. The engine is the
same normally built source `b4536217fbd5ec3592d1d62eff44512c607df7db` used in the failed attempts,
JAR SHA-256 `9193f8aca5cb44a26a7296bfd96337ca170938d1f2be5ff84b2c1594934ab450`.
The harness checkout was documentation-only successor `9ea33be1`.

The first control used preset `off`, with adapter mode `OFF`, the original launcher collector,
and sound enabled. It passed audio initialization, decoded sound files, and logged playback of
`miscallenous_main_menu.ogg`. At 70 seconds the main thread was in `BaseGameState.traverse`,
rather than the previously observed native `ALC10.nalcCreateContext` call.

The generic observer recorded an absent-interactive-marker error for that control. Preset Off
disables the instrumentation that emits that marker; the raw error is preserved and its meaning
is corrected in `control-interpretation.json`. It does not establish a failed game launch, and
no interactive startup duration is assigned to this control.

Inspection of the installed `sound.Object$1` bytecode confirms that its initializer calls
LWJGL `AL.create()` directly. The installed native library identifies itself as OpenAL Soft 1.15.1.
A separate ctypes child process using that exact library opened the default device, created a
44.1-kHz context, destroyed it, and closed the device successfully. Context creation completed
in approximately 0.217 seconds initially and 0.572 seconds after restarting the existing
PipeWire, pipewire-pulse and WirePlumber user services. OpenAL logging selected the Arrow Lake
speaker through PulseAudio. The diagnostic logging option follows the
[upstream environment-variable documentation](https://github.com/kcat/openal-soft/blob/master/docs/env-vars.txt).
These probes establish API operation; no listening test or audible-output claim is made.

## Current-engine sound-enabled launches

All durations below use runtime-state v2 `processStartedAt → mainMenuInteractiveAt`.
Settings were the saved 2048×1280 fullscreen request with sound temporarily enabled. Actual
fullscreen composition remains a separate GUI issue. No mods were disabled or caches rebuilt.

| Condition | Interactive menu (s) |
| --- | ---: |
| Recommended with OpenAL diagnostic logging | 18.862 |
| Ordinary Recommended, repetition 1 | 19.118 |
| Ordinary Recommended, repetition 2 | 18.792 |
| Ordinary Recommended, repetition 3 | 18.847 |
| Immediately after audio-service restart, without a preceding audio probe | 18.898 |
| After a complete Windows-to-Linux GPU handover, without a preceding audio probe | 18.616 |

The three ordinary repetitions had a median of 18.847 seconds. This is a bounded recovery check,
not a randomized comparison or a new speedup campaign. Recommended's reviewed Linux G1 policy
remained active. All six adapter reports contained zero contained failures and zero declined
transformations. Their process groups were deliberately stopped after observing the menu;
wrapper exit 143 is preserved and does not serve as ordinary GUI-exit acceptance.

The handover used the documented runtime procedure: stop GDM, give the existing VM its shared
GPU, verify guest-agent readiness, shut the VM down through the agent, verify it is off, rebind
i915, and start GDM. No Windows game was launched. The subsequent Linux game was the first audio
test after the handback. No reboot or persistent VFIO edit was involved.

## Interpretation and cleanup

The successful Off control and unchanged Recommended launches narrow the problem, but cannot
identify which transient condition caused the earlier failures. In particular, neither a
prepared-audio defect, a G1 defect, nor GPU handover alone has been established as the cause.
Restarting audio services had failed to resolve the earlier incident; it is not a proven repair
recipe. The original failures remain in the
[preceding evidence](2026-09-07-three-platform-startup-check.md). If the stall recurs, the retained
observer can capture Java and native thread stacks before stopping the owned game, providing
more evidence than another screenshot or an unbounded wait.

All test games are stopped, original settings are restored, the VM is shut off, i915 owns the
GPU, and GDM is active. Python bytecode caches were removed. No engine rebuild or installation
was needed. Raw controls, native probes, observer scripts, thread dumps, per-run state and
`summary.json` remain on Big Red under `benchmark-results/linux-audio-fix-20260907/`; the Mac
retains a copy of the compact summary. This provides native startup evidence and documentation
checks, with no new browser, packaged-GUI, combat, or runtime-CI acceptance claim.
