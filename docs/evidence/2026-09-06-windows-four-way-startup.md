# Windows four-way startup comparison

Objective: compare the installed modded game without Preflight/Fast Rendering, Fast Rendering
alone, Preflight alone, and both together. Three shuffled rounds, no Gallium override, 1024x720,
20-second cooldowns, 20 GiB VM / 14 vCPUs, original one-to-one host pinning. Keep the current
installed launcher/JVM configuration and enabled mod set; this is not a fresh factory installation.
Common clock: game-log origin to the VRAM after unload/preload completion marker. Preflight's
process-start-to-interactive-menu timestamps are separate; pure stock/FR have no Preflight marker.

Final matrix source is ff2a151f8f1fb18854582882df56566243d76b3c, installed JAR
f311f62283cee8c650e98b7d3ef3406d504ecd6f1968c78b9d056a8662329cf2.
Game-loaded agent/core sources remain unchanged from 1cc0c242; CLI ownership detection changed.
Earlier incomplete cohorts and the placement trial used JAR
6abf2f7f7f1be2b5a16d1840c48296a6326e2f1b34f36fe501f467c1938960ea.
Runner source 413affaeb223115216ac0cef0516dc03c80729a3 adds `--condition all` to the host wrapper
and selects the underlying stock launcher for native/other-driver arms. The legacy VM shortcut
sets GALLIUM_DRIVER=llvmpipe internally and is not a valid native stock comparator. The native
PowerShell parser passed; Windows CRLF and host LF runner content match after normalization
(SHA256 5d02d95368a23280169220fb82a287676487c814d8a2838a06e3850af9d3bf08).

Preceding placement trial 20260906-071730 used unchanged Preflight/cache and confined the test
runner plus inherited game processes to vCPU mask 0x3f (host P cores 0–5). The shortcut forced llvmpipe in this trial. Menu samples
19.385 / 19.327 / 19.152 s, graphics 17.505 / 17.578 / 17.397 s. Every game mask was verified
and recorded with PID/start time in cpu-placement.json. This was consistent but slower than the
retained 14-CPU candidate; it is not retained. Normal task and full CPU availability restored.
Host P-core capacity/max frequency: 1024 / 5.1 GHz; cores 6–13: 774 / 4.4 GHz.

An untested, undeployed candidate to defer exact background version-check HTTP requests until
menu readiness (bounded by five seconds) was saved privately at
benchmark-results/windows-startup-background-work/untested-version-check-deferral.patch and
removed from the working sources before the four-way measurement. It is not accepted work or
part of any measured artifact. Existing response dedup already removed 31 duplicate requests in
a recent snapshot, but 41 distinct requests still ran. Future investigation must validate the
background caller/await contracts and actual timing before changing their schedule.

Phase: corrected 12-launch cohort complete; 11 accepted runs and one retained stock failure.


Initial cohort 20260906-072817 was stopped before any game timing: the standalone JVM rejected
an unescaped file-only Log4j configuration URL containing spaces (`Unrecognized option: Preflight`).
Its raw archive is retained as incomplete. Runner source 563eb095 now uses System.Uri.AbsoluteUri,
and removes an inert Preflight property from non-Preflight arms. Installed Zulu 17.0.10 accepted
the resulting JVM option in a path containing spaces and #; PowerShell parsing passed. This is a
harness repair, not a baseline-game or measured engine change.

Replacement cohort 20260906-073309 uses the repaired runner. First pure stock process was observed
with affinity mask 16383 (all 14 vCPUs), no -javaagent and no Fast Rendering command-line marker.
It completed at 206.965 s on the common clock. Other preliminary samples were FR 53.055 s,
combined 29.080 s (30.767 s interactive), and rejected nominal Preflight 20.495 s
(22.694 s interactive). These incomplete-cohort samples are excluded from the final matrix.


The replacement matrix also exposed a product defect: LaunchOwnership classified a stock
starsector.bat as FAST_RENDERING solely because fr.vmparams existed beside it. Its first nominal
Preflight arm reached the menu but correctly failed the owner/health gate (27 transforms,
FAST_RENDERING owner despite a stock command). Cohort 20260906-073309 was stopped and retained
as incomplete. LaunchOwnership now consults the sidecar only when the selected launcher itself
identifies Fast Rendering by name or reference. Tests cover stock/FR coexistence, renamed
explicit-FR launchers and bounded sidecar reads. Full verify passed in 47.878 s.

Historical renderer correction: the 16.424 s archive explicitly launched Play-Starsector-VM.cmd
and recorded its SHA256 b93bcff1fb4b15d22167c66e75cc5c792e800c9c43266f67cdf92cdf0ac7919e.
The current identical file explicitly sets GALLIUM_DRIVER=llvmpipe. Earlier “native GPU” claims
for those wrapper-based trials were wrong; command-line intent was not effective renderer proof.
The historical evidence documents now carry a correction. The new matrix uses the underlying
stock launcher and corrected ownership detection, with no Gallium override in any arm.


Corrected cohort 20260906-075635: stock repetition 1 stopped advancing at game-log 4.954 s.
At JVM elapsed 541.50 s, a retained Thread.print dump showed main inside
org.lwjgl.WindowsSysImplementation.nAlert, called by CombatMain. This establishes an error-alert
wait, not a scheduler diagnosis or a Java deadlock. Alert text was unavailable from the QGA
session. The exact stalled Java PID 13540 and launcher PID 4464 were retired after birth/command
validation; the cohort continued and retained this failed attempt with no timing.
Private diagnostics: four-way-stock-stall-threads.txt/.err and four-way-stock-alert.txt.

## Fast Rendering startup takeover follow-up

The installed fr.jar remains SHA256
dea3ea3d0fd7437d4a7945fee65f741d9b72d3fec565b9c4807aea479ce56144 (0.8.4).
Direct installed-bytecode review confirms TextureLoader.loadTextureAsync produces TextureData,
increments ResourceLoader.mainThreadWaitGroup and enqueues a main-thread commit. The commit
constructs a handler through TextureBuilder, sets its registration name, publishes to the global
repository, and decrements the wait group even on failure. ResourceLoader also owns exception
propagation and executor shutdown. Bypassing all of ResourceLoader would discard these contracts.

The existing opt-in prepared bridge acts after DDS lookup and before ImageIO decoding. It is off
in the ordinary combined arm. Its earlier controlled test displaced 15,524 decodes but regressed
menu readiness; see the September 1 Windows tuning evidence, “Fast Rendering” prepared bridge
section. Merely enabling that bridge again is not an established win. A successor should retain
the renderer's initialization and lifecycle, and investigate prepared-data decompression/copy and
worker-to-main handoff costs before replacing more of the loading path. This is an implementation
lead, not an accepted bypass or a measured attribution of the current gap. No Fast Rendering
loading policy changed during this comparison.

## Final results

| Condition | Graphics-preload samples (s) | Median (s) | Accepted / attempted | Interactive-menu samples (s) | Menu median (s) |
| --- | --- | ---: | --- | --- | ---: |
| Stock, neither product | failed, 180.484, 177.366 | 178.925 | 2 / 3 | unavailable | — |
| Fast Rendering only | 47.288, 31.546, 32.925 | 32.925 | 3 / 3 | unavailable | — |
| Preflight only | 17.290, 17.078, 18.048 | 17.290 | 3 / 3 | 18.258, 18.798, 19.732 | 18.798 |
| Preflight + Fast Rendering | 23.150, 24.119, 26.484 | 24.119 | 3 / 3 | 24.129, 25.832, 27.407 | 25.832 |

Preflight-only is fastest in this cohort. Combined minus Preflight-only menu medians is 7.034 s;
this is an observed configuration difference, not attribution to one subsystem. Three attempts
per arm establish neither long-run reliability nor consistent sub-17-second menu readiness.
Stock means the current modded installation and installed tuned JVM launcher without either
product, not factory defaults. The common graphics clock can precede FR worker completion and
must not be presented as time-to-play.

All six Preflight runs passed adapter health with correct ownership: STARSECTOR for Preflight
alone and FAST_RENDERING for combined. Every accepted run recorded graceful shutdown. The
manually terminated stock attempt also reports gracefulShutdown=true because cleanup found no
remaining actors; it is explicitly a forced termination and remains rejected, with no time.
The scheduled task returned to Ready, with zero game/launcher actors. The normal task defaults
were restored; the persistent llvmpipe shortcut was not changed. CPU availability stayed 14 and
VM memory stayed 20 GiB. No CPU-priority experiment was applied to this cohort.

Renderer scope: underlying launchers and absence of a Gallium override were verified. The VM
reports Intel Arc 140T, driver 32.0.101.8991, but an actual game GL_RENDERER string was not
independently captured. This evidence does not promote environment intent into renderer proof.

Full Maven verify passed; three-platform verification run 33999849454 passed. Installed stock
and FR dry runs selected the correct owners before the final matrix.

Private archive: 20260906-075635-windows-startup-2x2.zip, 19,222,875 bytes, SHA256
2c23985b3a119df005410b8917d0fe640780ff8a5a5105da12ed5353c7a722a5.
Host fingerprint: 20260906-075635-windows-startup-2x2-host.json. Earlier incomplete archives and
the first stock failure diagnostics are retained alongside it.
