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

The current implementation checkpoint is `0e7c00e45f6f3deb6a17573098b68da34d47a6c2`, following the
cluster recorder/analyzer at `cf761d2c9089e7ef46f11d741166f0b3bc1d413c`. One Preflight-only
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

## Resume recipe after compaction

1. Confirm there is no existing Starsector process and use only a Preflight launch. The current
   save should be observed untouched for three seconds; the controller should report
   `campaign pause state already matched request` before the paused window.
2. Run `campaign-sample-paused-unpaused.json` for broad sampled attribution or the call-time probe
   scenario only when branch/call counts are required. Do not repeat an A run unless it can change
   the next decision.
3. Rank active recurring stutter with:
   `python3 scripts/starsector_gameplay_hotspots.py RUN/startup.jfr --scenario-evidence RUN/smoke-evidence.json --frame-report RUN/runtime-frame-report.json --frame-series campaignUnpausedAfter30SecondsActive --repeated-clusters 10`.
   Add `--allocations` for weighted allocation samples and `--contains TEXT` for a named stack.
4. Keep observations and hypotheses separate below. A previous optimization narrows a boundary; it
   does not make the boundary permanently uninteresting.
5. Commit bounded JSON/Markdown and hashes, then remove raw JFRs, logs, and rebuildable binaries.

## Confirmed observations

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
| Paused/unpaused attribution | Frame buckets are state-separated and focus-clean. The recorder retains exact repeated-cluster windows and the JFR analyzer maps and aggregates them through the calibrated Rosetta clock. | Attribution now shows broad engine/location/fleet/economy bursts but not the cadence or causal sub-call. | Do clusters align with a stable campaign-time or accumulated-delta boundary, and which sub-call owns excess cluster time? |
| Combat | Deterministic simulation, autopilot, speed-up, zoom, and frame reporting already exist. | Campaign work says nothing about 1,000+ DP battles or combat listener/collision scaling. | Which inclusive stacks dominate a symmetric high-DP clean combat window, and do benefits survive ordinary-DP confirmation? |

## Open questions, ranked

1. **Recurring active-campaign cadence:** repeated-cluster stacks are now visible and broad. Add a
   bounded cadence/catch-up probe to identify what schedules the location/fleet/economy bursts and
   rank excess cluster time rather than whole-window sample count.
2. **Commodity residual cost:** the empty/nonempty/delegated traffic split is now known. Map JIT
   samples or a faithful extracted benchmark to the remaining nonempty exact-key path, runtime
   enable gate, and caller boundary. Production must retain zero diagnostic writes.
3. **RAT tooltip idempotence:** determine whether tooltip identity plus codex-entry identity is a
   sufficient replay guard. Inspect both `WhichModScript` and `AICoreTooltipScript`; optimizing only
   the reflection cache may leave the per-frame copied UI traversal intact.
4. **Stellar Networks refresh epochs:** test whether a pass is necessary after a short unpaused
   interval and which listener events already express real market invalidation.
5. **Stable snapshot ownership:** instrument rebuilds by transformed loop kind before redesigning
   cursor retention.
6. **Combat scale:** retain the deterministic ordinary simulation, then add a separate symmetric
   1,000+ DP stress fixture. Do not replace the ordinary workload with the stress workload.

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

### Does a stack found inside a repeated cluster prove it caused the cluster?

No. It establishes temporal overlap at the JFR sampling resolution. Repeated appearance, inclusive
share, exact call-time probes, or an on/off intervention can strengthen causality. A single sampled
leaf inside one cluster remains a lead, not a conclusion.

### Can raw profiles be committed?

Do not commit JFRs, transformed classes, game/mod binaries, saves, or full logs. Commit bounded JSON,
hashes, methodology, and conclusions. Game screenshots are acceptable when they add useful visual
evidence and contain no sensitive material.
