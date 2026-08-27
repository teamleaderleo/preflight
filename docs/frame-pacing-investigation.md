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

The current optimized checkpoint is commit `86d6377a1988c56d985ae970e2deb5b876826a03`.
One Preflight-only `campaign-sample-paused-unpaused` run passed every semantic step on 2026-08-27,
used one owned game process, and dropped zero inactive or invalid frame intervals. Its bounded record
is [current optimized sampled profile](evidence/data/2026-08-27-current-optimized-sampled-profile.json).
The raw JFR and launch directory are disposable and are identified by hashes in that record.

Actual state-separated settled distributions were:

| State | Frames | Average | 1% low | 0.1% low | p99 | Repeated slow frames | Stutter burden |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Paused | 3,441 | 55.75 FPS | 40.16 FPS | 22.17 FPS | 24.9 ms | 0.00% | 2.22 ms/s |
| Unpaused | 1,642 | 49.75 FPS | 14.29 FPS | 5.18 FPS | 70.0 ms | 3.71% | 68.32 ms/s |

The active-campaign tail remains the primary campaign problem. Paused performance is substantially
cleaner, but isolated paused hitches still exist. Combat remains an equal product goal; this run did
not exercise combat.

## Confirmed observations

### Commodity event-mod validation remains a large CPU category

The 45-second `unpaused-settled` wall window contained 471 campaign main-thread execution samples.
`CommodityOnMarket.reapplyEventMod` was the leaf in 86 (18.26%); 84 of those stacks were below
`Economy.advance`. The installed exact-zero memo was enabled, production telemetry was disabled,
and `fastValidationUnavailable` remained zero.

This does **not** prove an 18.26% universal share. The same route has previously measured materially
lower shares, and statistical samples vary with campaign work. It does prove that the already
optimized wrapper can again become a coherent top leaf and is not a permanently spent area.

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
| Commodity event mods | Exact SHA-gated memo, mutation-aware slow fingerprint, zero-result fast path, and production counter-call elision are accepted. | The wrapper returned as the largest current CPU leaf. | Are most exact-zero `available.flatMods` maps empty, allowing an exact `isEmpty()` return before keyed lookup, and which instruction dominates after JIT compilation? |
| Stellar Networks refresh | One shuffled pass per paused interval replaces endless random refresh and then becomes idle. | A second pause starts another expensive 186-market burst, which can overlap a nominally unpaused route. | Can invalidation or refresh cadence be bounded by actual campaign-time advancement without making opened market data stale? |
| RAT tooltip scripts | No Preflight optimization exists; source shows per-frame UI copies and reflection, with a "do not modify twice" UI sentinel in the AI-core path. | The worst exact active frame crossed this code. | Does an identity/content guard remove repeated work without missing a tooltip object reused for a different entry? |
| Stable campaign snapshots | Stable arrays and exhausted cursor reuse are accepted and bounded. | Current allocation samples still show rebuild/cursor cost and cursor identities outnumber owners. | Which owners rebuild, how often, and can stale cursor entries be removed when an owner receives a replacement array? |
| Paused/unpaused attribution | Frame buckets are state-separated and focus-clean; JFR step windows are calibrated. | A wall step can contain an auto-pause, obscuring CPU attribution. | Can the profiler map execution samples to exact pause-state spans or repeated slow-frame clusters instead of only coarse steps and one worst frame? |
| Combat | Deterministic simulation, autopilot, speed-up, zoom, and frame reporting already exist. | Campaign work says nothing about 1,000+ DP battles or combat listener/collision scaling. | Which inclusive stacks dominate a symmetric high-DP clean combat window, and do benefits survive ordinary-DP confirmation? |

## Open questions, ranked

1. **Recurring active-campaign clusters:** identify stacks inside repeated >33.33 ms clusters, not
   just the single worst frame. This best matches perceived jitter.
2. **Commodity zero-path cost:** add opt-in-only diagnostics or a faithful extracted benchmark that
   distinguishes empty-map, nonempty-without-`eMod`, and slow-fingerprint traffic. Production must
   retain zero diagnostic writes.
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

### Can raw profiles be committed?

Do not commit JFRs, transformed classes, game/mod binaries, saves, or full logs. Commit bounded JSON,
hashes, methodology, and conclusions. Game screenshots are acceptable when they add useful visual
evidence and contain no sensitive material.
