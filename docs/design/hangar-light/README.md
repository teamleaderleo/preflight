# Hangar Light

A visual-direction prototype for the desktop. Open
[`hangar-light.html`](hangar-light.html) in a browser — it is one self-contained file with no
build step, no dependencies and no network access, so a `file://` open works.

It exists so palette and hull decisions get made against something clickable. It is not wired
into the app and nothing here runs in the product.

## What it settles

**The palette shipped.** The desktop's tokens are lifted from this page by hex (`#efe8dc` /
`#f6f1e8` / `#241d14` on light, `#100e0a` / `#191510` / `#e6ded0` on dark, gold `#9a6a24` and
`#c8944a`). This page stays the reference: change it here first, then copy the hexes into
`preflight-desktop/src/styles.css`, where `styles.test.ts` pins them.

**The hull is open.** A rotating wireframe as the idle state of Home, and the construction of
that wireframe. Discussion is in
[#496](https://github.com/teamleaderleo/preflight/issues/496); a separate runtime hull catalog
is being built independently and lofts the collision bounds rather than the artwork.

## How a hull is built

Each hull in the page carries three arrays, and **all three are traced**. Nothing is typed in.

| field | what it is |
| --- | --- |
| `o` | the plan-view outline, marched off the sprite's alpha, 44–104 points |
| `holes` | interior voids as their own closed loops |
| `inner` | the raised blocks inside the hull, `[height, contour]`, marched off the sprite's light |

`inner` is the answer to a question this prototype got wrong for a long time. The silhouette is
the boundary between hull and space. The shapes **inside** a ship — an Odyssey's spine and its
pod cluster, an Onslaught's three blocks, a Paragon's ring, an Astral's wings either side of its
flight decks — are the boundaries between one raised block and the next, and they are what
anyone actually recognises. They come off the same march, one level in: the sprite's own
luminance, blurred until the greebling is gone, cut at two thresholds, and every region above
each threshold traced as a closed loop and stood up at that height with four legs.

**Everything hand-typed was deleted to get here**, and the history is worth keeping because it
was three weeks of the same mistake. There were hand-written deck stations, then hand-written
lanes with a centre offset, then hand-placed pods. Each was a rule applied to six ships, and a
rule cannot know what any one ship looks like — the sprite can, and the sprite was sitting there
the whole time. The last version of it, `pods`, was hexagons scattered at coordinates read off
by eye; it survived one look next to the artwork.

Related dead ends, all of them the deck reaching for the silhouette and drawing a line across
the whole ship: a rung square to the keel, a chine partway out, a raked strut held at a fixed
angle. The interiors need none of it — a traced block already sits where it sits, and four short
legs at its compass extremes stand it up.

## The six

Odyssey, Onslaught, Conquest, Paragon, Astral, Hammerhead.

The first four are picked for silhouettes nothing else in the game has: a long asymmetric spire,
three prongs over two trenches, a channel down the middle, a ring. The Astral is the carrier and
brings a third kind of void — parallel slots either side of a spine, after the Conquest's channel
and the Paragon's hole. The Hammerhead is here because it is everyone's first destroyer.

Tracing the alternatives is what settled it, and the plan-view row is the argument: Legion and
Dominator are the Onslaught's silhouette at another size, and Atlas is distinct only by being a
freighter. The Ziggurat is the strongest shape in the game and is not in the set because naming
it is a spoiler — if it ever goes in it should be drawn and labelled **???**.

## The contact sheet

```bash
python3 docs/design/hangar-light/contact-sheet.py -o sheet.png [--cell 260] [hull ...]
```

Every hull at every angle worth judging it from, in one PNG, in about a fifth of a second. The
page has the same thing on it below the stage.

This is not a nicety. Turning one ship at a time in a browser and remembering what the last one
looked like is how you end up with five hulls that are each defensible and do not look like a
fleet, and every fault listed above was found in a row and invisible in isolation. Plan is the
column to trust — it is the only one the sprite can vouch for.

The other columns are interpretation and are allowed to be. Nothing above plan view exists in a
flat sprite, so where faithfulness and legibility disagree there, legibility wins.

`--sprites` also draws a **relief** column: the sprite's own luminance, blurred at about a
twentieth of the hull's width and cut into four bands. Starsector's art is lit from straight
above, so a raised deck is bright and a well between two blocks is dark, and that is the only
thing a flat sprite says about where a third axis would go. Posterising it unblurred does not
work — the bands chase the greebling, which sits at every tone. Blurred, it is a map of where a
line is worth drawing at all, and where the answer is nothing.

The relief has rewritten three of the six, and in both directions:

- **Odyssey** carried a second deck on its port shoulder, written on the theory that the
  shoulder is the feature the hull is bent around. The relief says that shoulder is a **well** —
  the Odyssey is a spine ship with its pods hung off it, recessed. Lane deleted. Its one
  remaining lane also stopped bulging amidships, because the lit spine is near-constant width.
- **Onslaught**'s outer lanes used to stop where the prongs merge. Wrong: each prong is the
  forward end of a **broadside block** that carries on past the quarter, lit as a mass of its
  own with a trough between it and the central dome. All three lanes now run the ship's length,
  which is what the three-mass relief has been saying all along.
- **Paragon** gained the two **stern pods**, which are lit as separate masses rather than as
  part of the body, and its ring lanes moved outboard. The bright band on that ring runs along
  its *outer* edge; centred on the arm they split the difference between ring and hole and
  landed on neither.

The pattern in all three: a lane was where a lane seemed reasonable, and the relief had an
opinion.

**Pass `--sprites` and compare against the artwork, not against your own last render.** A render
next to a render only tells you what changed. Next to the sprite it told us immediately that
every deck plate was about half again as wide as the superstructure it stood for — the Astral's
spine between its flight decks, the Onslaught's central ridge, the Paragon's bow. Those got
narrowed by hand, per ship, which is the only way that particular fault gets fixed. The sprite
column is read live from an installation and **its output is scratch, not repository content**.

The script reads the hull data straight out of the page, so the outlines and lanes can only be
the page's own, but it **ports** `build()` and `project()`. Change one, change the other; the
edge count it prints is the check, and it has to match what the page reports.

## The fidelity knobs

**Four of them are sliders on the page.** Detail, height, tier lift and interiors on/off move
live, because they were each a number argued about in a commit message and they are quicker to
settle by dragging. `contact-sheet.py` takes the same four as flags with the same defaults, so
drag until it looks right and then pass what you settled on.

That is why the page ships its shapes finely — 94 to 209 points rather than the 44 to 104 it
draws at. **What is stored is not the fidelity, it is the ceiling on it**; the browser runs the
same Douglas-Peucker again on the way in.

The rest need the sprite, so they stay in `trace-hulls.py` and need a re-trace:

| knob | what it does |
| --- | --- |
| `OUTER_EPS` | the ceiling on outline fidelity, in sprite pixels — the slider works down from it |
| `HOLE_EPS` | the same for voids, tighter — a void is small, so a given tolerance eats more of it |
| `BLUR` | how hard the lighting is smoothed before the interiors are cut out of it |
| `MIN_AREA` | the smallest interior block worth drawing |
| `TIER_EPS` | how closely an interior contour follows its blurred blob |
| `TIERS` | where the light is cut, and how high each tier stands |

Two notes on setting them. **Tolerance, not a point budget:** a budget gives every hull the same
allowance for very different amounts of ship, and whatever tolerance buys it. At a fixed
tolerance a hull lands where its own complexity puts it — 44 points for a Hammerhead, 104 for an
Astral — and the number is a decision rather than a side effect. **Blur and area floor move
together:** blurring harder merges blocks so the floor can come down; raising the floor without
blurring just deletes the small blocks and keeps the ragged big ones.

Deep notches are not the thing that gets lost when these are loosened. Douglas-Peucker keeps the
largest deviation first, so the Onslaught's prow trenches are the last thing it would drop —
they are intact at every tolerance tried up to 3.5, which was checked by counting spans across
the prow rather than by looking at a thumbnail.

## Regenerating the outlines

Only needed to change them. Requires a Starsector installation; nothing from it is copied.

```bash
python3 docs/design/hangar-light/trace-hulls.py --game /path/to/Starsector/Contents/Resources/Java
```

It rewrites `o:` and `holes:` in place and never touches `decks:`. On this Mac the default path
is already correct and the argument can be dropped.

## Things that were tried and are wrong

- **The collision bounds are not the silhouette.** They are what the game hit-tests, so they
  inscribe the artwork and chord across every sweep. The Odyssey's flanks are scalloped by three
  circular pods a side and the bounds draw that as one straight line.
- **Do not fold the Odyssey.** Four of these five hulls disagree with their own reflection over
  1.6–4.5 % of their pixels, which is rendering noise, so their masks are folded before tracing.
  The Odyssey's figure is 16.4 %: it is asymmetric on purpose, and folding it deletes what makes
  the ship recognisable. The tracer measures this rather than being told.
- **Do not rebuild the far flank from the near one.** It joins the two ends of the walk straight
  across whatever sits between them, which welds the Conquest's bow channel shut into a spire.
- **Do not decimate the deck and keel rings.** A notch is exactly the feature a sampled ring
  loses, and the chord it draws instead runs through the empty space the notch is for. The rings
  follow every point; the frames between them are what gets thinned.
- **Do not connect a deck square to the keel, and do not leave it unconnected.** See above. The
  first is a ladder, which this construction has turned back into twice; the second is a deck
  floating in the middle of the ship. Raked struts at a held angle are the answer to both.
- Earlier dead ends, in order: a drum per weapon slot ("barnacles"), only the heaviest mounts,
  a triangulated height field with slots as bumps, rays from a centreline hub, and deepening the
  concave corners while bowing long runs into arcs — the last of which self-intersects any
  outline that touches itself, after which the even-odd test reads half the hull as outside.

## The boundary

Outlines here are derived from an installation's sprites, which is the one thing this repository
otherwise keeps out — see `scripts/verify_source_boundary.py` and the rule in `CLAUDE.md`. What
is committed is a simplified contour, around a hundred points where the sprite's own is a
thousand: the same silhouette the game shows anyone who plays it, and regenerable from any
install by the script above. No sprite, no game file and no byte of the installation is here.
If that call goes the other way, deleting the `o:` and `holes:` arrays is enough — the tracer
puts them back.
