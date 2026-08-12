# Documentation map

Preflight's documentation includes a product manual, an engineering record, and a large evidence
archive. They answer different questions. This page is the shortest route to the current answer.

## Start here

- [Project overview](../README.md) — what Preflight does, current measured results, and development
  status.
- [Release readiness](release-readiness.md) — the blocking work before public distribution.
- [Public beta roadmap](beta-roadmap.md) — the ordered product, benchmark, reliability, package,
  platform, evidence, and presentation program.
- [Fractal permission request](fractal-permission-request.md) — maintainer correspondence draft and
  reply-handling checklist.
- [Product contract](product-contract.md) — exact modification, compatibility, preset, storage,
  update, and diagnostics boundaries.
- [Optimization history](optimization-history.md) — the readable, source-linked account from the
  roughly 101-second observed worst case and 88.13-second five-run median through the
  15.88-second warm record.
- [Experiment ledger](experiment-ledger.md) — every retained optimization family, including rejected,
  corrected, diagnostic, and deferred branches.
- [Performance and storage tradeoffs](performance-storage-tradeoffs.md) — what Balanced, Fastest,
  prepared audio, redundancy, and safe pruning cost and buy.
- [Downloads and installation](downloads.md) — planned artifacts and current private build process.
- [Known limitations](known-limitations.md) — the current platform, evidence, storage, and fallback
  limits.
- [Cross-platform evidence plan](cross-platform-evidence-plan.md) — what hosted, emulated, and native
  checks can establish before a platform is accepted.
- [VMware Fusion acceptance](fusion-acceptance.md) — deterministic Windows package and Ubuntu ARM64
  portable checks that use no game content.
- [Versioning and updates](versioning-and-updates.md) — application releases, game/mod compatibility,
  profiles, cache evolution, and the signed update channel.

## Users and beta testers

- [Automatic launch and discovery](automatic-launch.md)
- [Preparation and storage](prepare.md)
- [Diagnostics export](diagnostics.md)
- [Support and private report handling](support.md)
- [Rollback and bad-release response](rollback.md)
- [Release dependency inventory](dependency-inventory.md)
- [Startup benchmark](startup-benchmark.md)
- [Asset lint](asset-lint.md)
- [Desktop smoke automation](desktop-smoke-automation.md)
- [Signed macOS update and rollback rehearsal](evidence/2026-08-08-signed-update-rollback-rehearsal.md)

## Maintainers and contributors

- [Architecture](architecture.md)
- [Java runtime support](java-runtime-support.md)
- [Refactoring audit](refactoring-audit.md)
- [Desktop redesign brief](desktop-redesign-brief.md)
- [Verification strategy](verification-strategy.md)
- [Runtime adapter model](runtime-adapters.md)
- [Vanilla runtime adapter](vanilla-adapter.md)
- [Prior-art review: Starsector Rendering](prior-art-starsector-render.md)
- [What generalizes](what-generalizes.md)
- [Desktop application research](desktop-app-research.md)

## Engineering record

The [roadmap](roadmap.md), [optimization north star](optimization-north-star.md), and focused cache,
audio, texture, resource-index, JFR, and bytecode documents record how the current design was
reached. Some open with measurements or priorities that were current at the time. Read them as a
chronological laboratory notebook unless they explicitly say they are the current product contract.

The [evidence archive](evidence/) contains immutable run reports and decision records. Historical
numbers and rejected approaches remain unchanged after later work supersedes them. The large
[engineering handoff](next-llm-handoff.md) is an operational ledger for continued work.

When documents disagree, use this precedence:

1. current code and automated tests;
2. the product contract and release-readiness checklist;
3. current architecture and user documentation;
4. the dated engineering record; and
5. individual evidence reports, interpreted in their recorded context.

## Writing voice

Public writing should sound natural and assured:

- use contractions in ordinary prose;
- lead with the artifact, outcome, or evidence;
- state conclusions directly and delete setup the reader can infer;
- use “Preflight” or a neutral construction for product behavior;
- reserve first person for direct correspondence;
- use “a” or “an” unless the exact count matters;
- avoid contrast scaffolding such as “not X, but Y” and repeated “X, while Y” sentences;
- mention a limitation where it changes the claim, then move on; and
- keep benchmark qualifiers, compatibility limits, and write boundaries precise.
