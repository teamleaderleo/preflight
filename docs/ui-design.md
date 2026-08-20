# UI design guide

**Status:** living design guidance for the desktop app

This is the current visual and interaction guide for Preflight. It is deliberately smaller than a component-library specification and more durable than a redesign ticket. When this guide disagrees with the older [desktop redesign brief](desktop-redesign-brief.md), use this guide for current design direction and treat the brief as design history.

The point is not to freeze the interface. It is to keep future work recognizably Preflight while leaving room for the app to get stranger, clearer, quieter, or more useful when the product warrants it.

## The premise

Preflight should feel like an independent technical instrument made by somebody who actually plays Starsector: precise without being sterile, a little idiosyncratic, and comfortable showing real data without turning the whole application into a dashboard.

The current visual language is warm drafting paper, fine grids and construction lines, dark ink, restrained gold for emphasis and movement, rust for warning, real green for success, and a wireframe ship that can carry more visual weight than another rectangular panel. The light/dark themes and Blueprint, Hangar, Ultraviolet, Airglow, and Phosphor palettes can change the accent without changing that underlying language.

Do not imitate Starsector's own UI or assets. Preflight should look at home beside the game without pretending to be part of it. Also do not drift toward generic SaaS, launcher-store, or generated-dashboard conventions merely because they are familiar. A grid of equal cards, a large marketing hero, glassy pill controls, gradient-filled everything, and an icon for every noun are not neutral defaults here.

Generated frontend work in particular tends to converge on tidy generic patterns. Compare a proposed screen with the rendered app before accepting that convergence as “clean.” The existing oddities are often doing useful identity work.

## Hierarchy before decoration

The first question on a screen is what the player is here to do. Make that obvious with placement, scale, space, and ordering before adding borders, badges, explanatory copy, or another card.

Home is the clearest example. The ship is allowed to be the visual anchor because it makes the launcher feel like a Starsector companion instead of a settings form. The launch or recovery action still wins interaction priority. If a failed run becomes the current job, the screen may recompose around recovery rather than preserving the settled layout out of loyalty to old coordinates.

Do not give every capability equal visual weight. Supporting tools can be discoverable without competing with the current task. Prefer one dominant action and a few subordinate routes over a field of equally prominent buttons.

Use whitespace as part of the hierarchy. If the interface feels crowded, first ask whether something can move, collapse, become secondary, or leave the current screen. Making every label smaller is usually the wrong repair.

## Typography has jobs

The current type roles are intentional:

- **IBM Plex Sans Variable** is the ordinary human voice: body copy, explanations, forms, buttons, and most headings.
- **B612 Mono** is operational/data language: compact measurements, paths, metadata, small technical labels, and values that benefit from a stable rhythm.
- **Orbitron Variable** is display punctuation, not body typography. Keep it to the Preflight identity, major numeric/display moments, and occasional short labels where the stylization earns its keep.

Body copy should normally live around 16 px. Supporting copy at 13–14 px is comfortable. Compact data labels can be smaller when they are genuinely secondary, but essential information should not be reduced to debug microtype merely to keep a composition intact. If a value needs to be read to make a decision, rearrange the layout before shrinking it into 8 px text.

Dense evidence should become better organized before it becomes smaller. Aligned rows, grouping, disclosure, and good labels beat microscopic prose.

## Colour is semantic before it is decorative

Prefer the existing semantic custom properties (`--ink`, `--paper`, `--accent`, `--warning`, `--success`, the console/launch tokens, and related theme variables) over new component-local colour literals.

Raw colour values belong primarily where palettes or visual tokens are defined, or in genuinely special illustration work. A new feature should normally inherit the active theme/palette rather than invent its own miniature brand.

Accent colour means emphasis, selection, focus, movement, and active control. Warning and success must remain perceptually distinct from the accent; do not make a palette so monochrome that “selected,” “good,” and “needs attention” become the same visual event.

Dark mode is a real design state, not a filter applied after the light theme is finished. Palette variants should preserve hierarchy and semantics in both themes.

## Controls and surfaces

Interactive controls keep a 44 px minimum target. Focus must be visible. Hover can add explanation but cannot be the only way to discover a consequential action or important value.

Primary, consequential actions keep visible text labels. Icon-only controls are appropriate for small secondary or cosmetic utilities when the symbol is recognizable and the control has a meaningful accessible name plus useful hover/focus text. If two nearby glyphs are easy to confuse, keep one of them visibly labelled instead of forcing icon purity.

Danger styling is for genuinely destructive or forceful actions, not for ordinary warnings. Disabled controls should still make sense in context; do not use disabled state as a substitute for explaining where the active work moved.

Cards are a grouping tool, not the default unit of thought. Do not open a page with a card that merely restates the page title, and do not wrap every paragraph or setting cluster in another bordered rectangle. Some of the strongest Preflight screens work because the ship, status, action, and quiet metadata share one field instead of becoming four widgets.

Radii stay modest. The interface can be tactile without becoming bubbly. Use pills only where the form itself carries meaning, not as a reflexive way to make a label look designed.

## Layout and responsive behaviour

The Tauri default window (`1040×700`) and minimum window (`720×560`) are both first-class layouts. A screen that only looks right when the window is larger than the app's own default is unfinished.

Responsive work should recompose before it merely scales down. At the minimum window, preserve action reachability, readable labels, useful identity, and clear recovery. A narrow version is allowed to move controls, collapse secondary regions, change the ship's role, or use a different grouping.

The desktop shell owns the window. Longer workspaces may scroll inside their content region. Home should avoid unnecessary scrolling at the standard size because it is the front door, but recovery content must remain reachable when the state genuinely needs more room.

Prefer explicit component state/modifier classes or data attributes for major compositions such as settled, preparation, recovery, or active. `:has()` is useful for local relationships; it should not quietly become the page-level state machine.

DOM/reading order and visual order should normally agree. Do not use CSS reversal to make the screen read one way while keyboard and assistive-technology navigation encounter another.

## Motion

Motion should be brief and productive. Navigation/disclosure movement can acknowledge that the interface changed; it should not ask for attention after the change is understood.

Decorative ship movement is allowed because it belongs to the instrument language, but it never carries essential meaning and always respects reduced-motion preferences. Pause, direction, and appearance controls remain secondary to the player's actual task.

Avoid animation as compensation for weak hierarchy. If the eye cannot find the important thing until it moves, fix the still frame first.

## Copy inside the interface

The first layer says the result, state, or action in ordinary player language. Implementation details and evidence can sit behind a disclosure when they are useful.

Do not repeatedly explain that an operation is safe, bounded, exact, reviewed, or authoritative when the player only needs to know what will happen. Keep real safeguards and meaningful confirmations; remove the speech describing the safeguard's internal proof model.

Confirmation text should earn its existence by changing a decision: what will be written or deleted, where something will be sent, what will be backed up, or what cannot be undone. A confirmation that repeats the button label with more anxiety is clutter.

The copy does not need to be unnaturally terse. A useful sentence can have some breath when the player is reading rather than acting. Labels and immediate recovery actions should still be quick to scan.

## CSS ownership

Preflight intentionally uses authored CSS rather than a utility framework. Do not introduce Tailwind, a component-theme framework, CSS-in-JS, or another styling language as a local convenience. A framework migration would be a project-level decision with an explicit reason and migration plan.

`styles.css` currently contains the foundation plus a lot of accumulated component styling. `release-readiness.css`, `game-settings-layout.css`, and `homePresentation.css` are later layers created by specific phases of the desktop work. Their load order is therefore part of the rendered result today, but historical file names and “loads last” are not a design system.

For new work:

- put palette/theme primitives and durable shared control rules in the common foundation;
- keep component-specific layout with the component or in a clearly named component/feature stylesheet;
- use semantic classes and modifiers rather than utility-class soup;
- avoid static inline styles when a class can own the rule;
- avoid adding a new late override layer merely because it is easier than understanding the current cascade;
- if a rule only works because its file happens to load last, consider that a maintenance smell and add resolved-cascade coverage when the ordering is genuinely intentional.

We do not need a giant token rewrite before beta. Over time, repeated spacing, radius, type-size, and elevation values should become named tokens when doing so removes real inconsistency. Do not churn settled screens merely to make every number originate from a variable.

## Rendered review is part of implementation

Source review and jsdom tests can establish semantics, state ownership, accessibility contracts, and some resolved CSS. They cannot tell you that a ship is sitting six inches too low, a recovery card feels detached, a label is visually microscopic, or two controls collide in an actual WebView.

For meaningful UI work, review the built browser frontend in Chromium/Playwright. Exercise the deterministic preview scenarios, including ordinary and failure/recovery states, at `1040×700` and `720×560`. Check hover and keyboard focus interactively rather than relying only on screenshots.

When a local checkout is unavailable, the verified frontend artifact from Desktop CI is a valid rendering source. Do not stop at “the source looks right” if the task is visual.

Before accepting a screen, ask:

1. Is the player's current job obvious before reading everything?
2. Does the DOM order agree with the visual hierarchy?
3. Are the important controls readable and reachable at both supported window sizes?
4. Did we add another card, badge, tooltip, icon, or explanation because it helps, or because frontend work tends to accrete them?
5. Does the screen still look like Preflight when the novelty of the latest change wears off?

## Change this guide when the product changes

This document records the current taste and the current implementation lessons. It is not a veto against a better idea. If a future screen genuinely wants a different composition, colour relationship, control pattern, or type treatment, change the interface and then change the guide so the exception becomes an understood decision rather than unexplained drift.
