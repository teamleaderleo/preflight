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


def budget(loop, target):
    """Simplify to at most `target` points, keeping the loosest tolerance that gets there."""
    best = loop
    for eps in (0.35, 0.5, 0.7, 1.0, 1.4, 1.9, 2.5, 3.2, 4.2, 5.5, 7.0, 9.0):
        best = rdp(loop, eps)
        if len(best) <= target:
            break
    return best


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


def build(game, name, target=64):
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
    outer = budget(to_ship(raw), target * 2)
    inner = [budget(to_ship(loop), 48) for loop in holes(mask, w, h)]
    return outer, inner, len(raw), lop


def normalise(outer, inner):
    """Centre on the origin and scale the longest axis to 1, which is what the page expects."""
    pts = outer + [p for loop in inner for p in loop]
    zs = [p[0] for p in pts]
    xs = [p[1] for p in pts]
    cz, cx = (min(zs) + max(zs)) / 2, (min(xs) + max(xs)) / 2
    span = max(max(zs) - min(zs), max(xs) - min(xs)) / 2

    def unit(loop):
        return [[round((p[0] - cz) / span, 3), round((p[1] - cx) / span, 3)] for p in loop]

    return unit(outer), [unit(loop) for loop in inner]


def rewrite(page, name, outer, inner):
    """Replace this hull's `o:` and `holes:` arrays, leaving its hand-written `deck:` alone."""
    at = page.index(f"{name}:{{")
    head = page[at:page.index("o:[", at)]
    tail = page.index("deck:[", at)
    block = f"o:{json.dumps(outer)},\n      holes:{json.dumps(inner)},\n      "
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
        outer, inner, source, lop = build(game, name)
        outer, inner = normalise(outer, inner)
        page = rewrite(page, name, outer, inner)
        print(f"{name:11} {source:5} contour pixels -> {len(outer):4} points   "
              f"voids {[len(loop) for loop in inner] or '-'}   "
              f"asymmetry {lop * 100:4.1f}% "
              f"{'(traced verbatim)' if lop > 0.06 else '(folded)'}")
    PAGE.write_text(page)
    print(f"wrote {PAGE.name}")


if __name__ == "__main__":
    main(sys.argv[1:])
