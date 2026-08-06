# Documentation map

Preflight's documentation includes a product manual, an engineering record, and a large evidence
archive. They answer different questions. This page is the shortest route to the current answer.

## Start here

- [Project overview](../README.md) — what Preflight does, current measured results, and development
  status.
- [Release readiness](release-readiness.md) — the blocking work before public distribution.
- [Fractal permission request](fractal-permission-request.md) — maintainer correspondence draft and
  reply-handling checklist.
- [Product contract](product-contract.md) — exact modification, compatibility, preset, storage,
  update, and diagnostics boundaries.
- [Optimization history](optimization-history.md) — the readable, source-linked account from the
  original 88-second launch through the 15.88-second warm record.
- [Experiment ledger](experiment-ledger.md) — every retained optimization family, including rejected,
  corrected, diagnostic, and deferred branches.
- [Performance and storage tradeoffs](performance-storage-tradeoffs.md) — what Balanced, Fastest,
  prepared audio, redundancy, and safe pruning cost and buy.
- [Downloads and installation](downloads.md) — planned artifacts and current private build process.

## Users and beta testers

- [Automatic launch and discovery](automatic-launch.md)
- [Preparation and storage](prepare.md)
- [Diagnostics export](diagnostics.md)
- [Startup benchmark](startup-benchmark.md)
- [Asset lint](asset-lint.md)
- [Desktop smoke automation](desktop-smoke-automation.md)

## Maintainers and contributors

- [Architecture](architecture.md)
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
numbers and rejected approaches are intentionally not rewritten when later work supersedes them.
The large [engineering handoff](next-llm-handoff.md) is an operational ledger for continued work,
not release copy.

When documents disagree, use this precedence:

1. current code and automated tests;
2. the product contract and release-readiness checklist;
3. current architecture and user documentation;
4. the dated engineering record; and
5. individual evidence reports, interpreted in their recorded context.

## Writing voice

Public writing should sound like a person, not an institution:

- use natural contractions such as “doesn't,” “isn't,” and “can't”;
- lead with the artifact, outcome, and evidence instead of a builder or origin story;
- use “Preflight” or a neutral construction for product behavior instead of “I” or an ambiguous
  “we”;
- reserve first person for direct correspondence where a real person is asking for something;
- prefer “a” or “an” over “one” unless the exact count matters; and
- keep benchmark qualifiers, compatibility limits, and write boundaries precise even when the prose
  is informal.
