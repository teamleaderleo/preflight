# Nexerelin economy-info recurring hitch attribution

Date: 2026-08-28

Issues: #1158, #449. Branch: `codex/1158-physical` at `781bea39` plus this report.

Status: **exact recurring owner established; narrow scoped-list candidate passed shadow equivalence;
thin performance cohort pending; no FPS claim**.

## Run identity and health

Run `issue-1158-nex-economy-r2-20260828-180835` used the installed Starsector 0.98a-RC8
build, the current 83-mod profile fingerprint
`2995668308ac3d31d645ccac30fb1a7e644e64fce5609050a1488df4cadc5af6`, Nexerelin 0.12.2b,
the Recommended Preflight plan, Java 21 running Java-17-targeted helper bytecode, a 1440x932
window, Apple M5 OpenGL `2.1 Metal - 90.5`, and final swap interval one. The focused route held the
initial campaign state untouched for three seconds, verified a 45-second settled paused window,
then used the mapped internal pause action and retained a 45-second settled unpaused window. It did
not issue a save command.

Every semantic route step passed. The exact Nexerelin adapter installed and the complete adapter
set reported 61 exact transformations, zero source-binding rejections, unavailable plans,
transformation declines, contained failures, cache rejection signals, wrapper failures, or runtime
integrity failures. The frame hook averaged 15.83 microseconds across 8,021 samples. Deep owner
timing makes this a discovery run, not a thin FPS comparison.

The game emitted a native `SIGSEGV` only after the scheduled capture while the controller was
stopping it. Preflight correctly retained `FATAL_LOG_EVIDENCE`; the scenario itself passed. This
run is valid for pre-crash phase/frame attribution but cannot enter a clean-exit optimization
cohort.

## Exact owner result

The exact `EconomyInfoHelper.collectEconomicData(boolean)` boundary ran four times: once on the
first-run path and three recurring refreshes during the route.

| phase | calls | total | maximum | share of total |
| --- | ---: | ---: | ---: | ---: |
| complete rebuild | 4 | 150.262 ms | 59.094 ms | 100.0% |
| commodity scan | 4 | 137.730 ms | 53.139 ms | 91.7% |
| producer pass | 156 | 12.175 ms | 2.316 ms | 8.1% |
| importer pass | 156 | 8.589 ms | 2.400 ms | 5.7% |
| demand pass | 4 | 0.191 ms | 0.070 ms | 0.1% |
| market summary | 4 | 11.689 ms | 5.310 ms | 7.8% |
| all other outer phases | — | 0.711 ms | 0.302 ms | 0.5% |

The visit census retained 384 commodity iterations and 27,612 candidates in each of the producer,
importer, and demand loops. The named inner passes account for only 20.956 ms of the 137.730 ms
commodity scan. About 116.8 ms therefore remains around the per-commodity market-share calculation
and its small loop bookkeeping.

The three unpaused refreshes were not harmless background work:

| exact rebuild | containing frame | share of frame |
| ---: | ---: | ---: |
| 33.021 ms | 95.248 ms | 34.7% |
| 29.287 ms | 95.248 ms | 30.8% |
| 28.860 ms | 58.965 ms | 48.9% |

The first two refreshes landed in the same 95.248 ms frame and together explain 62.307 ms, or
65.4%, of it. The third explains almost half of a separate 58.965 ms frame. The callback is an
`EconomyTickListener`, so this is a recurring campaign hitch family rather than startup-only work.

## Installed-bytecode explanation

For every eligible commodity, Nexerelin calls core
`CommodityMarketData.getMarketSharePercentPerFaction()`. The installed 0.98a-RC8 implementation:

1. calls `getMarkets()` to enumerate distinct factions;
2. calls `getMarketSharePercent(faction)` once per distinct faction;
3. each faction call invokes `getMarkets()` again and scans all markets;
4. every `getMarkets()` delegates to `ReachEconomy.getMarketsInGroup()`;
5. every group lookup allocates a new `ArrayList` and scans the complete economy market list.

Nexerelin then calls `data.getMarkets()` three more times for its producer, importer, and demand
passes. This creates a repeated full-economy scan/list-allocation shape under an exact method that
ran three times in 45 seconds. The direct phase result and the static call graph agree; neither is
being presented as a candidate performance result yet.

## Narrow successor contract

The highest-information candidate is a scoped identity-keyed snapshot of each
`CommodityMarketData.getMarkets()` result, active only while this exact Nexerelin rebuild is on the
current thread. It leaves the original market-share algorithm, map ordering, faction/player rules,
and every caller outside the reviewed scope unchanged. Its first pass must include:

- an exact Starsector core class SHA and exact Nexerelin class/JAR SHA gate;
- original behavior whenever either half is absent, disabled, or fails;
- an independent kill switch;
- shadow validation that fresh lists retain the same size, order, and element identities before a
  thin performance claim;
- counters for scopes, cache misses/stores, hits, shadow matches/mismatches, declines, and failures;
- a clean ordinary-campaign correctness pass followed by interleaved thin baseline/candidate
  windows with the same semantic route and identity.

Do not broaden this into a general economy cache. If the scoped snapshot removes scans but does not
reproducibly improve the refresh-containing frame tail, retain it as another useful rejection.

## Candidate and shadow-equivalence checkpoint

Commit `2278f512` implements the candidate as an opt-in exact adapter. It brackets only the installed
Nexerelin `EconomyInfoHelper.collectEconomicData(boolean)` invocation and lets the installed core
`CommodityMarketData.getMarkets()` wrapper reuse one identity-keyed list snapshot on the same
thread and inside that scope. Both exact transformed halves must install before the candidate can
activate. A property kill switch, original implementation, fail-open health latch, bounded
thread-local state, and scope cleanup in a `finally` path remain available.

Run `issue-1158-nex-market-list-shadow-r1-20260828-182645` exercised shadow mode against the same
installed game, profile, save, display, adapter, and semantic paused/unpaused route. The route
passed, both exact halves installed, all four scopes ended, and the wrapper compared every fresh
original result against its scoped snapshot without returning the snapshot:

| shadow counter | result |
| --- | ---: |
| scopes begun / ended | 4 / 4 |
| per-commodity snapshot stores | 156 |
| fresh original comparisons | 5,460 |
| size/order/element-identity matches | 5,460 |
| mismatches | 0 |
| failures | 0 |
| outside-scope declines | 0 |
| maximum entries in one scope | 39 |

Adapter health retained 62 exact matches and 62 applied transformations, with zero source-binding
rejections, unavailable plans, transformation declines, contained failures, cache rejections,
wrapper failures, or runtime-integrity failures. The first-run plus three recurring refreshes
retained the expected cardinalities: 384 commodity visits and 27,612 producer, importer, and demand
candidates apiece. That is evidence that the proposed snapshot is semantically equivalent for this
reviewed workload, not evidence that it improves frame pacing.

As in the discovery pass, the controller completed the semantic capture and then the game emitted a
native `SIGSEGV` during shutdown. The harness correctly retained `FATAL_LOG_EVIDENCE`. This does not
invalidate the completed shadow comparisons, but it keeps the run out of the performance cohort and
reinforces that the later thin baseline/candidate cohort must require clean exits.

The next launch-dependent gate is a repeated interleaved thin baseline/candidate cohort on the same
semantic route. It must compare refresh-containing frame tails, direct cache hits/scans avoided,
workload identity, adapter/fallback health, and lifecycle health. No further game launch was started
after this checkpoint while the physical host was reserved for Ubuntu setup.

Compact retained data is in
[`data/2026-08-28-nexerelin-economy-info-hitches.json`](data/2026-08-28-nexerelin-economy-info-hitches.json).
Raw logs, frame packets, and complete local run/session directories remain disposable evidence.
