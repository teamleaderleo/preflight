# Native Windows four-way startup comparison

Objective: compare the installed modded game without Preflight/Fast Rendering, Fast Rendering
alone, Preflight alone, and both together. Three shuffled rounds, native GPU, 1024x720,
20-second cooldowns, 20 GiB VM / 14 vCPUs, original one-to-one host pinning. Keep the current
installed launcher/JVM configuration and enabled mod set; this is not a fresh factory installation.
Common clock: game-log origin to the VRAM after unload/preload completion marker. Preflight's
process-start-to-interactive-menu timestamps are separate; pure stock/FR have no Preflight marker.

Game-loaded artifact is unchanged: executable 1cc0c242, JAR
6abf2f7f7f1be2b5a16d1840c48296a6326e2f1b34f36fe501f467c1938960ea, main runtime at 8db6acf4.
Runner source 413affaeb223115216ac0cef0516dc03c80729a3 adds `--condition all` to the host wrapper
and selects the underlying stock launcher for native/other-driver arms. The legacy VM shortcut
sets GALLIUM_DRIVER=llvmpipe internally and is not a valid native stock comparator. The native
PowerShell parser passed; Windows CRLF and host LF runner content match after normalization
(SHA256 5d02d95368a23280169220fb82a287676487c814d8a2838a06e3850af9d3bf08).

Preceding placement trial 20260906-071730 used unchanged Preflight/cache and confined the test
runner plus inherited game processes to vCPU mask 0x3f (host P cores 0–5). Native menu samples
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

Phase: 12-launch native cohort running. Preserve raw artifacts, verify arm identities and flags,
report every sample and median on the common clock, then integrate runner/evidence and clean up.


Initial cohort 20260906-072817 was stopped before any game timing: the standalone JVM rejected
an unescaped file-only Log4j configuration URL containing spaces (`Unrecognized option: Preflight`).
Its raw archive is retained as incomplete. Runner source 563eb095 now uses System.Uri.AbsoluteUri,
and removes an inert Preflight property from non-Preflight arms. Installed Zulu 17.0.10 accepted
the resulting JVM option in a path containing spaces and #; PowerShell parsing passed. This is a
harness repair, not a baseline-game or measured engine change.

Replacement cohort 20260906-073309 uses the repaired runner. First pure stock process was observed
with affinity mask 16383 (all 14 vCPUs), no -javaagent and no Fast Rendering command-line marker.
Its log was still advancing through ordinary texture/resource work after 199 seconds; no final
time should be inferred until the completion marker is recorded.


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
