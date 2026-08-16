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
| `deck` | five stations, **written by hand**, each `[z, height, half-width of the flat top]` |

`o` and `holes` are generated. `deck` is not, and that is the point — evenly spaced ribs read
as a ruled grid laid over the ship, and scoring the artwork to pick better stations automatically
was barely different, because it finds the rows that are busy rather than the rows that are
structure. The stations are read off each sprite: the nose cap, the pod rows or the broadsides,
the bridge, the face of the engine block. Width is what gives the deck slopes and overhangs —
zero width is a knife ridge, a wide station is a plate, and a station wider than the one before
it overhangs the hull beneath.

## Regenerating the outlines

Only needed to change them. Requires a Starsector installation; nothing from it is copied.

```bash
python3 docs/design/hangar-light/trace-hulls.py --game /path/to/Starsector/Contents/Resources/Java
```

It rewrites `o:` and `holes:` in place and never touches `deck:`. On this Mac the default path
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
