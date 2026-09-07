# Windows current-engine startup observations

The fully prepared current-engine set reached an interactive main menu in
**20.477–26.404 seconds**, with a median of **25.188 seconds** across three runs.
These are local observations, not a randomized comparison or release-package acceptance.

Every startup number below uses `processStartedAt → mainMenuInteractiveAt`.
Preparation is outside that clock. No fresh stock baseline was collected.

## Conditions and identity

- Engine source: `b4536217fbd5ec3592d1d62eff44512c607df7db`.
- Candidate JAR SHA-256: `9193f8aca5cb44a26a7296bfd96337ca170938d1f2be5ff84b2c1594934ab450`.
- Current PowerShell runner SHA-256: `59637eb23a4250b183404d64a50d3363bc5da557a7c84dfc7e70bf56e3da53d4`.
- Big Red Windows VM, Intel Core Ultra 7 255H, 14 guest processors, 20 GiB RAM;
  host performance profile and guest high-performance power scheme.
- All 83 enabled mods retained. `enabled_mods.json` SHA-256:
  `76227ce91333c202271e541774f3e86fd8711c2542d63a81cfd18a4dc0a6997f`.
- Recommended preset, sound on, windowed 1024×720, no FastRendering, 20-second cooldown.
- The no-override condition uses the original game batch launcher and clears the
  Gallium override. The loaded OpenGL renderer was not independently captured;
  this evidence must not be described as verified hardware rendering.
- Candidate engine built normally with cached Maven dependencies. No GUI package
  was installed, and these runs do not establish native GUI visual correctness.

## All retained observations

| Cohort (2026-09-07) | Cache / renderer condition | Samples, seconds | Fastest | Slowest | Median |
| --- | --- | --- | --- | --- | --- |
| `120645-windows-startup-2x2` | Legacy audio manifest; no renderer override | 25.713 / 33.402 / 34.195 | 25.713 | 34.195 | 33.402 |
| `121403-windows-startup-2x2` | Legacy audio manifest; forced llvmpipe | 26.027 / 34.887 / 34.616 | 26.027 | 34.887 | 34.616 |
| `122627-windows-startup-2x2` | Refreshed audio manifest; no renderer override | 26.404 / 20.477 / 25.188 | 20.477 | 26.404 | 25.188 |

All nine runs passed the runner's existing health checks and shut down gracefully.
The first six remain valid observations of a partially prepared configuration,
but are excluded from the fully prepared headline. Their audio telemetry recorded
zero prepared hits and 2,050 game decodes, despite usable prepared textures.

The runner's preflight dry run accepted the texture cache while warning that audio
predated path-indexed lookup. Its preparation branch does not refresh audio.
Consequently, that readiness check did not establish the validated-audio prerequisite
for the current Windows Recommended optimizations. This is a harness readiness gap.

An explicit `audio prepare` completed in 49.395 seconds, preparing 2,049 sounds with
zero undecodable entries. Manifest SHA-256:
`e301a274501180d80a4545846dc38a23c05b77406fd3c4a30205472f1a1fe077`.
Each subsequent run served 2,049 prepared sounds, fell back once, and reported zero
audio failures. Prepared prefetch enqueues were 15,003 per run. The first run after
preparation remains included. The order and cache transition do not isolate the
causal contribution of audio or rendering.

The historical 16.424-second observation was not reproduced. It was the best of
six retained runs on an older engine with forced llvmpipe, not a universal result.
The preliminary 206.965-second value used a graphics-preload clock and cannot be
paired with these interactive-menu measurements as a speedup claim.

## Evidence and cleanup

Raw ZIP archives and host fingerprints remain under Big Red's
`/home/leo/Windows-Share/Diagnostics/`, with full cohort prefixes
`20260907-120645`, `20260907-121403`, and `20260907-122627`.
Local host-fingerprint copies and the audio preparation receipt are retained in
`benchmark-results/current-windows-20260907/`. Host logs and candidate identity
remain in the same relative directory on Big Red. No game assets are committed.

The owned test task was removed; the original scheduled task was left ready.
No Java game processes remained before shutdown. The VM was shut down through its
guest agent, the GPU returned to i915, and GDM restarted. Candidate staging binaries
and rebuildable Maven outputs were retired; useful prepared caches and raw evidence
were retained. No persistent VFIO boot configuration was changed.

SSH/network repair is separate operational evidence. The timer runs on the guest,
so transport latency is not added to it; background streaming or service work can
still affect resource contention. These were not certified quiet-machine runs.
