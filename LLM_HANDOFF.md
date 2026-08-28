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
   #1155 now has three shuffled real 260/520/780/1,040-DP ladders. Cost increased with battle size,
   but the best nonlinear model improved run-blocked RMSE by less than one percent over a simple
   linear model; the retained result is diffuse AI/entity/ordnance tax, not a stable cliff. #1153's
   exact vanilla particle wrapper consumed 0.496 ms/frame and 1.95% of the exact stress window, which
   justifies a downstream submission census but not a batching claim. Its separate GraphicsLib
   tessellation-array candidate installed cleanly yet executed zero batches in ordinary and exact
   stress routes, so it is rejected for those workloads without manufacturing a no-op A/B cohort.
   Read the current
   [physical-host reconciliation](docs/evidence/2026-08-28-fps-physical-host-reconciliation.md)
   and the completed
   [installed owner/hitch-tax pass](docs/evidence/2026-08-28-installed-owner-hitch-tax.md) before
   continuing #1153–#1158. #1158's first current-profile run resolved 53 mods, found that only one
   of 46 retained hitches overlapped GC, and selected core `ModularFleetAI` as the next exact
   decomposition target (113.618 ms maximum callback; five >100 ms hitch associations). Do not jump
   to a collector or FULL-JFR experiment from this result. #1154's classifier can consume retained
   reports after integration; #1156's async GPU path is already in the carrier. Use the physical
   machine for real game/LWJGL/driver/mod/runtime measurements and move large concurrency policy
   sweeps to synthetic harnesses.
   The exact successor is complete too: read
   [installed fleet-AI module tax](docs/evidence/2026-08-28-installed-fleet-ai-module-tax.md).
   Tactical AI, not assignment/strategic/navigation, produced the material exact measurement-window
   spans. Its next decomposition is also complete: read
   [installed tactical fleet-AI tax](docs/evidence/2026-08-28-installed-tactical-fleet-ai-tax.md).
   Fleet-list acquisition is immaterial; the interval-gated other-fleet scan produced 36.219 and
   32.413 ms spans occupying 62.0% and 64.4% of retained frames. Its decision census is now complete:
   read [installed lazy fleet-inflation hitches](docs/evidence/2026-08-28-installed-lazy-fleet-inflation-hitches.md).
   Only 27 real-strength-mode decisions ran, but their exact `inflateIfNeeded()` calls consumed
   232.934 ms and explained 107.371, 65.226, and 45.798 ms inside 200.130, 89.725, and 65.971 ms
   frames. Location-list access and strength math were cheap. Do not cache or skip the outer nearby
   decision: the selected work materially autofits and mutates a lazy fleet. The exact
   phase/autofit successor is now settled; read the
   [fleet-inflation/autofit rejection](docs/evidence/2026-08-28-installed-fleet-inflation-autofit-rejected.md)
   after the earlier mutable
   [fleet-inflation phase checkpoint](docs/evidence/2026-08-28-installed-fleet-inflation-phase-checkpoint.md).
   A foreground run joined three real inflations to 65.780, 31.762, and 32.233 ms frames. The
   largest occupied 39.5% of its frame, but reusable setup/pools were only 5.006 ms total, Core
   Autofit's worst complete call was 8.574 ms, and the stateful family explained none of five
   >100 ms frames or the repeated clusters. Do not build a broad inflater/autofit cache. The next
   exact owner is Nexerelin `EconomyInfoHelper$1`: its two slow calls took 42.370 and 13.759 ms and
   both overlapped >100 ms frames. The scheduled route passed, but shutdown emitted a native
   SIGSEGV; preserve that lifecycle failure and require clean exit in any later thin cohort.
   The exact Nexerelin successor is now measured; read
   [the recurring economy-info hitch attribution](docs/evidence/2026-08-28-nexerelin-economy-info-hitches.md).
   Four exact rebuilds consumed 150.262 ms, of which the commodity scan owned 137.730 ms. The three
   unpaused refreshes took 33.021, 29.287, and 28.860 ms; the first two together explained 65.4% of
   one 95.248 ms frame, and the third explained 48.9% of a 58.965 ms frame. Installed bytecode shows
   that `getMarketSharePercentPerFaction()` repeatedly allocates and scans the complete economy
   market group once per distinct faction, followed by three more Nexerelin `getMarkets()` passes.
   The next narrow candidate is a current-thread, identity-keyed market-list snapshot scoped only to
   this exact Nexerelin rebuild, with shadow order/identity validation, two exact source gates,
   original fallback, an independent kill switch, and direct hit/miss/mismatch counters. Do not
   broaden it into a general economy cache, and do not claim FPS from the intrusive discovery run.
   Its scenario passed but the game emitted a post-capture SIGSEGV during controller shutdown, so a
   later thin cohort must also require clean exit.

Private signing rehearsals prove that the release machinery works. They are not final release
evidence. Final operator evidence must use the same selected tag, source, Distribution, and package
generation throughout. A source change creates new bytes and invalidates affected package evidence.

Use [the engineering chronology](docs/next-llm-handoff.md) and dated
[evidence](docs/evidence/) when implementation history is relevant. Never take a SHA, owner, or
priority from historical notes without checking the live repository first.
