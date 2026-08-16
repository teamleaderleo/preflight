# Design reference

Where the visual decisions live, and which of them are already shipped.

This exists because the alternative is a chat log. Most of what is here was argued out over
several sessions, and every entry either records a decision that is now load-bearing in the
product or a dead end expensive enough that rediscovering it would cost a day.

## The prototype

**[`hangar-light/`](hangar-light/)** — one self-contained HTML page, openable from `file://`,
plus the two scripts that generate its data. This is the reference artefact for the desktop's
look. It carries, in one place:

- **The palette**, in both themes, as the source the shipped tokens were lifted from.
- **The wireframe hulls** — six ships traced inside and out from the installed sprites, with
  live dials for how finely they draw.
- **UI elements under argument**: the playtime readout and its session filter, and three
  rejected treatments of the same idea kept as cards so the rejections stay visible.

Its own [README](hangar-light/README.md) covers how a hull is built, every knob, what is
settled, what is still open, and a list of approaches that are wrong with the reason each one
failed.

## What shipped out of it

**The palette.** `preflight-desktop/src/styles.css` takes its tokens from this page **by hex**,
not by re-derivation — `#efe8dc` / `#f6f1e8` / `#241d14` on light, `#100e0a` / `#191510` /
`#e6ded0` on dark, gold `#9a6a24` and `#c8944a` (#505). Two attempts to reach that palette from
a description instead of copying it produced a brown app and then a slate one.

`styles.test.ts` pins the grounds and the accents by hex, because the difference between this
and brown is about 0.007 chroma and nothing weaker catches a drift back.

**Four palettes**, Blueprint / Hangar / Ultraviolet / Airglow, each an OKLCH rotation with
lightness held fixed so every contrast ratio survives and no accessibility recheck is needed
(#519). Status colours deliberately do not rotate with the accent: a palette changes what the
app is made of, not what it is telling you. The test measures OKLab distance between each
palette's accent and its status colours, which is what caught a warning sitting 0.041 from its
own accent — closer than the pair the rule was written to reject.

**Adding a palette** means: a `:root[data-palette="…"]` block in `styles.css`, the name in
`PALETTES` in `useTheme.ts`, a swatch rule, **and the name in `public/theme-init.js`**. That
last one is the easy miss — a palette absent from the pre-paint list renders as Blueprint for
one frame and is then corrected by React, which is the exact flash that file exists to remove.

## Other notes here

- [`icon-generation.md`](icon-generation.md) — how the application icon is produced.
- [`save-reference-filter.md`](save-reference-filter.md) — the save-file reference filter.

## Working rule

When a visual decision is described in words rather than pointed at, **ask which artefact is
meant and copy its values**. Colour names in a brief are gestures, not specifications, and
re-deriving a palette from a description has failed here twice.
