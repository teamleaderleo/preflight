# Documentation map

Preflight's documentation includes a product manual, an engineering record, a release-writing set,
and a large evidence archive. They answer different questions. This page is the shortest route to
the current answer.

## Start here

- [Project overview](../README.md): what Preflight does, current measured results, and development
  status.
- [Release readiness](release-readiness.md): the blocking work before public distribution.
- [Public beta roadmap](beta-roadmap.md): the ordered product, benchmark, reliability, package,
  platform, evidence, and presentation program.
- [Fractal permission request](fractal-permission-request.md): sent maintainer correspondence and
  reply-handling checklist.
- [Product contract](product-contract.md): exact modification, compatibility, preset, storage,
  update, and diagnostics boundaries.
- [Packaged capability receipt](capability-receipt.md): the machine-checked writes, child
  processes, native commands, fixed links, and network endpoints in an exact package.
- [Optimization history](optimization-history.md): the readable, source-linked account from the
  roughly 101-second observed worst case through the 15.25-second recorded low, with the latest
  same-profile medians at 89.00 seconds ordinary and 15.53 seconds with Preflight.
- [Experiment ledger](experiment-ledger.md): every retained optimization family, including rejected,
  corrected, diagnostic, and deferred branches.
- [Performance and storage tradeoffs](performance-storage-tradeoffs.md): what Balanced, Fastest,
  prepared audio, redundancy, and safe pruning cost and buy.
- [Downloads and installation](downloads.md): planned artifacts and current private package process.
- [Public beta release writing kit](release-post-draft.md): release headline/deck copy, GitHub Release
  body source, package/support language, short descriptions, and placeholders that must be replaced
  with exact candidate facts.
- [Preflight 0.1.0 draft release notes](releases/0.1.0.md): first-beta release-note source with the
  current feature inventory and explicit candidate placeholders.
- [Leo's beta announcement draft](beta-announcement-leo-draft.md): the shorter forum/Reddit post with
  the result first, the broader product immediately afterward, and instructions at the bottom.
- [Long beta announcement draft](beta-announcement-draft.md): full public source copy covering the
  performance investigation, desktop features, linter, privacy/support, updates, and beta limits.
- [Leo's talking points](leo-talking-points.md): the public-writing crib sheet for numbers, product
  features, story angles, trust/privacy answers, claim qualifiers, and current release work.
- [Public-writing sales inventory](public-writing-sales-inventory.md): intentionally overcomplete
  hook reservoir by audience, including player candy, power-user features, trust/release flexes,
  harder wording examples, and publication boundaries.
- [Patreon page draft](patreon-page-draft.md): creator-page About, four membership tiers, welcome
  note, first current post, follow-up post queue, and image notes.
- [GitHub Sponsors page draft](github-sponsors-page-draft.md): short bio, introduction, goal,
  featured-work choices, monthly tiers, one-time support, and the activation checklist.
- [Known limitations](known-limitations.md): the current platform, evidence, storage, and fallback
  limits.
- [Cross-platform evidence plan](cross-platform-evidence-plan.md): what hosted, emulated, and native
  checks can establish before a platform is accepted.
- [VMware Fusion acceptance](fusion-acceptance.md): deterministic Windows package and Ubuntu ARM64
  portable checks that use no game content.
- [Versioning and updates](versioning-and-updates.md): application releases, game/mod compatibility,
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

Public writing should say things directly and retain the path of the thought. Deliberate repetition
is not a defect. Neither is an ordinary word beside a more specific or esoteric one. Do not sand a
sentence down merely because a shorter version is available.

Use contractions. Keep grammar precise. Hedge when the evidence actually calls for it. Humor should
come from the observation itself rather than a decorative adjective. Avoid generic setup, packaged
personality, contrast scaffolding, and lists created only to make prose look organized. Never use an
em dash unless Leo explicitly asks for one.

Do not use **build** or **building** as promotional language. It reads like generic founder/dev
posturing. Use the verb that actually describes the work: make, write, develop, investigate,
measure, maintain, prepare, test, release, or work on. Technical documentation can still use
"build" when it literally means a compiler/package/workflow build and another word would make the
instruction less precise.

First person belongs wherever Leo is describing what he did, saw, or decided. Creator pages can be
personal and can use lines such as "I make stuff that tends to become much larger than I expect" or
"I make Preflight." A technical release post should still lead with Preflight or the measured result
because that is what the Starsector reader came for.

Public copy is allowed to sell the work. Do not confuse restraint with underselling. Lead with the
strongest exact result, then reveal the breadth of the product: benchmark, playtime, profiles,
settings, storage/recovery, support/privacy, signed updates, and the linter. The failed experiments
and corrected measurements are also valuable public material because they explain why the current
claims deserve confidence.

Benchmark conditions, compatibility limits, write boundaries, and release-candidate status must stay
exact even when the surrounding prose is informal. Never turn a development number into a universal
promise. Never describe an open PR as shipped.

Posts to the Starsector forum and subreddit are technical release posts. They can be enthusiastic,
funny, and personal without reading like a startup announcement. Any clickbait-adjacent joke has to
be unmistakably ironic.
