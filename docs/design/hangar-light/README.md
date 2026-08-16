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

Each hull in the page carries three things:

| field | what it is |
| --- | --- |
| `o` | the plan-view outline, traced from the sprite, ~100–125 points |
| `holes` | interior voids as their own closed loops |
| `decks` | one or more **hand-written** lanes; a station is `[z, height, half-width, centre x]` |
| `pods` | **hand-drawn** features, `[z, x, radius, height, sides]` — closed rings with two legs |

`o` and `holes` are generated. `decks` is not, and that is the point — evenly spaced ribs read
as a ruled grid laid over the ship, and scoring the artwork to pick better stations automatically
was barely different, because it finds the rows that are busy rather than the rows that are
structure. The stations are read off each sprite: the nose cap, the pod rows or the broadsides,
the bridge, the face of the engine block. Width is what gives the deck slopes and overhangs —
zero width is a knife ridge, a wide station is a plate, and a station wider than the one before
it overhangs the hull beneath.

**A hull gets as many lanes as it has spines.** One centreline run is the assumption that every
ship is a fish, and three of these five are not. An Onslaught's prow is three separate prongs
from `z=+0.93` down to `+0.25` — measured off the sprite — so a single deck over them is a bridge
built across two trenches; it carries one lane per prong, and they stop where the prongs merge. A
Paragon is a ring, and the one wrong place for a deck is straight down the middle of it, so the
bow cap, each arm and the command module standing up inside the ring's mouth each get their own.
The Odyssey's spine does not run down `x=0` at all. A station whose centre has no hull under it
is dropped rather than drawn, which is what stops a lane hanging a bar over open space.

The deck is tied to the hull by **raked struts**, two per station, landing on the silhouette
between that station and its neighbours. Both other things this has been are wrong: a strut
square to the keel is a ladder rung whatever you do to its length — it is what made the Odyssey
read as a barrel with hoops round it — and a deck tied to nothing at all is an island floating in
the middle of the ship.

What is held constant is the strut's **angle**, not how far along the ship it reaches. Reaching a
fixed fraction of the gap to the next station lets the rake be decided by how wide the ship
happens to be there, and across an Onslaught's beam that lies down flat and is a rung again. The
run aft is set from the run outboard and then clipped so a strut cannot overshoot its neighbour.

## Draw less

Three separate things were each filling the middle of a ship with rectangles, and all three are
gone. Between them they were about a fifth of every hull's line count and none of them said
anything the reader could not already see.

- **The deck is one closed plate**, two long edges and a cap at each end — not a crossbar at
  every station. A plate reads as a plate from its outline. Whether that outline comes out a
  triangle, a pentagon or a long hexagon is decided by how many stations the lane has, which is
  decided by the ship, which is the right thing for it to depend on.
- **Short legs at the ends of a plate**, straight down to the hull under them. Every version
  that reached out to the *silhouette* — rung, chine, raked strut — drew a line arcing clean
  across the ship, because on a wide hull that is what a deck-edge-to-outline connection is at
  any angle. A leg is local and cannot cross anything. That fault took four attempts.
- **Two connections a lane, at its ends**, and nothing between them. A crossbar at the beam was
  tried and pulled: no sprite has a feature there, it was in because a plate "should" be
  divided somewhere. Struts at the beam were worse — at the widest part of a wide hull the
  rake clips flat and draws long shallow lines across everything.
- **Verticals only at the hull's corners** — the sharpest turns in the outline, which on these
  ships are the prow, the shoulders and the transom. One every nth vertex boxes the whole rim
  into rectangles. Removing them entirely was tried too and is also wrong: with nothing tying
  the three rings together they read as contour lines on a map instead of a solid.

The general rule, since it keeps having to be rediscovered: **negative space is doing work.** A
line has to earn its place against the reader's own ability to close a shape.

## What is drawn rather than derived

`pods` is the escape hatch from all of the above, and the reason it exists is that everything
else on this page is a rule applied uniformly to six ships. A rule cannot know that an Odyssey
is six recessed pod wells hung off a spine, that a Paragon's ring is studded with bastions, or
that an Onslaught has one big lit disc on the centreline with a heavy drum on each beam. Those
are the things people actually recognise, and they are typed in by hand off each sprite —
position, radius, height, how many sides.

They are also the only closed shapes on a hull that are not the hull, which is what makes them
read as fittings rather than structure. Six sides for a round housing, eight for the big ones.

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
