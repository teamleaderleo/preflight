# UI design guide

**Status:** living guidance for the desktop app

## TL;DR

Preflight should feel like a technical instrument made by somebody who actually plays Starsector.

```text
current player task first
        ↓
clear hierarchy
        ↓
real data when useful
        ↓
Preflight's drafting-paper / wireframe identity
        ↓
render it at both supported window sizes before calling it done
```

A few rules catch most mistakes:

- Don't turn every capability into an equal card/button.
- Don't drift into generic SaaS/launcher-store styling because it's familiar.
- Keep primary actions visibly labelled and easy to reach.
- Use the existing semantic colours/type roles.
- Make the screen work at **1040×700** and **720×560**.
- Put player language first. Implementation evidence can sit behind disclosure.
- Visual work needs rendered review; source/tests alone can't tell you whether the composition looks bad.

When this guide disagrees with the older [desktop redesign brief](desktop-redesign-brief.md), this guide is current and the brief is history.

## Visual premise

Preflight is precise without being sterile, a little idiosyncratic, and comfortable showing real technical data without turning the app into a dashboard.

The current visual language uses:

- warm drafting paper / fine grids / construction lines;
- dark ink;
- restrained gold for emphasis/movement;
- rust for warning;
- real green for success;
- a wireframe ship that can carry more visual weight than another rectangle.

Blueprint, Hangar, Ultraviolet, Airglow, Phosphor, and light/dark themes can change the accent while keeping the underlying Preflight identity.

Don't imitate Starsector's UI/assets. Preflight should feel at home beside the game without pretending to be part of it.

Also avoid generic defaults such as a grid of equal cards, giant marketing heroes, glassy pill controls, gradient-filled everything, or an icon for every noun.

Generated frontend work often converges on those patterns because they're easy. Compare proposed screens with the rendered app before accepting “clean” genericity as an improvement.

## Hierarchy before decoration

First ask: **what is the player here to do right now?**

Make that obvious with placement, scale, spacing, and ordering before adding borders, badges, explanatory copy, or another card.

Home is the clearest example. The ship can be the visual anchor; Launch/recovery still wins interaction priority. A failure state can recompose around recovery instead of preserving the settled layout at all costs.

Supporting tools can stay discoverable without competing with the current job.

If a screen feels crowded, first consider moving, collapsing, demoting, or removing something. Shrinking everything into microtype is usually the wrong repair.

## Typography

The type roles are intentional:

- **IBM Plex Sans Variable:** ordinary human voice, forms, body copy, buttons, most headings.
- **B612 Mono:** operational/data language, paths, measurements, metadata, compact technical labels.
- **Orbitron Variable:** display punctuation, identity, major numeric moments. It isn't body copy.

Body copy usually lives around 16 px. Supporting copy around 13–14 px is comfortable. Essential information shouldn't become 8 px text just to preserve a composition.

Dense information should get better grouping/alignment/disclosure before it gets smaller.

## Colour

Use the existing semantic custom properties such as `--ink`, `--paper`, `--accent`, `--warning`, `--success`, and the launch/console tokens.

Raw colour literals belong mainly in palette/token definitions or genuinely special illustration work. A new feature should normally inherit the active palette.

Accent, warning, and success need to remain perceptually distinct. “Selected,” “good,” and “needs attention” shouldn't collapse into one colour event.

Dark mode is a real design state. Treat it that way during implementation/review.

## Controls and surfaces

- Interactive targets keep a **44 px minimum**.
- Focus stays visible.
- Hover can explain; it can't be the only way to discover a consequential action/value.
- Primary/consequential actions keep text labels.
- Icon-only controls are fine for small secondary/cosmetic utilities when the symbol is clear and the accessible name/hover text is useful.
- Danger styling is for genuinely destructive/forceful actions.
- Disabled state shouldn't replace an explanation of where the active work moved.

Cards are a grouping tool, not the default unit of thought. Don't wrap every paragraph/setting/action in another bordered rectangle.

Radii stay modest. Pills should mean something rather than serving as a reflexive “designed” label container.

## Layout and responsive behavior

Both desktop sizes are first-class:

- default: **1040×700**
- minimum: **720×560**

Responsive work should recompose before it merely scales down. At the minimum size, preserve readable labels, reachable actions, useful identity, and recovery.

Home should avoid unnecessary scrolling at the standard size. Longer workspaces can scroll inside their content area when the task genuinely needs it.

Use explicit component state/modifier classes or data attributes for major states such as preparation/recovery/active. `:has()` is useful locally; it shouldn't quietly become the page-level state machine.

DOM/reading order and visual order should normally agree. Avoid CSS reversal that makes keyboard/assistive navigation encounter a different order from the screen.

## Motion

Motion should acknowledge change and then get out of the way.

Decorative ship motion is allowed because it belongs to the visual identity, but it can't carry essential meaning and it must respect reduced-motion preferences.

If the important thing only becomes discoverable after it moves, fix the still frame first.

## Copy inside the app

This is the same first-layer rule now used by the docs:

**say the result, state, or action in ordinary player language first.**

Implementation detail/evidence can sit behind disclosure when useful.

Don't repeatedly tell the player that something is safe, bounded, exact, reviewed, or authoritative when they only need to know what will happen.

Confirmations should change a decision by explaining something concrete, for example:

- what will be written/deleted;
- where something will be sent;
- what gets backed up;
- what can't be undone.

A confirmation that merely repeats the button with extra anxiety is clutter.

Use contractions in ordinary UI prose. Labels/recovery actions should scan quickly; explanatory text can breathe when the player is actually reading.

## CSS ownership

Preflight intentionally uses authored CSS. Don't add Tailwind, CSS-in-JS, a component-theme framework, or another styling language as a local convenience. A framework migration would be a project-level decision.

`styles.css` contains the foundation plus accumulated component styling. `release-readiness.css`, `game-settings-layout.css`, and `homePresentation.css` came from later phases. Their current load order can affect the result, but “loads last” isn't a design system.

For new work:

- keep palette/theme primitives and durable shared controls in the common foundation;
- keep component-specific layout with the component or a clearly named feature stylesheet;
- use semantic classes/modifiers instead of utility-class soup;
- avoid static inline styles when a class can own the rule;
- don't add a new late override layer just because understanding the cascade is inconvenient;
- if a rule only works because its file loads last, treat that as a maintenance smell and add resolved-cascade coverage when the ordering is intentional.

There doesn't need to be a giant token rewrite before beta. Promote repeated spacing/radius/type/elevation values into tokens when that removes real inconsistency, not as churn for its own sake.

## Rendered review is part of UI implementation

Source review/jsdom tests can establish semantics, state ownership, accessibility contracts, and some resolved CSS.

They can't tell you that the ship is sitting too low, a recovery panel feels detached, text is visually microscopic, or controls collide in a real WebView.

For meaningful visual changes, review the browser frontend in Chromium/Playwright with the deterministic preview scenarios, including ordinary and failure/recovery states, at both supported sizes. Check keyboard focus and hover interactively too.

If a local checkout isn't available, the verified frontend artifact from Desktop CI is a valid rendering source.

Before accepting a screen, ask:

1. Is the player's current job obvious before reading everything?
2. Does DOM order match the visual hierarchy?
3. Are important controls readable/reachable at 1040×700 and 720×560?
4. Did another card/badge/tooltip/icon/explanation actually help?
5. Does it still look like Preflight after the novelty wears off?

## Change the guide when the product changes

This records current taste and implementation lessons, not a ban on better ideas.

If a future screen genuinely needs a different composition, colour relationship, control pattern, or type treatment, make the better interface and update this guide so the departure becomes an understood decision instead of unexplained drift.
