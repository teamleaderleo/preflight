# Documentation map

Preflight's documentation includes a product manual, an engineering record, release writing, and a
large evidence archive. They answer different questions. This page is the shortest route to the
current answer.

## Start here

- [Live release convergence #652](https://github.com/teamleaderleo/preflight/issues/652): current
  beta blockers, merge order, collision control, and moving `main` coordination.
- [Release readiness](release-readiness.md): documentation mirror of the current candidate gate.
- [Public beta roadmap](beta-roadmap.md): current candidate execution sequence from source selection
  through native/package acceptance.
- [Product contract](product-contract.md): current product, compatibility, preset, storage, update,
  and diagnostics boundaries.
- [Project overview](../README.md): what Preflight does, current measured results, and development
  status.
- [Engineering overview](engineering-overview.md): the main performance and architecture story,
  including shared data reads, texture critical paths, storage/layout, Janino, campaign runtime,
  fallback boundaries, and desktop productization.
- [Publication decision #950](https://github.com/teamleaderleo/preflight/issues/950): the 2026-08-20
  maintainer decision that the Fractal Softworks request is courtesy correspondence and a reply is
  outside the publication gate.
- [Fractal permission request](fractal-permission-request.md): the retained 2026-08-07 courtesy
  correspondence and reply-handling record; use #950 for publication policy.
- [Packaged capability receipt](capability-receipt.md): machine-checked writes, child processes,
  native commands, fixed links, and network endpoints in an exact package.
- [Optimization history](optimization-history.md): source-linked development performance history,
  including the current ~101-second → 13.69-second arc and the controlled 89.00-second ordinary /
  15.53-second Preflight campaign in its historical context.
- [Startup benchmark](startup-benchmark.md): current measurement protocol and exact packaged-engine
  candidate mode.
- [Experiment ledger](experiment-ledger.md): retained optimization families, including rejected,
  corrected, diagnostic, and deferred work.
- [Performance and storage tradeoffs](performance-storage-tradeoffs.md): current preset preparation
  costs and storage behavior.
- [Downloads and installation](downloads.md): planned public artifacts and current candidate/publication
  boundary.
- [Public beta release writing kit](release-post-draft.md): release headline/deck copy, GitHub Release
  body source, package/support language, short descriptions, and candidate placeholders.
- [Preflight 0.1.0 draft release notes](releases/0.1.0.md): first-beta release-note source with the
  current feature inventory and candidate placeholders.
- [Leo's beta announcement draft](beta-announcement-leo-draft.md): shorter forum/Reddit post.
- [Long beta announcement draft](beta-announcement-draft.md): fuller public source copy.
- [Mod-author public writing draft](mod-author-post-draft.md): standalone pitch for `lint`, `scan`,
  and `analyze setup`.
- [Leo's talking points](leo-talking-points.md): public-writing crib sheet for numbers, features,
  trust/privacy answers, claim qualifiers, and current release work.
- [Public-writing sales inventory](public-writing-sales-inventory.md): overcomplete hook reservoir by
  audience; treat dated release-status language inside it as copy history unless refreshed against
  #652 and #950.
- [Known limitations](known-limitations.md): current platform, evidence, storage, and fallback limits.
- [Cross-platform evidence plan](cross-platform-evidence-plan.md): claim boundary for hosted,
  emulated, and native checks.
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
- [UI design guide](ui-design.md): current visual language, interaction hierarchy, CSS ownership,
  responsive expectations, accessibility, and rendered-review rules for the desktop app.
- [Desktop redesign brief](desktop-redesign-brief.md): historical redesign rationale and implementation
  record; use the UI design guide for current visual direction when the two disagree.
- [Verification strategy](verification-strategy.md)
- [Runtime adapter model](runtime-adapters.md)
- [Vanilla runtime adapter](vanilla-adapter.md)
- [Prior-art review: Starsector Rendering](prior-art-starsector-render.md)
- [What generalizes](what-generalizes.md)
- [Desktop application research](desktop-app-research.md)

The owner intentionally removed the repository rulesets/branch protection on 2026-08-21; retired
[#607](https://github.com/teamleaderleo/preflight/issues/607) records that decision rather than owning
an active release gate. [#720](https://github.com/teamleaderleo/preflight/issues/720) records the
completed `release-signing` Environment setup, private rehearsals, and signing-secret migration.
#652 remains the live release-sequencing authority.

## Engineering record

Start with the [engineering overview](engineering-overview.md) for the current high-level technical
story. The [roadmap](roadmap.md), [optimization north star](optimization-north-star.md), focused cache,
audio, texture, resource-index, JFR, bytecode documents, and the
[historical implementation handoff](next-llm-handoff.md) record how the current implementation was
reached. Some contain headings such as “current,” “next,” or “release program” that were accurate on
their recorded date. Read those headings as dated snapshots unless the document explicitly delegates
live state to #652.

The [evidence archive](evidence/) contains retained run reports and decision records. Historical
numbers and rejected approaches stay unchanged after later work supersedes them. When a newer
controlled result changes the status of an older benchmark, keep both and let
[Optimization history](optimization-history.md) explain the chronology.

Do not use durable documentation as a moving-SHA dashboard. Exact revision identities belong in
immutable evidence, PRs, commits, or the live coordination issue while work is in flight.

When documents disagree, use this precedence:

1. current code and automated tests;
2. #652 for live beta blockers, merge order, and moving release coordination;
3. the product contract, release-readiness mirror, and current user documentation;
4. current architecture documentation;
5. the dated engineering record; and
6. individual evidence reports, interpreted in their recorded context.

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

First person belongs wherever Leo is describing what he did, saw, or decided. **Made** and
**created** are usually better front-facing verbs than **built**. Do not invent a creator persona
around the work: avoid lines about projects getting out of hand, getting carried away, or becoming
larger than expected unless Leo actually wants that sentence.

Let an interesting fact stand. Do not automatically follow it with a joke, disclaimer, personality
tag, or explanation. Short feature copy can be blunt; a line such as **Tracked playtime!!!!!** does
not need a paragraph explaining why the exclamation marks are there.

Public copy is allowed to sell the work. Lead with the strongest current result, then use the few
features that matter to the venue. The general player-facing order is benchmark and speed, tracked
playtime, campaign movement, launch settings and battle size, setup checks, storage/recovery, signed
updates, and the linter. Saved launch profiles are useful but do not need to lead general product
copy.

Keep concrete behavior in place of abstract guarantees. For compatibility, for example: if a runtime
shortcut does not recognize the code it expects, it steps aside and the normal game path runs. Stop
there unless the venue actually needs the deeper boundary.

Benchmark conditions, compatibility limits, write boundaries, and release-candidate status must stay
exact even when the surrounding prose is informal. Never turn a development number into a universal
promise. Never describe an open PR as shipped.

Posts to the Starsector forum and subreddit are technical release posts. They can be excited and
personal without reading like a startup announcement. When the useful fact has landed, stop writing.
