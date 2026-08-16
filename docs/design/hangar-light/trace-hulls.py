"""Trace the wireframe hull outlines in hangar-light.html from an installed Starsector.

The page ships with the outlines already in it, so this is only needed to change them: point it
at an installation and it rewrites the `o:` and `holes:` arrays in place. The `deck:` stations
are written by hand off each sprite and are never touched.

    python3 trace-hulls.py [--game /path/to/Starsector/Contents/Resources/Java] [hull ...]

Nothing from the installation is copied. What comes out is a simplified plan-view contour of
each hull -- around a hundred points where the sprite's own contour has a thousand -- which is
the same silhouette the game already shows anyone who plays it.
"""
import json
import math
import re
import struct
import sys
import zlib
from pathlib import Path
from collections import deque

DEFAULT_GAME = "/Applications/Starsector.app/Contents/Resources/Java"
HULLS = ["odyssey", "onslaught", "conquest", "paragon", "astral", "hammerhead"]
PAGE = Path(__file__).with_name("hangar-light.html")
NBR = [(1, 0), (1, 1), (0, 1), (-1, 1), (-1, 0), (-1, -1), (0, -1), (1, -1)]


def read_png(path):
    data = open(path, "rb").read()
    i, idat, hdr = 8, b"", None
    while i < len(data):
        length = struct.unpack(">I", data[i:i + 4])[0]
        kind = data[i + 4:i + 8]
        chunk = data[i + 8:i + 8 + length]
        if kind == b"IHDR":
            hdr = struct.unpack(">IIBBBBB", chunk[:13])
        elif kind == b"IDAT":
            idat += chunk
        elif kind == b"IEND":
            break
        i += 12 + length
    w, h, depth, colour, _, _, interlace = hdr
    assert (depth, colour, interlace) == (8, 6, 0), (path, hdr)
    raw, stride = zlib.decompress(idat), w * 4
    out, prev, pos = bytearray(h * stride), bytearray(stride), 0
    for y in range(h):
        f = raw[pos]
        pos += 1
        line = bytearray(raw[pos:pos + stride])
        pos += stride
        if f == 1:
            for x in range(4, stride):
                line[x] = (line[x] + line[x - 4]) & 255
        elif f == 2:
            for x in range(stride):
                line[x] = (line[x] + prev[x]) & 255
        elif f == 3:
            for x in range(stride):
                a = line[x - 4] if x >= 4 else 0
                line[x] = (line[x] + ((a + prev[x]) >> 1)) & 255
        elif f == 4:
            for x in range(stride):
                a = line[x - 4] if x >= 4 else 0
                b, c = prev[x], (prev[x - 4] if x >= 4 else 0)
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if (pa <= pb and pa <= pc) else (b if pb <= pc else c))) & 255
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, out


def march(mask, w, h, seed, want):
    """Moore-neighbour trace starting at `seed`, following cells whose value is `want`."""
    contour, cur, back = [seed], seed, 4
    for _ in range(4 * w * h):
        step = None
        for k in range(8):
            d = (back + 1 + k) % 8
            nx, ny = cur[0] + NBR[d][0], cur[1] + NBR[d][1]
            if 0 <= nx < w and 0 <= ny < h and mask[ny][nx] == want:
                step, back, cur = d, (d + 5) % 8, (nx, ny)
                break
        if step is None:
            break
        contour.append(cur)
        if len(contour) > 3 and cur == seed:
            return contour[:-1]
    return contour


def rdp(points, eps):
    if len(points) < 3:
        return points
    ax, ay = points[0]
    bx, by = points[-1]
    dx, dy = bx - ax, by - ay
    norm = math.hypot(dx, dy) or 1.0
    worst, index = -1.0, 0
    for i in range(1, len(points) - 1):
        px, py = points[i]
        d = abs(dy * px - dx * py + bx * ay - by * ax) / norm
        if d > worst:
            worst, index = d, i
    if worst <= eps:
        return [points[0], points[-1]]
    return rdp(points[:index + 1], eps)[:-1] + rdp(points[index:], eps)


def budget(loop, eps, cap):
    """Simplify at a fixed tolerance, and only fall back to a point count if that overruns.

    Tolerance, not a point budget. A budget makes every hull spend the same number of points
    whatever its shape, so an Astral and a Hammerhead get the same allowance for very different
    amounts of ship, and the tolerance that buys it is whatever it happens to be. At a fixed
    tolerance hulls land where their own complexity puts them -- 66 for an Odyssey, 104 for an
    Astral -- and the number is a decision about how closely to follow the artwork rather than
    a side effect.

    Deep notches survive either way; Douglas-Peucker keeps the largest deviation first, so the
    Onslaught's prow trenches are the last thing it would drop, not the first. They are intact
    at every tolerance tried up to 3.5. The cap is only there so a pathological outline cannot
    run away.
    """
    out = rdp(loop, eps)
    while len(out) > cap:
        eps *= 1.25
        out = rdp(loop, eps)
    return out


def holes(mask, w, h):
    """Background components that the border cannot reach are interior voids."""
    seen = [[False] * w for _ in range(h)]
    queue = deque()
    for x in range(w):
        for y in (0, h - 1):
            if not mask[y][x]:
                seen[y][x] = True
                queue.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            if not mask[y][x]:
                seen[y][x] = True
                queue.append((x, y))
    while queue:
        x, y = queue.popleft()
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < w and 0 <= ny < h and not mask[ny][nx] and not seen[ny][nx]:
                seen[ny][nx] = True
                queue.append((nx, ny))
    found, claimed = [], [[False] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            if mask[y][x] or seen[y][x] or claimed[y][x]:
                continue
            blob, q = [], deque([(x, y)])
            claimed[y][x] = True
            while q:
                cx, cy = q.popleft()
                blob.append((cx, cy))
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = cx + dx, cy + dy
                    if (0 <= nx < w and 0 <= ny < h and not mask[ny][nx]
                            and not seen[ny][nx] and not claimed[ny][nx]):
                        claimed[ny][nx] = True
                        q.append((nx, ny))
            if len(blob) >= 60:
                found.append(march(mask, w, h, min(blob, key=lambda p: (p[1], p[0])), False))
    return found


def blur_luma(px, w, h, mask):
    """The sprite's own lighting, blurred until the greebling is gone and only masses are left."""
    lum = [0.0] * (w * h)
    for i in range(w * h):
        if mask[i // w][i % w]:
            lum[i] = (0.299 * px[i * 4] + 0.587 * px[i * 4 + 1] + 0.114 * px[i * 4 + 2]) / 255
    r = max(2, int(w * BLUR))
    on = [mask[i // w][i % w] for i in range(w * h)]

    def sweep(src, horizontal):
        dst = [0.0] * (w * h)
        outer, inner = (h, w) if horizontal else (w, h)
        for c in range(outer):
            acc = num = 0
            for t in range(inner + r):
                if t < inner:
                    i = c * w + t if horizontal else t * w + c
                    if on[i]:
                        acc += src[i]
                        num += 1
                d = t - 2 * r - 1
                if d >= 0:
                    j = c * w + d if horizontal else d * w + c
                    if on[j]:
                        acc -= src[j]
                        num -= 1
                m = t - r
                if 0 <= m < inner:
                    k = c * w + m if horizontal else m * w + c
                    dst[k] = acc / num if num else 0.0
        return dst

    return sweep(sweep(lum, True), False)


def masses(px, w, h, mask, cut):
    """Closed contours of everything the sprite lights above `cut` of its own range.

    This is the same march that produces the silhouette, one level in: the silhouette is the
    boundary between hull and space, and these are the boundaries between one raised block and
    the next. They are the shapes inside the ship, which is what anyone actually recognises --
    an Odyssey's spine and its pod cluster, an Onslaught's three blocks, a Paragon's ring.
    """
    lum = blur_luma(px, w, h, mask)
    vals = sorted(lum[y * w + x] for y in range(h) for x in range(w) if mask[y][x])
    if not vals:
        return []
    lo, hi = vals[len(vals) // 50], vals[-len(vals) // 50 - 1]
    level = lo + (hi - lo) * cut
    up = [[mask[y][x] and lum[y * w + x] >= level for x in range(w)] for y in range(h)]
    seen, out = [[False] * w for _ in range(h)], []
    for y in range(h):
        for x in range(w):
            if not up[y][x] or seen[y][x]:
                continue
            blob, q = [], deque([(x, y)])
            seen[y][x] = True
            while q:
                cx, cy = q.popleft()
                blob.append((cx, cy))
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = cx + dx, cy + dy
                    if 0 <= nx < w and 0 <= ny < h and up[ny][nx] and not seen[ny][nx]:
                        seen[ny][nx] = True
                        q.append((nx, ny))
            if len(blob) >= w * h * MIN_AREA:
                out.append(march(up, w, h, min(blob, key=lambda p: (p[1], p[0])), True))
    return out


def load_ship(game, name):
    return json.loads(re.sub(r"#[^\n]*", "", open(f"{game}/data/hulls/{name}.ship").read()))


def symmetry_axis(mask, w, h, cx):
    """
    The column the artwork is actually drawn symmetric about.
    """
    # `center` is where the game hangs the sprite, not necessarily the column the art is drawn
    # symmetric about, and being one pixel out welds narrow slots shut when the two halves are
    # OR-ed together -- which is what closed the Conquest's bow gap. So the axis is searched for.
    base, best, axis = int(round(cx)), -1, int(round(cx))
    for cand in range(base - 6, base + 7):
        agree = 0
        for y in range(0, h, 3):
            row = mask[y]
            for x in range(0, w, 2):
                m = 2 * cand - x
                if 0 <= m < w and row[x] == row[m]:
                    agree += 1
        if agree > best:
            best, axis = agree, cand
    return axis


def asymmetry(mask, w, h, axis):
    """Share of the hull that disagrees with its own reflection."""
    on = miss = 0
    for y in range(h):
        row = mask[y]
        for x in range(w):
            if row[x]:
                on += 1
            m = 2 * axis - x
            if 0 <= m < w and row[x] != row[m]:
                miss += 1
    return miss / on if on else 0.0


def fold(mask, w, h, axis):
    for y in range(h):
        row = mask[y]
        for x in range(w):
            m = 2 * axis - x
            if 0 <= m < w and row[m]:
                row[x] = True
    return mask


# The four knobs that decide how much of the inside gets drawn. They are all in one place
# because they only make sense together: blurring harder merges blocks, so the area floor can
# come down; raising the floor without blurring just deletes small blocks and keeps ragged big
# ones. Tuned to land each hull at three to six shapes of fifteen-odd points, which is a hull
# you can read at a glance rather than a contour map of its greebling.
BLUR = 0.085       # box radius as a fraction of sprite width
MIN_AREA = 0.022   # smallest region kept, as a fraction of the sprite's area
OUTER_EPS = 2.8    # simplification tolerance in sprite pixels, for the outline
HOLE_EPS = 1.4     # tighter for voids: they are small, so the same tolerance is a bigger share
                   # of them -- at 2.8 the Astral's flight decks came out as five-point slivers
TIER_EPS = 4.5     # ditto for an interior contour, which is a blurred blob and wants less
TIERS = [(0.32, 0.42), (0.66, 0.86)]


def build(game, name):
    ship = load_ship(game, name)
    w, h, px = read_png(f"{game}/{ship['spriteName']}")
    cx, cy = ship["center"]
    # A low alpha cut keeps the thin structures -- the Onslaught's prow slots are only a few
    # pixels of transparency wide and a high cut welds them shut before simplification sees them.
    mask = [[px[(y * w + x) * 4 + 3] > 16 for x in range(w)] for y in range(h)]
    axis = symmetry_axis(mask, w, h, cx)
    # Only fold the hulls that are actually symmetric. Measured against their own sprites the
    # Onslaught, Conquest, Paragon and Hammerhead disagree with their reflection over 1.6-4.5 %
    # of their pixels, which is rendering noise. The Odyssey's figure is 16.4 %, because the
    # Odyssey is drawn asymmetric on purpose: folding it would delete what makes it recognisable.
    lop = asymmetry(mask, w, h, axis)
    if lop <= 0.06:
        mask = fold(mask, w, h, axis)

    def to_ship(loop):
        return [[round(-(y - (h - cy)), 2), round(-(x - cx), 2)] for x, y in loop]

    start = next((x, y) for y in range(h) for x in range(w) if mask[y][x])
    raw = march(mask, w, h, start, True)
    # Folding the mask is what makes a hull symmetric; the trace of a folded mask already is.
    # Rebuilding the far flank from the near one was tried and is worse than useless -- it joins
    # the two ends of the walk straight across whatever sits between them, which welded the
    # Conquest's bow channel shut into a spire.
    outer = budget(to_ship(raw), OUTER_EPS, 130)
    inner = [budget(to_ship(loop), HOLE_EPS, 48) for loop in holes(mask, w, h)]
    tiers = []
    for cut, lift in TIERS:
        for loop in masses(px, w, h, mask, cut):
            tiers.append((lift, budget(to_ship(loop), TIER_EPS, 30)))
    return outer, inner, len(raw), lop, tiers


def normalise(outer, inner, tiers):
    """Centre on the origin and scale the longest axis to 1, which is what the page expects.

    The frame comes from the outline alone, and the interiors are carried through it, so a
    tier stays where it sits on the ship instead of being fitted to its own bounding box.
    """
    pts = outer + [p for loop in inner for p in loop]
    zs = [p[0] for p in pts]
    xs = [p[1] for p in pts]
    cz, cx = (min(zs) + max(zs)) / 2, (min(xs) + max(xs)) / 2
    span = max(max(zs) - min(zs), max(xs) - min(xs)) / 2

    def unit(loop):
        return [[round((p[0] - cz) / span, 3), round((p[1] - cx) / span, 3)] for p in loop]

    return unit(outer), [unit(loop) for loop in inner], [(lift, unit(loop)) for lift, loop in tiers]


def rewrite(page, name, outer, inner, tiers):
    """Replace this hull's whole geometry: outline, voids and interior tiers.

    Nothing in a hull entry is written by hand any more. Hand-written deck stations were tried
    at length and the answer was always the same -- a rule applied to six ships cannot know what
    any one of them looks like. The interiors are marched off the sprite's own lighting by the
    same code that marches the silhouette off its alpha.
    """
    at = page.index(f"{name}:{{")
    head = page[at:page.index("o:[", at)]
    tail = page.index("},\n", page.index("inner:[", at))
    block = (f"o:{json.dumps(outer)},\n      holes:{json.dumps(inner)},\n      inner:["
             + ",".join(f"[{lift},{json.dumps(loop)}]" for lift, loop in tiers) + "]")
    return page[:at] + head + block + page[tail:]


def main(argv):
    game = DEFAULT_GAME
    if "--game" in argv:
        at = argv.index("--game")
        game, argv = argv[at + 1], argv[:at] + argv[at + 2:]
    if not Path(game, "data", "hulls").is_dir():
        raise SystemExit(f"no Starsector data/hulls under {game} -- pass --game")
    page = PAGE.read_text()
    for name in (argv or HULLS):
        outer, inner, source, lop, tiers = build(game, name)
        outer, inner, tiers = normalise(outer, inner, tiers)
        page = rewrite(page, name, outer, inner, tiers)
        print(f"{name:11} {source:5} contour pixels -> {len(outer):4} points   "
              f"voids {[len(loop) for loop in inner] or '-'}   "
              f"inner {len(tiers)}   "
              f"asymmetry {lop * 100:4.1f}% "
              f"{'(traced verbatim)' if lop > 0.06 else '(folded)'}")
    PAGE.write_text(page)
    print(f"wrote {PAGE.name}")


if __name__ == "__main__":
    main(sys.argv[1:])
