# Frame-pacing investigation notebook

This is the maintained resume point for campaign and combat smoothness work. Dated files under
`docs/evidence/` are immutable point-in-time records; this page is intentionally mutable. Update it
when a measurement falsifies a hypothesis, an explored area becomes interesting again, or a better
next probe becomes available.

## How to read and update this page

- **Observed** means a checked artifact or exact bytecode supports the statement.
- **Hypothesis** means the statement has a named way to be falsified. Do not promote it from one
  noisy run.
- **Explored, not exhausted** means prior work narrowed the area but did not prove that every useful
  optimization at that boundary is spent.
- Preserve rejected ideas and the reason they failed. A new implementation or workload may change
  the answer, but it should have to address that reason explicitly.
- Prefer actual pause-state frame buckets over scenario step names. A modal can pause the sector
  during a step that began unpaused.
- Never combine startup, paused campaign, active campaign, and combat into one FPS claim.

## Current measurement frontier

[Gameplay FPS program #449](https://github.com/teamleaderleo/preflight/issues/449) is the canonical
parent for this work. The point-in-time
[#449 reconciliation](evidence/2026-08-28-issue-449-program-reconciliation.md) distinguishes the
public `main` baseline from this working branch, maps the implemented and missing workstreams, and
defines the next narrow hitch-packet slice. Do not treat a local experiment commit as evidence that
the public branch already contains the capability.

The current implementation checkpoint is `fbe62fcc`, which retains the completed and rejected exact
matrix-identity experiment while removing its candidate from active code. It also retains the bounded
asynchronous whole-frame GPU timer with explicit query ownership and pre-context-destroy cleanup, the
first bounded joined hitch packet, and the accepted v2 collision set,
exact-step hitch enrichment, distinct recurring-cluster breadth, and CI coverage for all gameplay
analysis scripts. The AI Tweaks weapon-location helper candidate at `5b6035aa` and the Detailed Combat
Results state-map reuse candidate were both live-tested and reverted. The AI Tweaks candidate removed
its exact allocation target but did not demonstrate a player-visible improvement and added a sampled
global getter tax; DCR installed and completed safely but substantially regressed the 1,040-DP
stress window.
The compact-index, texture-bind deduplication, and matrix-identity elision candidates remain rejected.
The latter two removed millions of exact GL calls in thin 1,040-DP cohorts without a reproducible
tail-smoothness win. The
latest accepted campaign measurement checkpoint remains `fee6c7b8`, following the cluster
recorder/analyzer at `cf761d2c9089e7ef46f11d741166f0b3bc1d413c`. One Preflight-only
`campaign-sample-paused-unpaused` run of those source bytes passed every semantic step on
2026-08-27, used one owned game process, and dropped zero inactive or invalid frame intervals. Its
bounded record is [repeated cluster attribution](evidence/data/2026-08-27-repeated-cluster-attribution.json).
The raw JFR and launch directory are disposable and are identified by hashes in that record.

Actual state-separated settled distributions were:

| State | Frames | Average | 1% low | 0.1% low | p99 | Repeated slow frames | Stutter burden |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Paused | 3,376 | 55.85 FPS | 41.32 FPS | 23.75 FPS | 24.2 ms | 0.00% | 1.36 ms/s |
| Unpaused | 1,739 | 50.50 FPS | 14.64 FPS | 6.04 FPS | 68.3 ms | 3.28% | 59.44 ms/s |

The active-campaign tail remains the primary campaign problem. Paused performance is substantially
cleaner, but isolated paused hitches still exist. Combat remains an equal product goal; this run did
not exercise combat.

These numbers are one current B observation, not a comparison against the prior run. Workload and
thermal state were not locked, so apparent deltas must not be advertised as improvement.

The later 60 Hz precision-limiter successor is also settled as a rejection. Two thin installed-host
candidate runs remained mechanically healthy but changed the retained VSync-off context by only about
-1.5% p99 and +1.6% 1% low while lowering average FPS about 2.6%. Its deadline policy added about
0.63 ms beyond the game's integer-millisecond request and still overshot about 0.68 ms on average.
Do not rerun that waiter unchanged; the large paused-campaign result remains VSync-off itself.

## Current live-validation queue

### Hitch packet v1 retained (`2f0ea0b3`)

**Observed:** a Preflight-only corrected live route retained one complete 175-frame paused-campaign
packet around a 50.003 ms trigger. The frame spent 49.210 ms before native swap, 0.538 ms in native
swap, 0.248 ms in message processing, and 0.006 ms in other post-swap work. A complete join of
16,284 exact broad campaign-phase calls attributed only about 0.171 ms of the trigger. The packet
therefore rules out presentation wait and those nine broad campaign phases for this hitch.

**Observed:** the discovery run corrected the instrument rather than being discarded. It showed
that an 8,192-call ring could not cover three seconds in this profile, that title-demo combat could
consume gameplay packet slots, and that rich bounded evidence could exceed the old 512 KiB reader
ceiling. The retained shape uses 32,768 primitive call spans, excludes combat before campaign is
seen, and accepts at most 4 MiB per adapter/report source file.

**Measurement discipline:** ordinary frame capture uses fixed primitive arrays and no per-frame
objects. Exact campaign call spans remain discovery-only. Live report snapshots are best effort;
the sealed shutdown report is authoritative. Inclusive display-boundary overhead averaged 15.784
microseconds in the confirmation run. An isolated actual-game-runtime calibration measured 2.317
ns per enabled no-trigger ring write, but is not an FPS claim.

**Exact next action:** split `preSwap` into actual game CPU work versus limiter/cap waiting. Do not
start with another broad campaign timer or GPU experiment for this specific fingerprint. If game
CPU remains large, let a thin trigger arm a short higher-detail CPU capture for the next matching
hitch. Preserve the original packet as the non-intrusive performance observation.

See the [bounded hitch-packet record](evidence/2026-08-28-hitch-packet-v1.md) and child
[#1150](https://github.com/teamleaderleo/preflight/issues/1150).

### Exact campaign limiter split (`354d9006`)

**Observed:** the thin `campaign-hitch-limiter-current-state` route exact-matched the installed
`BaseGameState` loop and completed its limiter split on all 5,238 eligible paused frames. Both
captured >50 ms triggers remained game-work hitches after known limiter sleep was removed: 52.753
ms of a 53.521 ms frame and 45.981 ms of a 56.003 ms frame. Broad campaign timers and JFR were off.

**Observed:** the 1% tail is not the same fingerprint. Of 31 frames above 33.33 ms, 23 were
native-swap dominated, seven were remaining-pre-swap-work dominated, and one was limiter-oversleep
dominated. The run's 30.30 FPS 1% low therefore mostly reflects presentation/swap quantization,
while its two >50 ms hitches are game work.

**Measurement discipline:** this was one diagnostic confirmation, not a paired uplift cohort.
The loaded save stayed paused and the route issued no save action. Adapter health was ACTIVE with
64 exact transforms, zero declines/failures, and clean PID-bound shutdown. The inclusive boundary
hook averaged 16.409 microseconds; the two limiter timestamp hooks are outside that figure.

**Exact next action:** for broad player impact, establish actual swap-interval/context policy and
bounded GPU/driver attribution around native-swap clusters before trying a rendering change. For
the rarer >50 ms game-work fingerprint, let the thin trigger arm a short CPU capture for a later
matching hitch rather than leaving broad discovery timers permanently enabled.

See the [bounded limiter-split record](evidence/2026-08-28-campaign-limiter-split.md).

### Precision limiter rejected at 60 Hz (`codex/1157-installed-pacing`)

**Observed:** two Preflight-only 60-second paused windows exact-installed the deadline-based limiter,
forced swap interval zero, and reported zero adapter decline/failure or interrupted wait. B1/B2 were
57.52/57.48 average FPS, 22.3/22.7 ms p99, 44.84/44.05 FPS 1% low, and two isolated >33.33 ms frames
each. Every slow frame was pre-swap dominated; no presentation wait hid the result.

**Observed:** against the retained VSync-off observations, candidate arm medians moved p99 -1.5% and
1% low +1.6% while average FPS moved -2.6%. That is historical decision context, not an exact current
interleaved cohort, but it is far below the acceptance target and mixed enough to reject promotion.
The candidate extended the coarse request about 0.63 ms and then overshot the computed deadline about
0.68 ms on average while spinning only 2.3-2.6 microseconds. A successor must change deadline semantics
before another same-host run.

**Explored, not exhausted:** this rejects the current 60 Hz park/spin policy, not precision pacing in
general. Reconsider only for a materially different policy or a separate high-refresh display contract.
See the [bounded rejection record](evidence/2026-08-28-campaign-precision-limiter-rejected.md).

### Native-swap CPU versus off-CPU split (`5702a9ae`)

**Observed:** all 3,409 settled paused frames produced a valid current-thread CPU split around the
exact native `Display.swapBuffers()` call. Native-swap p99 was 17.4 ms; render-thread CPU p99 inside
it was 0.5 ms, while inferred off-CPU p99 was 17.1 ms. Ten of 12 frames above 33.33 ms were
swap-dominated. Those ten spent 97.948–98.296% of swap wall time off CPU, averaging 98.104%.

**Observed:** the installed settings request VSync and a 60 FPS cap. The live adapter observed four
actual interval-one and two interval-zero requests, no other values, and a final interval of one.
The scenario did not change presentation settings and the force-off experiment was disabled. The
observer does not yet timestamp policy requests per frame, so final interval one is not presented as
a complete interval history.

**Measurement discipline:** two cached current-thread CPU-clock reads are added per presented frame.
The shipped x86-64 game runtime's live 10,000-read calibration averaged 587.613 ns/read; all 12,552
hot-path reads succeeded. No JFR or broad campaign timer was active. The same scenario/profile/pause
route was retained, but the scenario does not bind exact save bytes, so this is within-run
attribution rather than a paired FPS claim.

**Live capability result (`3db5b1b1`):** the Apple M5 context reports OpenGL `2.1 Metal - 90.5`,
OpenGL 1.5 query objects, `GL_EXT_timer_query`, and `GL_ARB_sync`. It does not expose OpenGL 3.3 or
`GL_ARB_timer_query`. The one-time read-only inventory passed the short semantic route without
creating rendering state or producing an FPS claim.

**Exact next action:** use a fixed-size asynchronous EXT timer-query ring that never waits on the
hot path to split GPU execution from the remaining driver/compositor/VSync wait, and carry actual
interval state into the same window. Preserve a capability/disable fallback and bounded
unavailable/overrun telemetry. Do not change rendering or presentation merely because the off-CPU
boundary is large.

See the [native-swap CPU/off-CPU record](evidence/2026-08-28-native-swap-cpu-offcpu-split.md).
See the [live OpenGL capability record](evidence/2026-08-28-opengl-context-capability.md).

### Asynchronous GPU attribution (`5e465911`)

**Observed:** the final focused paused/unpaused route completed 8,312 asynchronous EXT query
results with a fixed 16-query ring. It had zero overrun, query-owner conflict, contained failure,
inactive interval, or invalid interval. The one-time post-begin ownership check passed, cleanup
ended the active query, and all 16 owned query objects were deleted before context destruction.

**Observed:** the recurring paused 33 ms tail was normally not GPU-bound. Paused GPU p99 was 4.4
ms while frame p99 was 33.0 ms and inferred swap off-CPU p99 was 16.4 ms. Representative quantized
frames paired 1.4–2.4 ms GPU time with 16.2–16.7 ms of swap off-CPU wait. Isolated GPU-heavy paused
frames still exist, so this does not claim every paused hitch is presentation.

**Observed:** the unpaused tail was often GPU-heavy. GPU p95/p99 were 25.4/51.4 ms. The worst paired
frame carried 177.9 ms of GPU work in a 221.1 ms frame with 0.148 ms inferred swap off-CPU time.
The aggregate includes the deliberate five-second transition, but an exact receipt join placed 45
of the 64 retained worst pairs after the settled step began, including the worst frame.

**Measurement discipline:** the query path is intrusive discovery instrumentation. Its two hooks
per frame averaged 428.361 microseconds each, roughly 0.857 ms/frame before any GPU/driver query
perturbation. Its FPS numbers are not an uplift/regression claim. CPU and GPU tracks overlap and are
not additive. The scenario still does not bind exact save bytes.

**Follow-up result:** the phase-owned broad census found 13,306 selected legacy-GL calls per active
frame, then the exact state model found 2,294 same-state reissues per frame. That is 40.53% of its
modeled families and 16.89% of the selected command stream. Texture binds repeated 53.68%,
enable/disable 38.23%, and blend state 37.09%. Slow-frame redundancy was effectively identical to
ordinary-frame redundancy, so this is a baseline submission-tax lead rather than the hitch cause.
The texture-bind and exact matrix-identity suppression candidates subsequently completed ordinary
correctness and thin interleaved 1,040-DP cohorts. Both removed millions of exact calls without a
reproducible tail-smoothness win. Keep the paused presentation branch separate; for combat, return
to bad-frame CPU/bytecode attribution before trying another speculative GL cache.

See the [asynchronous GPU timing record](evidence/2026-08-28-asynchronous-gpu-frame-timing.md).

### Exact GL matrix identity elision rejected (`9e263701`, retired by `fbe62fcc`)

**Observed:** the intrusive census counted 29,179,941 legacy matrix operations over 500 stress
frames. Exact identity/no-op operations contributed 2,387,807 calls (8.18%); zero-angle
`glRotatef` calls alone contributed 2,100,158. This is discovery evidence, not an FPS claim.

**Observed:** the exact candidate passed ordinary combat with all eight expected LWJGL methods
installed, a verified 4x viewport, clean visual evidence, no wrong-thread call, no scope leak, and no
runtime disable. In the thin B/A/A/B 1,040-DP cohort it removed a median 2,526,231 transforms/run
(13.40%), yet changed p99 -1.4%, 1% low +1.5%, >50 ms/min +2.6%, >100 ms/min +0.1%, stutter burden
-1.2%, and average FPS +0.8%. That is mixed run noise, not a player-visible win.

**Observed:** every measured slow frame was pre-swap dominated. Native swap averaged roughly 0.31
ms, so presentation wait did not hide a matrix improvement in this workload. The workload gate kept
the exact 32-versus-50 non-fighter fleets and bounded transient fighter-launch timing rather than
incorrectly requiring lockstep entity counts.

**Explored, not exhausted:** this rejects exact identity suppression at the LWJGL wrapper seam. It
does not reject all matrix work or neighboring GL families. Any successor must first show excess
presence in the bad combat frames and then use a thin cohort for the claim. See the
[bounded rejection record](evidence/2026-08-28-gl-matrix-identity-elision-rejected.md).

### AI Tweaks target-selection location reuse rejected (`5b6035aa`, reverted by `574cfd3b`)

**Observed:** the final exact-build candidate passed all 34 semantic steps in the symmetric
1,040-DP fixture and all 33 steps in a no-JFR ordinary 8-v-25 fixture. Both exact classes installed;
the final stress run recorded 60,980 contexts, 60,980 misses, 8,302,274 hits, and zero abandoned
contexts. No save/load or serialization class was transformed, neither scenario saved its in-memory
fixture, and controlled shutdown found no gameplay fatal.

**Observed:** the allocation premise was real. In the adjacent exact-window sample,
`SelectTarget`-attributed `computePosition` weight moved from 194.1 MiB in the helper-declined
near-control to 2.0 MiB in the final candidate. The overall `SelectTarget` allocation family remained
about 461.1 MiB, led by 247.5 MiB of `Float.valueOf`; eliminating one vector family did not spend the
target-selection boundary.

**Observed:** the player-visible result was not a win. The intrusively profiled stress observations
were 19.49 FPS near-control, 19.37 FPS intermediate B, and 17.49 FPS final B, with 1% lows effectively
flat at 6.35, 6.37, and 6.49 FPS. Final stutter burden was 417.07 ms/s versus 354.30 ms/s in the
near-control. Those distributions are diagnostic co-observations, not FPS claims: JFR sampling was
intrusive, battle evolution was not lockstep, viewport width varied slightly, and candidate corrections
changed the Preflight JAR. The thin ordinary run recorded 50.95 average FPS and 19.16 FPS 1% low but
had no A cohort.

**Observed:** the wrapper appeared on 20/624 final combat execution samples (3.21%), versus 0/651 in
the declined near-control and 5/659 in the intermediate candidate. That variability does not quantify
the cost precisely, but it falsifies the assumption that a `ThreadLocal` lookup on every global
`WeaponHandle.getLocation` call is free.

**Explored, not exhausted:** a future design must remove the global helper tax and cannot reintroduce
synthetic `SelectTarget` instance fields without explaining the prior null-receiver failures. The
boxing family is a lead, not a ready target. Require exact receiver/use proof plus a thin shuffled
cohort before shipping another intervention. See the
[bounded rejection record](evidence/2026-08-28-aitweaks-weapon-location-selection-rejected.md).

### Detailed Combat Results map reuse rejected (`734555bc`, reverted by `418be653`)

**Status:** the fresh foreground baseline, one combined-opt-in stress B, and one ordinary-fixture B
are complete. Every run passed its semantic steps and exact process shutdown. The accepted plans
are now promoted at the Recommended preset boundary. The additional Detailed Combat Results map-reuse
candidate was tested and rejected. See
[accepted combat opt-ins](evidence/2026-08-27-combat-accepted-optins-pair.md) and
[Recommended combat promotion](evidence/2026-08-27-recommended-combat-promotion.md), plus the
[DCR candidate audit](evidence/2026-08-27-dcr-state-map-reuse-candidate.md).

The compact-index v3 candidate at `a7ffaf78` passed exact tests and full Java 17 verification, but
an exact layout check falsified its allocation premise before live launch. With the installed
game's 6 GiB heap, compressed object references and `int` array elements are both four bytes. The
accepted v2 shape (two `Object[]` plus one `int[]`) and candidate v3 shape (one `Object[]` plus two
`int[]`) therefore each use 12 payload bytes per capacity slot. V3 added lookup indirection without
an established allocation-byte saving and was reverted by `bb3ebcc3`. See the immutable
[rejection record](evidence/2026-08-27-collision-query-compact-index-rejected.md).

**Observed:** the default exact window measured 25.06 average FPS, 8.23 FPS 1% low, 193.18 ms/s
stutter burden, and 65.70% recurring-slow-frame exposure. Collision v2 hit 830,242/831,063 capacity
hints and avoided 496,928 growths. The combined accepted-opt-in run measured 26.63 average FPS,
8.51 FPS 1% low, 167.54 ms/s stutter burden, and 56.15% recurring exposure. Its listener shortcut
served 45,881,368 exact empty snapshots with zero non-empty or unknown-list cases.

**Observed:** the ordinary 8-v-25, 1x, 60-second B also passed cleanly. It recorded 55.34 average
FPS, 18.42 FPS 1% low, 31.27 ms/s stutter burden, and 1.82% recurring exposure. The listener path
served 12,391,414 empty snapshots and safely delegated all 6,867 non-empty cases to fresh snapshots.
No unknown list implementation appeared.

**Falsifier for the promotion:** an exact transform failure, a semantic mismatch in a delegated
listener case, or a later paired ordinary result showing a repeatable regression. Do not resurrect
collision v3 unless runtime layout changes or GC reference scanning becomes a measured dominant
cost.

**Observed:** the installed Detailed Combat Results 5.4.3 `FrameProcessorState.updateCommonState`
creates three new `HashMap` instances on every unpaused combat frame: one retained-projectile map,
one killed-ship copy, and one next-frame alive-ship map. The exact class is Java 16 bytecode and is
loaded from a Java 17-compatible mod archive. The rejected opt-in candidate mutated the projectile
map in place and rotated the three already-distinct ship maps. It did not reduce the damage detector's
per-frame cadence, alter a save/load class, or serialize new state.

**Observed:** both Preflight-only runs installed the exact transform, produced nonzero candidate
telemetry, passed controller/shutdown safety, and emitted no DCR error. The ordinary window measured
48.06 average FPS, 16.18 FPS 1% low, 33.32 ms/s stutter burden, and 2.61% recurring exposure. The
1,040-DP window measured 19.15 average FPS, 7.32 FPS 1% low, 362.93 ms/s stutter burden, and 96.20%
recurring exposure. Relative to the retained accepted B, that is -28.09% average FPS, -13.98% 1%
low, +116.62% stutter burden, and +40.05 points recurring exposure. This is a rejection signal, not
a claim that a particular changed `HashMap` encounter order explains the full delta. See the
[bounded rejection record](evidence/data/2026-08-27-dcr-state-map-reuse-rejected.json).

**Explored, not exhausted:** exact-window allocation sampling still attributed roughly 6 MiB of
sampled weight to `Double.valueOf` while DCR aged projectile-history entries and roughly 6 MiB to
`Helpers.concat`/`Arrays.copyOf`. Those are narrower candidates that do not require rotating or
mutating the original maps. The rest of the damage-analysis pipeline still allocates sets, lists,
and match objects. Rejecting map reuse does not spend this family.

**Exact next action:** inspect DCR's `Helpers.concat` call and projectile-age representation on the
exact installed bytes. Prefer a transformation that preserves source-list and map encounter order.
Require an offline allocation proof before another live run; do not repeat A.

The current analyzer builds on checkpoint `b099f354`: `--repeated-clusters` may be combined with
`--step`, intersects the state-derived cluster windows with the exact receipt, and fails closed if
they do not overlap. Its enrichment view then compares those cluster samples with the non-cluster
remainder of the same exact step, ranked by excess presence rather than rare-event lift. Run the CPU
form as:

`python3 scripts/starsector_gameplay_hotspots.py RUN/startup.jfr --scenario-evidence RUN/smoke-evidence.json --step combat-sample-1040dp --frame-report RUN/runtime-frame-report.json --frame-series combatAfterCampaignActive --repeated-clusters 32 --cluster-enrichment`

For the allocation form, omit `--cluster-enrichment` and add `--allocations`. For the
candidate-specific allocation family, also add
`--contains 'com.fs.starfarer.combat.o0OO.G$o'`. This prevents setup, deployment, camera motion, or
any frame outside the declared battle window from entering the cluster attribution.

For the symmetric 1,040-DP fixture, do not use the fixed 33.33 ms cluster threshold as hitch
enrichment when ordinary frames already exceed it. The 2026-08-28 profiled run averaged 50.889
ms/frame, so that selector covered 29.372 of the 30-second step. Use the complete packet-backed severe
population instead:

`python3 scripts/starsector_gameplay_hotspots.py RUN/startup.jfr --scenario-evidence RUN/smoke-evidence.json --step combat-sample-1040dp --frame-report RUN/runtime-frame-report.json --hitch-frame-millis 100 --cluster-enrichment`

That pass produced 18 intersected severe groups, 51 combat execution samples, and 607 same-step
background samples. Advanced Gunnery Control was the strongest narrow lift but appeared in only two
groups; `WeaponGroup.advance` was broad but only 1.25x enriched. Allocation composition was similar in
the severe and whole-step populations. No candidate was promoted. See the
[bounded severe-frame attribution record](evidence/2026-08-28-combat-severe-frame-attribution.md).

## Resume recipe after compaction

1. Confirm there is no existing Starsector process and use only a Preflight launch. The current
   save should be observed untouched for three seconds; the controller should report
   `campaign pause state already matched request` before the paused window.
2. For a newly observed hitch, inspect the sealed `frameTimes.hitchPackets` packet first. Use the
   exact campaign call-time producer only for discovery runs. Run `campaign-sample-paused-unpaused.json`
   for broad sampled attribution only when the packet cannot choose the next boundary. Do not
   repeat an A run unless it can change the next decision.
3. Rank active recurring stutter with:
   `python3 scripts/starsector_gameplay_hotspots.py RUN/startup.jfr --scenario-evidence RUN/smoke-evidence.json --frame-report RUN/runtime-frame-report.json --frame-series campaignUnpausedAfter30SecondsActive --repeated-clusters 10`.
   Add `--allocations` for weighted allocation samples and `--contains TEXT` for a named stack.
   For deep call timers, run `starsector_campaign_cluster_calls.py` and always add
   `--scenario-evidence RUN/smoke-evidence.json --step unpaused-settled` when the question is the
   settled route. The state bucket alone includes the deliberate post-unpause transition.
   In a workload whose baseline frame time already exceeds 33.33 ms, replace the repeated-cluster
   selector with `--hitch-frame-millis 100`; otherwise the selected windows can swallow almost the
   entire step and make enrichment meaningless.
4. Keep observations and hypotheses separate below. A previous optimization narrows a boundary; it
   does not make the boundary permanently uninteresting.
5. Commit bounded JSON/Markdown and hashes, then remove raw JFRs, logs, and rebuildable binaries.

## Confirmed observations

### The Nexerelin economy-info callback is an exact whole-cache rebuild

Installed Nexerelin 0.12.2b bytecode and its source-bearing `ExerelinCore.jar` agree: anonymous
`EconomyInfoHelper$1.doAction()` contains only `collectEconomicData(false)`. That method clears all
ten maintained cache collections, then rebuilds commodity producers/importers/demand, heavy-industry
membership, AI-core use, and faction income.

The shipped core implementation makes the commodity loop more interesting than the source alone
suggests. Each `CommodityMarketDataAPI.getMarkets()` call allocates a new list and scans every
economy market for the commodity's economy group. Nexerelin calls it three times per commodity.
Before those passes, `getMarketSharePercentPerFaction()` performs another group scan and then calls
`getMarketSharePercent()` once per distinct faction; each of those calls performs another allocated
group scan.

The exact phase run resolved the original question: four rebuilds consumed 150.262 ms and the
commodity scan owned 137.730 ms. Two refreshes together explained 65.4% of a 95.248 ms frame, while
a third explained 48.9% of a 58.965 ms frame. The exact scoped-list candidate then passed 5,460
fresh-result order/identity comparisons with no mismatch or failure. Its offline causal derivation is
also exact: it should collapse 1,404 list builds per rebuild to 39, avoiding 253,890 group-membership
tests per rebuild.

Do not treat a poor v1 frame result as exhaustion of the whole seam. Stock still performs 220,896
`getExportMarketSharePercent` visits per rebuild because it rescans 177 markets for each of 32
factions. A separately shadowed one-pass successor could reduce that to 6,903 while preserving
first-faction order, identity membership, player-owned semantics, and final private-map state. Settle
the already-shadowed list candidate before promoting that stronger rewrite. See the
[Nexerelin economy-info record](evidence/2026-08-28-nexerelin-economy-info-hitches.md).

### Commodity event-mod validation remains a large CPU category

The 45-second `unpaused-settled` wall window contained 471 campaign main-thread execution samples.
`CommodityOnMarket.reapplyEventMod` was the leaf in 86 (18.26%); 84 of those stacks were below
`Economy.advance`. The installed exact-zero memo was enabled, production telemetry was disabled,
and `fastValidationUnavailable` remained zero.

This does **not** prove an 18.26% universal share. The same route has previously measured materially
lower shares, and statistical samples vary with campaign work. It does prove that the already
optimized wrapper can again become a coherent top leaf and is not a permanently spent area.

### Empty maps dominate the exact-zero event-mod path

The exact-gated empty-map shortcut is now accepted. A profiling-only live run observed 38,352,454
fast hits and 193,824 delegations. Of the fast hits, 33,350,500 (86.96%) had an empty exact
`available.flatMods` backing map and now return before the keyed `eMod` lookup. Another 5,001,954
fast hits had a nonempty map with no `eMod` and retain the exact-key check.

This answers the branch-frequency question and justifies the narrower bytecode shape. It does not
measure the saved JIT cost or exhaust the broader wrapper. Production builds still omit these
counters entirely. See [the bounded empty-map record](evidence/2026-08-27-event-mod-empty-map-fast-path.md).

### Repeated active-campaign hitches cross broad advance work

The cluster-aware recorder retained 22 repeated slow-frame clusters in each of two recent active
campaign windows. In the first run, the ten longest windows covered 2.176 seconds; 51 of 54
execution samples were campaign samples. `CampaignEngine.advance` was inclusive in 47/51,
location/system advance in 25/51, fleet advance in 18/51, and economy and fleet AI in 8/51 each.
The follow-up again spread samples across engine, location, fleet, economy, AI, UI, and event work.

**Hypothesis:** a scheduled or accumulated-delta boundary batches multiple campaign systems and
creates the visible clusters. **Falsifier:** another controlled trace concentrates the clusters in
one unrelated leaf, or cadence probes show no shared burst/catch-up boundary. The current sample
does not identify the scheduler or justify changing simulation cadence.

### Post-unpause catch-up and settled-route stutter are distinct

An exact deep-timer run split the 22 active repeated clusters by scenario receipt. Seven clusters
covering 25 frames occurred inside the five-second post-unpause transition; 15 clusters covering 34
frames occurred in the following 45-second settled route. The transition had far more retained
overlap with locations (346 ms), economy (177 ms), fleet AI (136 ms), and market advance (93 ms).

The settled route's leading retained timers were one 69 ms location call, 58 ms of economy across
five calls, 50 ms of fleet AI across four calls, and 34 ms of economy-stepper work across seven
calls. Timer rows nest and do not explain the rest of the 1.673 seconds of cluster windows.

This partially falsifies the single-boundary interpretation above: a broad catch-up burst is a good
description of the transition, while settled stutter remains mixed. See
[the exact call-window record](evidence/2026-08-27-campaign-call-cluster-correlation.md).

### The slow event-mod fingerprint no longer copies the full modifier map

The first exact-cluster trace attributed 8 MiB of allocation-sample weight to
`MutableStat.getFlatMods`; every matching sample came from the commodity memo's post-vanilla slow
fingerprint. The exact transformed accessor now reads only `eMod` from the reviewed backing map and
treats Starsector's null backing field as the empty representation. The follow-up trace recorded
zero matching samples among 28 campaign allocation samples in its ten longest active clusters.

This is an accepted removal of an observed allocation stack, not an FPS-uplift claim. JFR allocation
sampling is weighted and incomplete, and the follow-up still had 22 repeated active clusters. See
[the bounded cluster record](evidence/2026-08-27-repeated-cluster-attribution.md).

### The wall-clock "unpaused" step included a second sector pause

Stellar Networks' `MarketUpdater.advance` only performs work when `SectorAPI.isPaused()` is true.
Runtime telemetry nevertheless recorded two pause intervals and two complete 186-market passes
during the overall scenario. The second pass occurred inside the wall-clock
`unpaused-settled` step, so that step's JFR ranking mixes genuine active campaign work with an
autopause/modal interval.

The frame report's `campaignUnpausedAfter30SecondsActive` bucket remains valid because it classifies
each frame by the observed pause state. The JFR step-window ranking is broader and must not be called
a pure unpaused profile.

### A second Stellar Networks pass creates a concentrated allocation burst

The mixed wall window attributed about 119 MiB of JFR allocation-sample weight to ship-stat objects
created below `FleetMember.updateStats`; about 99 MiB ran through Stellar Networks market refresh,
submarket cargo sorting, and fleet synchronization. JFR weights are estimates, not an allocation
census. Telemetry recorded 372 markets queued and served, 4,745 exhausted paused frames, one
invalidation, zero delegation, and zero runtime failures.

The accepted paused-pass optimization is working: after each pass it becomes idle. The remaining
question is the cost and necessity of starting another complete pass after a short unpaused period
or modal transition, not whether the existing exhaustion gate failed.

### The worst exact active-unpaused frame crossed tooltip reflection work

The worst frame in the actual active-unpaused bucket was 272.524 ms. Five execution samples mapped
to that interval; four were campaign main-thread samples. They crossed Random Assortment of Things'
`WhichModScript`/`AICoreTooltipScript` UI traversal and reflection, fleet-list work, and rendering.
This is useful attribution for an isolated hitch, not proof that tooltip work causes the recurring
3.71% slow-frame exposure.

### Stable snapshots still deserve observation after cursor reuse

The current run ended with 448 stable snapshot owners and 960 retained cursor identities. JFR
allocation samples attributed about 34 MiB to `CampaignEntityMaintenanceRuntime.stableSnapshot`
and 5.8 MiB to `SnapshotIterator`. Prior cursor reuse work materially reduced this category, but
snapshot rebuilds and old cursor identities can still accumulate within the bounded maps.

## Explored, not exhausted

| Area | What prior work established | Why it may still matter | Next falsifiable question |
| --- | --- | --- | --- |
| Commodity event mods | Exact SHA-gated memo, mutation-aware direct-backed slow fingerprint, zero-result split, production counter-call elision, and empty-map return are accepted. The empty branch covered 86.96% of live fast hits; the observed whole-map-copy stack disappeared from the follow-up sample. | The wrapper has previously returned as a major CPU leaf, and 5,001,954 nonempty/no-`eMod` hits still performed the exact-key lookup. Allocation removal does not establish that the residual CPU boundary is spent. | After production JIT compilation, which residual wrapper instruction or caller boundary dominates, and is the nonempty/no-`eMod` subpath worth a safe additional proof? |
| Stellar Networks refresh | One shuffled pass per paused interval replaces endless random refresh and then becomes idle. | A second pause starts another expensive 186-market burst, which can overlap a nominally unpaused route. | Can invalidation or refresh cadence be bounded by actual campaign-time advancement without making opened market data stale? |
| RAT tooltip scripts | No Preflight optimization exists; source shows per-frame UI copies and reflection, with a "do not modify twice" UI sentinel in the AI-core path. | The worst exact active frame crossed this code. | Does an identity/content guard remove repeated work without missing a tooltip object reused for a different entry? |
| Stable campaign snapshots | Stable arrays and exhausted cursor reuse are accepted and bounded. | Current allocation samples still show rebuild/cursor cost and cursor identities outnumber owners. | Which owners rebuild, how often, and can stale cursor entries be removed when an owner receives a replacement array? |
| Nexerelin economy-info rebuild | Two slow anonymous callbacks overlapped >100 ms frames; installed bytecode resolves both to `collectEconomicData(false)`. Static inspection proves repeated allocated full-market scans nested under every commodity and faction. | The exact rebuild is compact but potentially superlinear in commodities × markets × factions. Static structure does not establish which phase dominates or whether list reuse produces a player-visible win. | In the focused foreground run, how much time belongs to market-share setup versus producer/importer/demand passes, and how many commodities/market visits drive it? |
| Paused/unpaused attribution | Frame buckets are state-separated and focus-clean. Repeated clusters now correlate with bounded exact campaign calls and exact scenario steps. This separated seven post-unpause clusters from 15 settled clusters. | Transition work is a broad catch-up burst; the settled route still mixes occasional large spikes with recurring small calls, and retained children explain only part of cluster wall time. | Does the transition ordering repeat, and which individual settled frames align with the 69–90 ms location spikes? |
| Combat | Deterministic simulation, autopilot, speed-up, exact 4x viewport, explicit frame windows, presentation splits, and bounded workload fingerprints are live. Collision v2 hit 99.90% of capacity hints in the fresh default run. The accepted plans are in Recommended; v3 compact indexes, DCR state-map reuse, the global AI Tweaks location wrapper, texture-bind deduplication, and exact matrix-identity elision are rejected. The GL candidates each removed millions of calls in thin B/A/A/B cohorts but did not move tail smoothness reproducibly. | The AI simulation is workload-bounded rather than lockstep, and residual hitches span AI Tweaks, vanilla ship/weapon work, graphics, collision, and damage-analysis mods. The two GL rejections show that even enormous JNI/driver-call volume may be diffuse baseline tax rather than the bad-frame cause. Rejecting those implementations does not spend their underlying families. | Which semantic CPU/bytecode family has the highest excess presence across repeated >33 ms clusters, and can a thin hitch packet escalate only around those frames? |

## Open questions, ranked

1. **Nexerelin economy-info decomposition:** run the exact focused phase/cardinality probe with the
   foreground route. Require exact span/frame joins and clean adapter health. If the repeated
   allocated market scans dominate, test only a reviewed per-commodity reuse boundary; otherwise
   retain the owner explanation and move on.
2. **Combat scaling coefficients:** the exact `>=100 ms` stress-frame pass did not find a broad narrow
   CPU or allocation family to promote, and the unchanged 60 Hz precision waiter is now rejected.
   Use the existing 1,040-DP fixture and thin recorder to populate #1155's real scaling coefficients
   across a bounded DP ladder. This should distinguish superlinear simulation/render families from
   diffuse per-entity tax before another bytecode intervention.
3. **Transition versus settled active work:** repeat the exact-step correlation once when a code
   decision depends on it. If the transition ordering is stable, probe its catch-up scheduler;
   independently map settled timer calls to individual slow frames before adding broader timers.
4. **Commodity residual cost:** the empty/nonempty/delegated traffic split is now known. Map JIT
   samples or a faithful extracted benchmark to the remaining nonempty exact-key path, runtime
   enable gate, and caller boundary. Production must retain zero diagnostic writes.
5. **RAT tooltip idempotence:** determine whether tooltip identity plus codex-entry identity is a
   sufficient replay guard. Inspect both `WhichModScript` and `AICoreTooltipScript`; optimizing only
   the reflection cache may leave the per-frame copied UI traversal intact.
6. **Stellar Networks refresh epochs:** test whether a pass is necessary after a short unpaused
   interval and which listener events already express real market invalidation.
7. **Stable snapshot ownership:** instrument rebuilds by transformed loop kind before redesigning
   cursor retention.
8. **Combat residual frontier:** after the scaling coefficients, choose either one separated
   render-sync/GraphicsLib candidate from #1153 or a bounded #1156 GPU/resource diagnostic. Avoid the
   reverted DCR map rotation, retired AI Tweaks `SelectTarget` fields, rejected global location wrapper,
   and unchanged precision waiter. Any new allocation candidate must separate intrusive discovery from
   thin measurement; stress does not replace ordinary play.
9. **Rosetta tax:** if GL command counts show a large CPU-side dispatch boundary, compare an extracted
   equivalent call stream under native ARM and x86/Rosetta, then seek a same-scenario cross-machine
   control. Do not attribute GPU elapsed time or off-CPU swap wait directly to instruction translation.

## Frequently revisited questions

### Does a 30 FPS 1% low always mean every other frame missed a 60 Hz deadline?

No. Percentile conversion can turn a 33.3 ms frame into 30 FPS, but the product question is whether
slow frames are isolated or clustered. Rank repeated-cluster exposure and excess slow-frame time
alongside 1% and 0.1% lows.

### Is paused campaign work unimportant?

No. Players spend time in markets, tooltips, dialogs, and planning screens. Paused work also reveals
economy and UI maintenance that can hitch transitions. It should have its own target and claim, not
be mixed into active campaign numbers.

### Has an area been exhausted once an optimization was accepted?

No. Acceptance proves a particular boundary and implementation under its evidence. A new profile
can show residual cost inside the optimized wrapper, a different caller, or an invalidation pattern
the first change deliberately left intact.

### Why was the compact-index candidate rejected without a game run?

Its stated win was lower array allocation. The exact current JVM uses four-byte compressed object
references and four-byte integers, leaving v2 and v3 at the same 12-byte payload per capacity slot.
A game run could still produce noise, but it could not restore the disproven allocation premise.
The result is preserved as a rejection while the larger collision and combat areas remain open.

### Does a stack found inside a repeated cluster prove it caused the cluster?

No. It establishes temporal overlap at the JFR sampling resolution. Repeated appearance, inclusive
share, exact call-time probes, or an on/off intervention can strengthen causality. A single sampled
leaf inside one cluster remains a lead, not a conclusion. Cluster enrichment improves prioritization
by comparing against non-cluster samples from the same exact step and reports how many distinct
clusters contain each method. Neither overrepresentation nor broad cluster coverage proves that the
method caused the frame delay.

### Did the 1,040-DP AI Tweaks location experiment work?

Structurally, yes: it removed almost all sampled `SelectTarget -> computePosition` allocation and
served more than eight million repeated reads per run. As a gameplay optimization, no: the profiled
frame observations did not improve consistently, the wrapper itself appeared in CPU samples, and the
only thin ordinary observation lacked an A cohort. The code is preserved in Git history and reverted.
That area is explored, not exhausted; a future design must avoid taxing every global getter call.

### Did removing millions of exact matrix operations improve combat smoothness?

No. The exact candidate removed a median 2.53 million identity transforms per 30-second stress run,
but its thin interleaved cohort moved p99 only -1.4%, 1% low +1.5%, and >50 ms frame rate in the
wrong direction by 2.6%. The candidate was retired. The result narrows one wrapper seam; it does not
prove that all matrix or OpenGL work is irrelevant.

### Does `campaignUnpausedAfter30SecondsActive` mean settled after unpausing?

No. It means active campaign frames after the recorder's campaign warmup boundary. In the current
scenario it includes the explicit five-second post-unpause transition. Intersect with the exact
`unpaused-settled` receipt step before making a steady-state claim.

### Is Rosetta the big gameplay-FPS problem?

It is a credible amplifier, not a measured percentage. The shipped JVM and native libraries are
x86-64, so a native ARM JVM is not a drop-in control. The exact active-campaign census did expose
roughly 700,000 selected legacy-GL wrapper calls per second entering the Rosetta/JNI/Apple
OpenGL-over-Metal path. That can create a large steady tax, but selected call volume barely differed
between ordinary and slow frames, so it does not explain the hitch tail by itself.

### Can raw profiles be committed?

Do not commit JFRs, transformed classes, game/mod binaries, saves, or full logs. Commit bounded JSON,
hashes, methodology, and conclusions. Game screenshots are acceptable when they add useful visual
evidence and contain no sensitive material.

### Could the rejected DCR combat candidate have damaged a campaign save?

No save/load or serialization class was transformed. Its exact target was a transient combat
damage-detector state class, and both live runs exited without a fatal or DCR error. It was reverted
for runtime performance and semantic-risk reasons, not because it wrote or migrated save data.
