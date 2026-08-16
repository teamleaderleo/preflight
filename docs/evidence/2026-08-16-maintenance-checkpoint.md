# 2026-08-16 maintenance checkpoint

This is a point-in-time snapshot after `main` merged #488 (`23e1b876ceb6c61e3691350711ed601b245c6aff`). It exists so the long chronological handoff does not need to be rewritten every time a short product-maintenance burst changes the immediate frontier.

## What just landed

The August 15–16 maintenance run moved Preflight further from a development harness and toward a launcher that maintains its own local state:

- #473 stopped writing routine per-seam evidence when a launch had nothing actionable to report.
- #475 made launch/benchmark evidence retention automatic while the desktop is ready and idle, keeping the newest 10 runs and 5 benchmarks.
- #478 bounded automatic safety backups and stale profile-activation reviews.
- #480 added per-file storage accounting inside evidence categories, retained both the beginning and end of bounded console capture, and introduced the durable launch ledger/playtime history.
- #481 made the product benchmark refuse concurrent desktop mutations.
- #485 serialized and contained launch-ledger reads/writes/backfill, made launch counting depend on a proven game start, and separated incomplete sessions from ignored attempts.
- #486 tied desktop minimize/stop behavior to the verified Starsector JVM and preserved playtime recording when the desktop exits.
- #487 reattaches to an exact live game after desktop restart, bounds stderr parsing, locks launch settings while the game is active, and automatically reclaims prepared data when the owned cache grows past 12 GiB while protecting current/named profiles.
- #488 exposes the durable lifetime playtime total on Home and backfills older run history without making discovery or launch depend on readable history.

## Backlog cleanup performed at this checkpoint

Closed as completed because their stated work has landed:

- #20 — vanilla runtime adapter contract / ecosystem fixtures
- #48 — evidence-driven startup-acceleration roadmap
- #75 — exact sound-loader/JOrbis equivalence gate
- #102 — evidence integrity and first repeated texture benchmark readiness
- #457 — errand-first desktop navigation
- #469 — automatic launch-report retention
- #479 — shipped-versus-scaffolding audit
- #483 — playtime research before exposing the feature

Closed as superseded:

- #294 — the old trust-surface umbrella; `docs/release-readiness.md` is the maintained release blocker list.

Updated but intentionally left open:

- #418 now describes the real remaining task: run and retain the paired startup benchmark from the exact packaged release candidate. The `--engine PATH`/`bundle.json` verification work is already complete.
- #477 now has a status note distinguishing #487's landed 12 GiB owned-cache policy from the still-open question of free-space-pressure eviction and opt-out semantics.
- #484 now has a status note narrowing playtime work to interrupted-session durability, corrections, local merge/import behavior, and test isolation. The visible total and short-session counting policy have already shipped.

## Current release direction

`docs/release-readiness.md` remains authoritative for publication. The high-value unknowns are external or candidate-specific now: publication/trademark guidance, the complete hosted candidate, the exact packaged startup pair, final packaged report lifecycle, and licensed Windows/Linux game evidence.

The open #482 is a correctness fix worth rescuing before much more product surface: cache health should use the same Starsector-build and audio-decoder identities as launch. Its existing branch is behind current `main` and currently conflicts.

For player-facing follow-up after the current desktop work settles, #391 and #463 still describe one coherent missing readout: after an ordinary launch, show whether acceleration actually activated and the ordinary last-launch startup time. Combined with the new lifetime playtime total, that would make Home explain what just happened without requiring a formal benchmark or diagnostics export.

## How to read the repository from here

1. Read `docs/release-readiness.md` for publication blockers.
2. Read this snapshot for the maintenance frontier after #488.
3. Read open issues for the exact remaining contracts.
4. Use `docs/next-llm-handoff.md` as the engineering chronology and evidence ledger; portions of its older priority language are intentionally historical.

Do not infer an unfinished feature merely because an older issue or handoff paragraph describes it. Check current `main`, merged PRs, and the issue's latest status first.
