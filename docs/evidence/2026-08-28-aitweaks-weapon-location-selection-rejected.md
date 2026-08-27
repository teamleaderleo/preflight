# AI Tweaks target-selection weapon-location reuse rejected

Date: 2026-08-28

Install: Starsector 0.98a-RC8, AI Tweaks 2.2.10, current heavily modded profile,
macOS on Apple M5, Java 17 game runtime under Rosetta, Preflight Recommended preset

Status: **rejected after correctness, ordinary-combat, and 1,040-DP validation**. The candidate
removed the intended allocation family, but it did not demonstrate a useful player-visible benefit
and its global getter wrapper acquired measurable sampled CPU weight. The candidate is preserved at
`5b6035aa` and removed from the shipped tree by `574cfd3b`.

## Decision

The exact installed AI Tweaks target-selection path recalculates one weapon-slot location many times
while one `SelectTarget` object is synchronously constructed and consumed. The candidate bracketed
only that call from `AutofireAI.updateTarget`, then made
`WeaponHandle.getLocation-impl` reuse the first original getter result for the same weapon and thread.
It deliberately did **not** transform `SelectTarget`; the earlier combined field rewrite remains
retired after its heavy-combat null-receiver failures.

The structural premise was strongly confirmed. In the final 1,040-DP run, 60,980 selections retained
one original getter miss apiece and served 8,302,274 cached reads, or about 136.1 hits per selection.
Weighted `SelectTarget` allocation samples rooted at vanilla `computePosition` fell from 194.1 MiB in
the adjacent near-control to 2.0 MiB in the final candidate. JFR weights are statistical estimates,
not an allocation census.

That is not enough to ship the wrapper. The profiled stress observations produced no consistent
frame-time improvement, and the final candidate wrapper appeared in 20 of 624 combat execution
samples (3.21%) versus zero of 651 in the near-control. Because these runs used intrusive JFR
sampling, their frame distributions are diagnostic co-observations rather than a performance claim.
The clean no-JFR ordinary run is a B-only safety observation, not an uplift comparison. With no clean
player-visible win and a plausible new CPU tax, the implementation is rejected rather than retained
as an allocation-only opt-in.

## Exact identity and semantic boundary

Both transformed classes were admitted only for Java 17 classfiles, the AI Tweaks CoreLoader, archive
SHA-256 `9f6179bcd2df2e3ce8cea2da79051c9f1be3c9b71712c6c28d7568b777ecf5b2`, exact
method descriptors, and reviewed instruction shapes:

- `AutofireAI` loader bytes: `f8bf1794c6277b5d7a64f206b62e8ac78ec7c35ce90c50dfecd5ca66a5119b56`;
- `WeaponHandle` archive entry: `a5b29faf9870d98a9d3275e0b5d50025b456bdd0e3396f7e4f77d3a4bc9c8282`;
- `WeaponHandle` loader-produced bytes at the JVM transform boundary:
  `0c574bd722f62c04b543d2ee0f9e8276776ecb3f26e91ddc06dfc868ba4afb21`.

AI Tweaks' loader resolves private engine symbols before `defineClass`, so the archive entry and live
transform buffer legitimately differ. The first live attempt pinned the archive bytes, declined the
live `WeaponHandle`, and therefore exposed this boundary without changing its original getter. The
corrected target pins the loader-produced bytes while still requiring the exact archive and loader.

The runtime kept no save data and transformed no save/load or serialization class. Its context was
per-thread, identity-matched the weapon, closed on both normal and exceptional exits, and released
game object references at the end of every selection. Self-review added an atomic activation rule:
both transforms had to install before either hook did work. A partial match therefore remained fully
inert. The property `preflight.combat.aiTweaksSelectionLocation=true` was an explicit opt-in, and the
ordinary plan kill switch remained available.

## Correctness and fallback checks

Focused fixture tests covered the exact normal and exceptional bracket, same-weapon identity,
nested-context abandonment, disabled behavior, partial-install inertness, changed shape, wrong hash,
and second-rewrite decline. The installed-byte test transformed the exact local AI Tweaks archive plus
the captured loader-produced class and passed ASM analysis over every transformed method.

Verification completed before live validation:

- 10 focused plan/runtime/catalog tests passed;
- the exact installed-byte integration test passed;
- full Java 17 `mvn verify` passed on the candidate tree: 39.601 seconds, no failures;
- full Java 17 `mvn verify` passed again after the revert: 44.163 seconds, no failures or
  errors (77 tests, five environment-dependent skips across the reactor).

The first ordinary attempt never reached gameplay. Intrusive sampling hit the known Rosetta/HotSpot
`sharedRuntime.cpp:561` safepoint assertion while title assets were loading; the controller timed out
at `main-menu-ready` and stopped the owned process. AI Tweaks combat classes had not executed, so this
is a profiler/harness failure, not a candidate result. The bounded console and scenario hashes are
`3363f936d43e5b417345d3c69d33d91101923e4d84587032c1d8c8ba025998bc` and
`43425f80db8d8464e8d279a8b6a79efe32c4e6f20c2ad0d746b9839216ca43c8`.

The no-JFR retry kept Preflight, the candidate, frame telemetry, adapter health, and the exact ordinary
route. All 33 semantic steps passed: 8 allied versus 25 opposing ships, 1x simulation, autopilot,
verified zoom from 1.250 to 4.250, an exact 60-second window, and controlled shutdown. It recorded:

| Ordinary B-only observation | Value |
| --- | ---: |
| Selection contexts / misses / hits | 64,153 / 64,153 / 4,435,773 |
| Abandoned contexts | 0 |
| Frames | 3,084 |
| Average / median FPS | 50.95 / 54.35 |
| 1% / 0.1% low | 19.16 / 11.93 FPS |
| p95 / p99 | 26.7 / 52.2 ms |
| Stutter burden | 27.51 ms/s |
| Recurring slow-frame exposure | 2.46% |

All 70 registered exact transforms applied, with zero declines and zero contained failures. This
validates behavior and both wrapper branches in ordinary combat; it does not establish improvement.

## 1,040-DP stress observations

The three adjacent Preflight-only runs used the same profile fingerprint
`2995668308ac3d31d645ccac30fb1a7e644e64fce5609050a1488df4cadc5af6`, texture fingerprint
`59b01dc050f39a9f07053bd168cc8c1ecd55086b429b2d732456f87ca217a702`, 1440x932
windowed display, Recommended/full adapter policy, G1 Rosetta policy, game build, selected campaign,
and `campaign-simulation-combat-1000dp`. Every completed route built 24 mirrored ships and 520 DP per
side, enabled 2x speed, verified zoom, measured 30.005 seconds, and stopped the exact owned process.

They are not a publication-quality controlled cohort. Battle evolution is deterministic enough to
repeat the workload but not lockstep; verified visible width varied from 5,744.0 to 5,805.6; the
near-control had the caller bracket installed while the helper exact gate declined; and expected
candidate corrections changed the Preflight JAR hash. These limitations permit a rejection/no-win
decision but not a positive FPS claim.

| Profiled exact window | Near-control: helper declined | Corrected candidate | Final exact-build candidate |
| --- | ---: | ---: | ---: |
| Candidate installed | no (caller only) | yes | yes |
| Frames | 590 | 586 | 528 |
| Average FPS | 19.49 | 19.37 | 17.49 |
| Median FPS | 21.28 | 20.75 | 18.87 |
| 1% low | 6.35 | 6.37 | 6.49 |
| 0.1% low | 5.50 | 4.27 | 5.25 |
| p95 / p99 | 85.3 / 157.5 ms | 78.8 / 157.1 ms | 93.6 / 154.1 ms |
| Stutter burden | 354.30 ms/s | 356.40 ms/s | 417.07 ms/s |
| Recurring exposure | 93.22% | 94.37% | 99.43% |
| Longest recurring cluster | 22.086 s | 23.015 s | 25.556 s |

Relative to the near-control, the final candidate's diagnostic frame co-observation was -10.26%
average FPS, +2.20% 1% low, +9.73% p95 frame time, +17.72% stutter burden, and +6.21 points recurring
exposure. The intermediate candidate was mostly flat. This inconsistency reinforces “no demonstrated
user-visible benefit”; it does not prove the wrapper caused the final battle's worse evolution.

## Allocation and CPU attribution

The adjacent near-control had 651 combat execution samples and 2,646 combat allocation samples. Its
`computePosition` filter retained 391.3 MiB total weighted allocation, including 194.1 MiB under
`SelectTarget`. The final candidate had 624 combat execution samples and 2,652 combat allocation
samples. Its same filter retained 206.5 MiB total and only 2.0 MiB under `SelectTarget`. The intended
family was therefore effectively removed while unrelated weapon-location work remained in firing,
ally-hit analysis, movement, render, and pivot paths.

The remaining `SelectTarget` allocation was still broad. In the final run it retained about 461.1 MiB
of weighted allocation, led by 247.5 MiB of `Float.valueOf`, then vectors, `LinearMotion`, ballistic
targets, beams, arrays, and arcs. Different battle evolution makes aggregate weights non-additive and
non-causal. The important falsifiable result is narrower: the reviewed repeated location-computation
leaf disappeared while the larger target-selection frame problem did not.

The final wrapper appeared in 20/624 combat execution samples, compared with 0/651 in the declined
near-control and 5/659 in the intermediate candidate. That variability prevents a precise CPU-cost
estimate, but it is enough to reject the assumption that the ThreadLocal/global getter interception
is free. Future work on this family must avoid adding a hot wrapper to every `WeaponHandle.getLocation`
call or prove its cost in a thin, shuffled cohort.

## Falsifiers and open questions

This experiment would be worth revisiting only if a new design removes the global getter tax while
retaining the exact semantic boundary—for example, an exact local scalarization whose receiver use is
proved without synthetic instance fields. It must also explain the prior `SelectTarget` null-receiver
failures rather than merely changing a field name.

A future candidate fails if either transform drifts, a partial installation performs work, misses do
not equal selection contexts, abandoned contexts are nonzero, original getter fallback is absent, an
ordinary scenario fails, or a thin interleaved cohort cannot show player-visible improvement. The
247.5 MiB sampled boxing family is a new lead, not permission to revive the unsafe combined target.

Compact machine-readable values and artifact hashes are in
[`data/2026-08-28-aitweaks-weapon-location-selection-rejected.json`](data/2026-08-28-aitweaks-weapon-location-selection-rejected.json).
Raw JFRs, full logs, screenshots, loader-produced classes, and run directories are disposable and are
not committed.
