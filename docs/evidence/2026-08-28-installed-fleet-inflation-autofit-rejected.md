# Installed fleet-inflation/autofit optimization rejection

Date: 2026-08-28

Issues: #1158, #449. Branch: `codex/1158-physical` at `a0734143` plus this report.

Status: **foreground phase/frame join complete; broad inflater/autofit cache rejected; no FPS claim**.

This pass closes the bounded successor selected by the earlier lazy fleet-inflation hitch report and
the first focus-invalid phase checkpoint. It preserves a real campaign hitch explanation without
turning a stateful fleet-generation path into an unsafe cache.

## Run and evidence health

Run `issue-1158-inflater-autofit-r2-20260828-174009` used the installed 0.98a-RC8 game, the same
83-mod profile fingerprint `2995668308ac3d31d645ccac30fb1a7e644e64fce5609050a1488df4cadc5af6`,
the Recommended plan, Java 21 running Java-17-targeted helper bytecode, a 1440x932 window, Apple M5
OpenGL `2.1 Metal - 90.5`, and final swap interval one. The semantic route held the untouched
initial state for three seconds, verified pause, retained a 45-second paused window, unpaused
through the internal mapped action, and retained a 45-second unpaused window.

All route steps passed. The exact-PID foreground step succeeded, 7,749 focus observations were
retained, and zero intervals were dropped as inactive. The adapter applied 73 exact
transformations with zero decline, contained failure, integrity failure, source-binding rejection,
or unavailable plan. Both the fleet-inflation and Core Autofit probes installed. No save command
was issued.

The process emitted a native `SIGSEGV` after the scheduled capture, while the controller was
stopping the run, and was then killed after its JFR was written. Preflight correctly classified the
run as `FATAL_LOG_EVIDENCE` instead of calling shutdown clean. No `hs_err_pid28217.log` or matching
macOS diagnostic report was produced. The pre-crash discovery telemetry is usable; this run cannot
satisfy a clean-exit optimization cohort.

## Discovery-only frame context

The display-boundary hook averaged 26.86 microseconds across 8,313 samples and reached 25.36 ms
once. The deep campaign/owner/inflater/autofit probes add additional unquantified cost, so these
numbers describe the workload and select owners; they are not candidate FPS evidence.

| semantic window | frames | active time | p50 | p95 | p99 | 1% low | max | >50 ms | >100 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| paused, settled | 3,581 | 61.197 s | 17.0 ms | 19.9 ms | 32.9 ms | 30.40 FPS | 48.929 ms | 0 | 0 |
| unpaused, settled | 1,847 | 35.435 s | 16.9 ms | 31.1 ms | 63.8 ms | 15.67 FPS | 167.956 ms | 35 | 5 |

The unpaused window retained 19 repeated slow-frame clusters containing 46 frames and a 46.95
ms/s stutter burden. That wider tail is important when interpreting the isolated inflation events.

## Fleet-inflation phase result

Three real `DefaultFleetInflater.inflate` calls visited 21 fleet members:

| phase | calls | total | maximum | share of inflater total |
| --- | ---: | ---: | ---: | ---: |
| total | 3 | 42.246 ms | 25.989 ms | 100.0% |
| initial setup | 3 | 1.963 ms | 1.780 ms | 4.6% |
| hullmod pool | 3 | 0.204 ms | 0.090 ms | 0.5% |
| weapon pool | 3 | 2.202 ms | 1.502 ms | 5.2% |
| fighter pool | 3 | 0.637 ms | 0.277 ms | 1.5% |
| member work | 3 | 33.227 ms | 20.046 ms | 78.7% |
| autofit, inclusive inside member work | 21 | 21.428 ms | 10.120 ms | 50.7% |
| D-mod work, inclusive inside member work | 21 | 7.964 ms | 2.783 ms | 18.9% |
| final sync | 3 | 4.016 ms | 2.304 ms | 9.5% |

Setup plus all three availability pools consumed 5.006 ms across the complete run. That is not a
large immutable cache seam. Member work remained dominant and stateful.

The three inflater totals joined exact unpaused frames:

| inflater total | containing frame | share of frame |
| ---: | ---: | ---: |
| 25.989 ms | 65.780 ms | 39.5% |
| 9.861 ms | 31.762 ms | 31.1% |
| 6.395 ms | 32.233 ms | 19.8% |

The enclosing tactical scan occupied 30.056 ms / 45.7% of the 65.780 ms frame, of which its exact
`inflateIfNeeded` span occupied 26.526 ms / 40.3%. The phase join therefore validates the existing
causal chain, but only the first event was itself a >50 ms-frame contributor. This family did not
explain any of the five >100 ms frames or the repeated slow-frame clusters.

## Core Autofit result

The exact `CoreAutofitPlugin.doFit` boundary observed all 21 member calls:

| phase/helper | calls | total | maximum | interpretation |
| --- | ---: | ---: | ---: | --- |
| total | 21 | 19.855 ms | 8.574 ms | complete reviewed method |
| primary fit | 21 | 16.566 ms | 7.097 ms | inclusive broad region |
| weapon-fit helpers | 21 | 9.242 ms | 2.221 ms | largest named helper family |
| fighter-fit helpers | 21 | 2.007 ms | 0.714 ms | distributed small work |
| setup/modules | 21 | 1.181 ms | 0.919 ms | no module-recursive calls observed |
| all other broad regions combined | — | about 2.006 ms | <=0.325 ms each | immaterial individually |

The worst complete autofit occupied 8.574 ms / 13.0% of the 65.780 ms frame. Its primary-fit region
occupied 7.097 ms / 10.8%; the largest exact weapon-fit helper occupied only 2.221 ms / 3.4%.
No complete autofit or helper span crossed 16 ms.

These measurements reject a broad Core Autofit cache or rewrite for the current workload. The
largest helper is too small to select a player-visible candidate, and the surrounding method
chooses weapons/fighters/hullmods using live variant, availability, ordnance, and random state.
Removing allocations or calls there without a thin frame result would repeat the rejected AI
Tweaks `WeaponHandle.getLocation` mistake.

## Decision

Retain the exact probes as opt-in discovery instrumentation, but do not promote an optimization
from this branch:

- reject availability-pool caching as immaterial;
- reject caching/skipping the outer nearby-strength decision because it owns listener and lazy
  fleet-state transitions;
- reject broad inflater, per-member, autofit, D-mod, or final-sync suppression as state-changing;
- do not move inflation earlier merely to hide its cost under save/campaign loading;
- do not run an A/B cohort for a candidate that removes no reviewed operation.

This is a useful rejection. Lazy inflation can cause an isolated campaign hitch, but the selected
implementation is irreducibly stateful at the measured scale and is not the dominant recurring
smoothness problem in this run.

## Next highest-information slice

The same retained owner/hitch tax selects a different exact callback before another broad probe:
Nexerelin 0.12.2b `exerelin.campaign.econ.EconomyInfoHelper$1` ran only ten times, but two calls took
42.370 and 13.759 ms. Both overlapped >100 ms frames and contributed 56.129 ms of exact callback
overlap. Inspect the anonymous callback's installed/source method and decompose only its expensive
operation before considering a mod-specific fix. Keep the existing `SensorBurstAbilityAI` result as
a secondary isolated lead: 28.534 ms occupied 53.5% of one 53.327 ms frame, but it did not explain
the severe recurring tail.

Compact retained data is in
[`data/2026-08-28-installed-fleet-inflation-autofit-rejected.json`](data/2026-08-28-installed-fleet-inflation-autofit-rejected.json).
Raw logs, JFR, frame packets, and full run/session directories remain disposable local evidence.
