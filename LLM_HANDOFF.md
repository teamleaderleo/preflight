# Preflight LLM handoff

This file is a route to current state, not a copy of it.

Before changing code or collecting release evidence:

1. Read [CLAUDE.md](CLAUDE.md).
2. Fetch `main`, list the open PRs in the area, and read the live
   [release board #652](https://github.com/teamleaderleo/preflight/issues/652).
3. Read [Release readiness](docs/release-readiness.md).
4. Follow the current owner. [#965](https://github.com/teamleaderleo/preflight/issues/965)
   owns candidate exercises, [#818](https://github.com/teamleaderleo/preflight/issues/818) owns
   final package identity, [#418](https://github.com/teamleaderleo/preflight/issues/418) owns the
   packaged startup benchmark, and [#1023](https://github.com/teamleaderleo/preflight/issues/1023)
   holds product observations until one becomes a concrete defect.
5. Read [scripts/README.md](scripts/README.md) before driving the game. Use
   `scripts/benchmark-startup.sh` for one automatic startup measurement, `--details` to explain a
   changed result, and `--campaign` only for a real comparison.
6. For current campaign/combat smoothness work, read the maintained
   [frame-pacing investigation notebook](docs/frame-pacing-investigation.md). It separates durable
   observations from falsifiable hypotheses and records why a previously explored area may still
   be worth revisiting. [Gameplay FPS program #449](https://github.com/teamleaderleo/preflight/issues/449)
   is the canonical parent for that lane; use focused child issues once one experiment is concrete.
   The latest completed discovery checkpoint is the
   [1,040-DP severe-frame attribution pass](docs/evidence/2026-08-28-combat-severe-frame-attribution.md),
   and the rejected
   [60 Hz precision-limiter pass](docs/evidence/2026-08-28-campaign-precision-limiter-rejected.md),
   following the rejected
   [GL matrix identity-elision cohort](docs/evidence/2026-08-28-gl-matrix-identity-elision-rejected.md),
   following the rejected
   [GL texture-bind deduplication cohort](docs/evidence/2026-08-28-gl-texture-bind-dedup-rejected.md)
   and [settled active-campaign OpenGL state reissues](docs/evidence/2026-08-28-opengl-state-reissues.md),
   following [OpenGL command attribution](docs/evidence/2026-08-28-opengl-command-attribution.md)
   and [asynchronous whole-frame GPU timing](docs/evidence/2026-08-28-asynchronous-gpu-frame-timing.md),
   the bounded [native-swap CPU/off-CPU split](docs/evidence/2026-08-28-native-swap-cpu-offcpu-split.md),
   and [live OpenGL capability inventory](docs/evidence/2026-08-28-opengl-context-capability.md).
   Paused recurring 33 ms tails were normally low-GPU and swap-off-CPU dominated, while active
   unpaused tails were often GPU-heavy even after the five-second transition. The query probe is
   materially intrusive and makes no FPS claim. The exact active window then exposed 13,306 selected
   legacy-GL calls per retained frame; matrix plus fixed-function state represented 81.62%, and
   texture binds brought the share to 88.98%. Slow frames carried only 0.54% more calls, so this is a
   large baseline/Rosetta-amplified lead rather than the hitch explanation. The exact follow-up then
   observed 2,294 same-state reissues per active frame: 40.53% of modeled calls and 16.89% of the
   selected command stream. Texture binds repeated 53.68%. Slow-frame redundancy was effectively
   unchanged, confirming a baseline submission-tax lead rather than the hitch cause. The exact
   texture-only candidate then suppressed a median 38.36% of binds in a thin B/A/A/B 1,040-DP
   cohort, but changed p99 +1.6%, 1% low -0.9%, >50 ms/min +41.5%, stutter burden +8.4%, and average
   FPS -4.3%. The subsequent exact matrix census found 29.18 million operations/500 frames and 2.39
   million identity/no-op calls. A fail-open candidate removed a median 2.53 million exact identity
   transforms per run in a thin B/A/A/B 1,040-DP cohort, yet moved p99 -1.4%, 1% low +1.5%, >50
   ms/min +2.6%, and average FPS +0.8%. Both candidates are rejected and retired; do not revive or
   widen either merely because its causal counter is large. The deterministic route now freezes
   camera setup, pins the full viewport, records begin/end workload fingerprints, and has a compact
   cohort summarizer. The paused branch remains a separate thin presentation/VSync/compositor
   experiment. The 1,040-DP matrix cohort found every slow frame pre-swap dominated, while native
   swap averaged only about 0.31 ms. The follow-up JFR pass corrected an important selector failure:
   because the stress fixture averaged 50.9 ms/frame, fixed `>33.33 ms` clusters covered nearly the
   whole 30-second step. Use `--hitch-frame-millis 100` there. Its 18 exact severe groups did not
   reveal a broadly recurring narrow CPU or allocation family, so no candidate was promoted.

   Before adding more probes, reconcile the live coordination map in
   [#1152](https://github.com/teamleaderleo/preflight/issues/1152) and child lanes #1153–#1158. At the
   2026-08-28 installed-host checkpoint, #1157's current 60 Hz precision waiter is rejected: two thin
   VSync-off runs were healthy but changed historical-context p99 only -1.5% and 1% low +1.6%, while
   average FPS was -2.6%. Its roughly 0.63 ms deadline extension plus 0.68 ms average overshoot is a
   concrete successor-design warning. VSync-off itself remains the large accepted experimental win.
   #1154/#1158 have complementary classifier, owner-tax, and JVM-correlation work but still need
   integration cleanup. The next high-information physical-host pass is #1155's real combat-scaling
   coefficients using the 1,040-DP harness, followed by one separated #1153 render-sync/GraphicsLib
   candidate or a bounded #1156 GPU/resource diagnostic. Use the physical machine for real
   game/LWJGL/driver/mod/runtime measurements and move large concurrency policy sweeps to synthetic
   harnesses.

Private signing rehearsals prove that the release machinery works. They are not final release
evidence. Final operator evidence must use the same selected tag, source, Distribution, and package
generation throughout. A source change creates new bytes and invalidates affected package evidence.

Use [the engineering chronology](docs/next-llm-handoff.md) and dated
[evidence](docs/evidence/) when implementation history is relevant. Never take a SHA, owner, or
priority from historical notes without checking the live repository first.
