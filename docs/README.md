# Documentation

There are a lot of documents in this repository because Preflight keeps experiments, evidence, release work, and implementation history. You shouldn't have to read all of them.

## TL;DR

Pick the thing you actually want:

- **What is Preflight?** Start at the [project README](../README.md).
- **Explain it like my brain has 256 KB of RAM.** Read [Preflight in 256 KB](how-preflight-works-256kb.md).
- **Walk me through how it works.** Read [How Preflight works](how-preflight-works.md).
- **I want to understand the codebase.** Read [Codebase tour](codebase-tour.md).
- **Give me the engineering story.** Read [Engineering overview](engineering-overview.md).
- **I need the exact current product rules.** Read [Product contract](product-contract.md).

That's enough for most people.

## If you're using Preflight

- [Getting started](getting-started.md)
- [Preparation and storage](prepare.md)
- [Known limitations](known-limitations.md)
- [Diagnostics and support](diagnostics.md)

## If you're changing Preflight

- [Codebase tour](codebase-tour.md)
- [Architecture](architecture.md)
- [UI design guide](ui-design.md)
- [Verification strategy](verification-strategy.md)
- [CI policy](ci-philosophy.md)

Start with the codebase tour if the repository is still unfamiliar. It follows user-visible actions through React, Rust, the Java CLI/core, and the injected agent instead of expecting you to learn the project by reading directories alphabetically.

## If you're checking performance or evidence

- [Engineering overview](engineering-overview.md)
- [Findings and outreach](findings-and-outreach.md)
- [Optimization history](optimization-history.md)
- [Performance and storage tradeoffs](performance-storage-tradeoffs.md)
- [Evidence archive](evidence/)

Selected current startup values live in [project facts](project-facts.json), with the audited rendered claim in [claim headlines](claim-headlines.md). Historical campaigns stay in the history/evidence docs for the questions they measured.

Use **Findings and outreach** when the question is whether a discovery should become a mod-author/base-game report at all. Its default answer is to keep the finding in Preflight unless external outreach clearly improves the outcome.

## If you're working on the release

- [Release readiness](release-readiness.md) is the current checklist/mirror.
- [Live release convergence #652](https://github.com/teamleaderleo/preflight/issues/652) owns moving coordination.
- [Downloads and installation](downloads.md) describes the public artifact boundary.

Release drafts, announcement drafts, candidate rehearsals, and packaging evidence exist because release work needs them. They aren't general project navigation.

## What about all the other files?

Most of them are specialist or historical reference. They include focused performance investigations, rejected experiments, prior-art notes, release-copy drafts, old plans, compatibility research, and dated implementation handoffs.

They can stay useful without being required reading.

A few rules make that less confusing:

1. **Current code and tests win.**
2. **Current product docs beat dated engineering notes.**
3. **Evidence says what happened in that recorded experiment.** It doesn't automatically describe the current product.
4. **Files such as `next-llm-handoff.md` are historical implementation records.** They aren't a current task list or a front door for humans.
5. **Search by topic when you need specialist detail.** The docs index doesn't need a link to every retained file.

## Writing and navigation style

Human-facing docs should be easy to enter even when the underlying subject is complicated.

- Put a short **TL;DR** near the top of a long page. Usually a few sentences or bullets are enough.
- Default to contractions in ordinary prose: **it's, isn't, doesn't, can't, won't, we're**. Expand them only when the sentence genuinely reads better that way.
- Lead with what happens and why somebody should care. Put proof, implementation details, and edge cases afterward.
- Keep front-door link lists short. Link to a hub or deeper document instead of spraying every related file across the page.
- Exhaustive reference material can stay exhaustive. It just shouldn't be the first layer.
- Prefer concrete behavior over abstract guarantees. If a runtime shortcut doesn't recognize its target, it steps aside and the normal game path runs.
- Keep benchmark conditions, compatibility limits, write boundaries, and release status exact even when the prose is casual.
- Use contractions and ordinary words. Keep grammar precise. Let humor come from the observation itself.
- Don't use an em dash unless the maintainer explicitly asks for one.
- Don't use **build/building** as promotional language. Use the verb that actually describes the work; technical compiler/package builds can still be called builds.

When the useful fact has landed, stop writing.