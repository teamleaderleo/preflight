#!/usr/bin/env python3
"""Export the six accepted Hangar Light traces into the desktop bundle.

The page is the source. Nothing here decides what a hull looks like -- it copies the traced
contours out of `hangar-light.html`, scales them into the frame the runtime already uses for
collision-bound hulls, and writes one deterministic file.

    python3 export-product-hulls.py            # write the artifact
    python3 export-product-hulls.py --check     # fail if the committed artifact is stale

`--check` is the whole point. The contours now live in three places -- the page, this artifact,
and the runtime builder that draws it -- and the page and the contact sheet have already drifted
apart three times while looking fine in a thumbnail. A generated file that nothing regenerates
is a copy, and copies rot.
"""

import argparse
import importlib.util
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
PAGE = HERE / "hangar-light.html"
DEFAULT_OUTPUT = HERE.parents[2] / "preflight-desktop" / "src" / "generated" / "curated-wireframe-hulls.json"

FORMAT = "preflight-curated-wireframe-hulls-v1"

# The page normalises every hull to the same +/-1 frame. The runtime measures its own extent, so
# the absolute scale does not matter -- but sprite-derived and collision-derived hulls flow through
# one `hullFrame`, and keeping both in the same order of magnitude keeps every tolerance in that
# file comparable between them.
SCALE = 100

# hullSize and tech come from the game's own `data/hulls/*.ship` and `ship_data.csv`, checked
# rather than assumed: the page calls the Odyssey a cruiser, which is how it plays and not what
# the game records. Two enum values per ship, typed here so the export needs no installation.
TAXONOMY = {
    "odyssey": ("CAPITAL_SHIP", "HIGH_TECH"),
    "onslaught": ("CAPITAL_SHIP", "LOW_TECH"),
    "conquest": ("CAPITAL_SHIP", "MIDLINE"),
    "paragon": ("CAPITAL_SHIP", "HIGH_TECH"),
    "astral": ("CAPITAL_SHIP", "HIGH_TECH"),
    "hammerhead": ("DESTROYER", "MIDLINE"),
}


def design_hulls():
    """Reuse the contact sheet's parser rather than writing a second one to disagree with it."""
    source = HERE / "contact-sheet.py"
    spec = importlib.util.spec_from_file_location("hangar_contact_sheet", source)
    if spec is None or spec.loader is None:
        raise SystemExit(f"cannot import {source}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.hulls(), module.KNOB


def point(pair):
    """The page stores [along, across]; the runtime's x is along the keel and y across it."""
    return {"x": round(pair[0] * SCALE, 2), "y": round(pair[1] * SCALE, 2)}


def loop(points):
    return [point(pair) for pair in points]


def artifact():
    hulls, knob = design_hulls()
    missing = sorted(set(TAXONOMY) - set(hulls))
    if missing:
        raise SystemExit(f"page is missing hulls: {', '.join(missing)}")
    records = []
    for hull_id, hull in hulls.items():
        hull_size, style = TAXONOMY[hull_id]
        records.append({
            "id": hull_id,
            "name": hull["name"],
            "hullSize": hull_size,
            "style": style,
            "featured": True,
            # The silhouette stands in for collision bounds: it is the same kind of closed loop,
            # traced off the sprite's alpha instead of hand-placed, so every consumer of `bounds`
            # keeps working without knowing which kind of hull it has.
            "bounds": loop(hull["o"]),
            "engines": [],
            "mounts": [],
            "curated": {
                "format": "preflight-curated-wireframe-v1",
                "thickness": hull["thick"],
                "engineBells": hull["bells"],
                "holes": [loop(void) for void in hull["holes"]],
                "inner": [{"height": height, "points": loop(shape)} for height, shape in hull["inner"]],
            },
        })
    return {
        "format": FORMAT,
        # Provenance, so a future reader can tell whether this file was made under the same
        # conditions as whatever they are comparing it against.
        "source": "docs/design/hangar-light/hangar-light.html",
        "generator": "docs/design/hangar-light/export-product-hulls.py",
        "scale": SCALE,
        "knobs": knob,
        "hulls": records,
    }


def encoded():
    return json.dumps(artifact(), ensure_ascii=False, indent=2) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true",
                        help="exit non-zero if the committed artifact no longer matches the page")
    args = parser.parse_args()

    expected = encoded()
    if args.check:
        if not args.output.is_file():
            raise SystemExit(f"missing generated hull artifact: {args.output}")
        if args.output.read_text() != expected:
            raise SystemExit(
                f"stale generated hull artifact: {args.output}\n"
                f"the page has moved since it was written -- re-run {Path(__file__).name}")
        print(f"current: {args.output}")
        return

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(expected)
    data = json.loads(expected)
    for record in data["hulls"]:
        curated = record["curated"]
        print(f"{record['id']:11} {len(record['bounds']):4} outline  "
              f"{len(curated['holes'])} void(s)  {len(curated['inner'])} interior")
    print(f"wrote {args.output}")


if __name__ == "__main__":
    main()
