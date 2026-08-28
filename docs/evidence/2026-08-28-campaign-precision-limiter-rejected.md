# Campaign precision limiter rejected at 60 Hz

Date: 2026-08-28

Install: Starsector 0.98a-RC8, current 83-mod profile, macOS on Apple M5, shipped x86-64
Java 17 game runtime under Rosetta, Preflight Recommended/Fast runtime path

Status: **not promoted after two exact installed-host candidate observations**. The candidate was
structurally healthy, but it did not add a meaningful player-visible smoothness improvement on top
of the already accepted experimental VSync-off path. Its source remains visible on
`codex/1157-installed-pacing` and in PR #1164; it is not part of the shipping quality-grind branch.

## Decision

The exact candidate replaces only the reviewed second `Thread.sleep` call in Starsector's
`BaseGameState` limiter with a deadline-based park plus bounded short spin. Activation requires both
the exact bytecode target and an explicit 60 FPS property. Missing identity, disabled activation,
unexpected state, or interruption retains/falls back to the original game path. The experiment used
the existing `Smooth frame pacing` VSync-off switch so it tested the incremental value of a more
precise game limiter, not the already demonstrated value of disabling the second presentation gate.

Both candidate runs completed the full semantic route, loaded the same profile, observed the save
already paused, retained that pause state, opened a clean 60-second frame window, captured evidence,
and stopped the one owned game process cleanly. The limiter reported requested, installed, and active
at 60 FPS with a 250-microsecond spin margin. Adapter health was ACTIVE with zero source-binding
rejections, declines, contained failures, cache-rejection signals, wrapper failures, or runtime
integrity failures. The exact-match count differed by one because one unrelated registered class did
not load in the second session; the limiter target itself installed and ran in both sessions.

| Run | Frames | Avg FPS | p50 ms | p95 ms | p99 ms | 1% low | Max ms | >33 ms | >50 ms | >100 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| B1 | 3,436 | 57.52 | 17.3 | 19.7 | 22.3 | 44.84 | 38.487 | 2 | 0 | 0 |
| B2 | 3,393 | 57.48 | 17.3 | 20.1 | 22.7 | 44.05 | 48.490 | 2 | 0 | 0 |

The retained 2026-08-26 VSync-off observations were 58.95/59.09 average FPS, 22.9/22.8 ms p99,
43.67/43.86 FPS 1% low, and four/one frames over 33.33 ms. Their arm medians versus the new candidate
medians are approximately average FPS **-2.6%**, p99 **-1.5%**, and 1% low **+1.6%**. The apparent
severe-frame-rate reduction comes from tiny counts across different window lengths and is not used
as a claim. These older controls used an earlier build/probe, so this is decision context rather than
an exact current interleaved A/B cohort. The movement is nevertheless far below the issue's roughly
10% p99/1%-low acceptance example, is mixed, and does not justify another launch merely to force a
positive result.

The causal chain therefore does not complete:

`deadline-based wait installed -> only ~1-2% historical tail movement + lower average -> no useful incremental win`

## What the limiter telemetry says

The candidate did not spend meaningful CPU spinning. B1/B2 averaged 2.60/2.28 microseconds of spin
per wait and never exceeded 236 microseconds. It also recorded zero interrupted waits. The more
important result is cadence: the candidate extended the game's integer-millisecond request by an
average 626/639 microseconds and then overshot its computed deadline by another 677/687 microseconds
on average. Maximum overshoots were 28.30 and 29.07 milliseconds. The measured limiter interval
therefore averaged 9.624/9.767 ms for game requests averaging 8.321/8.441 ms.

That policy can make the nominal cadence more exact relative to the prior completion timestamp while
also extending a limiter whose coarse request already absorbs scheduler delay. The resulting 17.3 ms
median frame and roughly 57.5 FPS average are consistent with double-counting part of that delay.
This is a mechanism-backed explanation for the non-win, not proof that every precision-limiter design
must fail. Any successor must change the deadline policy before asking for another same-host cohort;
the current implementation should not be retried unchanged.

## Presentation split and probe cost

Every settled frame had a complete presentation and limiter split. B1/B2 pre-swap p99 was 18.2 ms,
with 15.314/15.369 ms average pre-swap and 5.404/5.410 ms after subtracting the limiter. Native swap
averaged 1.779/1.743 ms with 6.3 ms p99; its inferred off-CPU portion averaged 1.497/1.458 ms with
6.0 ms p99. All four >33.33 ms frames were pre-swap dominated, and none was swap dominated. This
confirms that VSync-off remained active and that presentation wait did not hide a precision-limiter
win.

The ordinary display-boundary measurement hook averaged 24.663/24.761 microseconds. Limiter and
presentation timestamp reads are reported separately by the probe contract. JFR, the asynchronous GPU
timer, broad campaign timers, and GL command probes were disabled, so these are thin measurement runs
rather than intrusive discovery observations.

## Identity and scope

Both runs used Preflight only, JAR
`1c0ba20ec904da669f945ecbb2dde96753015a5a5a8bfbfef2eb963969602196`, profile fingerprint
`2995668308ac3d31d645ccac30fb1a7e644e64fce5609050a1488df4cadc5af6`, texture profile
`59b01dc050f39a9f07053bd168cc8c1ecd55086b429b2d732456f87ca217a702`, the same current Continue
save, 1440x932 windowed rendering, swap interval zero, the game's 60 FPS cap, and scenario
`campaign-precision-limiter-vsync-off`. No save action was issued and no save/load or serialization
class was changed.

Local `./mvnw -Pcoverage verify` completed successfully. The aggregate agent coverage policy still
reports 76.42% against a 78% gate on this carrier branch, while the precision candidate itself has
126 covered and 21 missed lines. Desktop verified preparation also remains blocked by the carrier's
stale `RunCommand.java` capability lock. Those are inherited integration debts, not evidence in favor
of the candidate, and they are another reason not to promote this source as-is.

The compact machine-readable metrics and artifact hashes are in
[`data/2026-08-28-campaign-precision-limiter-rejected.json`](data/2026-08-28-campaign-precision-limiter-rejected.json).
Raw logs and run directories are disposable and are not committed.

## Consequence for issue #449 and child #1157

The VSync-off experiment remains the large measured paused-campaign win. This pass rejects the current
60 Hz precision waiter as an incremental companion; it does not weaken the VSync-off result and does
not spend the broader frame-pacing area. A current interleaved A/B is warranted only after the deadline
semantics change materially or when testing a genuinely different 120/144 Hz display contract.

For the physical-machine program, the next higher-information installed-host slice is no longer this
unchanged waiter. Use the live 1,040-DP fixture to populate the combat-scaling lane's real coefficients,
or run one narrowly separated render-sync/GraphicsLib candidate from #1153. Preserve VSync-off and the
existing thin frame recorder; do not combine those candidates or revive the rejected GL call caches.
