# Rendered UI acceptance

**Status:** durable operator procedure for meaningful desktop/frontend visual changes

This is the practical companion to [UI design guide](ui-design.md). The design guide says what Preflight should feel like; this page says how to prove a frontend change actually renders that way.

Source review, React/jsdom tests, and CSS assertions remain useful for semantics, state ownership, accessibility contracts, and authored cascade rules. They are not substitutes for a browser. If a change affects composition, density, responsive behavior, disclosure, control hierarchy, clipping, scrolling, or visual state transitions, finish the review in Chromium/Playwright against the exact frontend bytes being accepted.

## Bind the review to exact bytes

Immediately before capture, refresh the live issue/PR and record:

- current `main` SHA;
- PR head SHA;
- Desktop CI workflow run ID;
- verified frontend artifact name/ID and digest when available.

Prefer the verified `preflight-desktop-frontend-<run-id>` artifact from Desktop CI. If the PR head changes, discard screenshots from the old head as acceptance evidence unless the change is provably documentation-only and does not alter the built frontend bytes. When in doubt, rebuild and recapture.

Do not present a checkout build as if it were the verified PR artifact.

## Required window sizes

Treat both shipped sizes as first-class layouts:

- default Tauri window: **1040×700**;
- minimum window: **720×560**.

A screen that is merely unclipped at the minimum size is not automatically good. Review hierarchy, readable text, action reachability, scroll ownership, and whether the composition still feels intentional.

## Core scenario matrix

For Home/Hangar work, capture and inspect at least:

| Scenario | 1040×700 | 720×560 | What to inspect |
| --- | --- | --- | --- |
| Ready / settled | required | required | dominant hull, setup identity, one primary launch action, no dead space |
| Setup / no installation | required | required | next action and explanation remain obvious |
| Preparation / low disk | required | required | consequential alternatives remain reachable and do not collide |
| Damaged prepared data | required | required | repair state is clear without implying game/mod files are damaged |
| Failed-run recovery | required | required | recovery owns attention without awkward layout shift; companion Home field remains coherent |
| Running / options-open | as useful | required for overlap work | stop/options controls do not collide or hide content |
| Hangar | required | required | ship remains primary; motion/direction/reset read as one control family |

Add scenarios whenever the change touches a state not represented here. Do not inflate the matrix with nearly identical screenshots that would not change the review decision.

## Interaction pass

Screenshots are necessary but insufficient. Exercise the interactions whose information appears only after input:

- keyboard-tab through actionable controls in meaningful order;
- focus and hover installation-path disclosure;
- open/close Options and any relevant details disclosure;
- drag the Home and Hangar ship, reverse its direction, and use Reset with keyboard and pointer;
- scroll any minimum-window state that genuinely requires scrolling and verify the important control at the bottom is actually reachable;
- check light and dark themes when a change touches contrast, overlays, shadows, or semantic colour.

A focus tooltip that exists in the DOM but paints behind a button is a browser failure, not a passing accessibility test.

## Record geometry when it matters

Alongside screenshots, lightweight Playwright diagnostics can make regressions easier to identify:

- viewport/client width and height;
- `scrollWidth` / `scrollHeight` for the page workspace;
- explicit Home composition class/state;
- bounding boxes for controls implicated in clipping or overlap;
- browser console/page errors.

Use measurements to describe what happened, not to replace looking at the screen.

## Candidate images vs canonical documentation

Keep two image classes separate:

1. **Review evidence** — SHA-qualified images tied to an exact candidate, e.g. `docs/images/review/976/<head-sha>/...` or an equivalent issue/PR attachment.
2. **Canonical product screenshots** — stable README/walkthrough images such as `docs/images/desktop-home-light.png`.

Publish review evidence while the candidate is still being judged. Refresh canonical README/walkthrough screenshots only after the visual candidate is accepted. A rejected experiment should never silently become the repository's public representation of Preflight.

Useful compact evidence is usually three contact sheets rather than a wall of files:

- default-window scenario matrix;
- minimum-window scenario matrix;
- themes + interaction states.

Keep individual full-resolution captures when a reviewer needs to inspect detail.

## What counts as a failure

Treat the render as failed when any of these are true even if tests are green:

- a new banner/card causes an unintended layout jump;
- a primary action moves or disappears when a secondary state appears;
- technical metadata becomes debug-sized to make the layout fit;
- a tooltip/disclosure is occluded or cannot be reached by keyboard;
- content is technically scrollable but the scroll ownership feels accidental or hides the important control on entry;
- DOM/keyboard order and visual priority disagree;
- a state looks like stacked generic panels instead of the same Preflight workspace changing jobs;
- default or minimum size looks merely survivable rather than deliberately composed.

If the browser pass exposes a concrete defect, repair that observed defect, rebuild the exact frontend artifact, and repeat the affected scenarios. Do not carry screenshots from the pre-fix head forward as acceptance evidence.

## Keep the flow small and repeatable

The goal is not screenshot bureaucracy. The goal is to make the browser answer questions that source and jsdom cannot answer.

Run the manual **Desktop rendered layout** workflow when Home or Hangar composition changes. It
checks every width from 720 to 1440 pixels, exercises full, compact, and minimal Home, verifies that
refocus doesn't replace or move controls, and uploads the rendered matrix. Run the same pass locally
from `preflight-desktop/` with `npm run ui:matrix`. It writes the individual screenshots, exact
geometry, a browsable contact sheet, and `overview.png` under `.ui-matrix/`. The first local run
creates an isolated Python/Chromium environment under `node_modules/.preflight-ui-layout`; later
runs reuse it, `npm ci` can replace it, and the repository worktree pruner treats it as generated
binary output.

A good rendered review leaves behind enough durable evidence that the next person can tell:

- exactly what bytes were reviewed;
- which scenarios and window sizes were inspected;
- which interactive states were exercised;
- what defect, if any, the browser exposed;
- whether the candidate passed both implementation quality and visual quality.
