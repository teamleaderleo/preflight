"""Render every hull at every angle into one PNG, for judging them against each other.

    python3 contact-sheet.py [-o sheet.png] [--cell 260] [--sprites [GAME]] [hull ...]

`--sprites` puts each hull's actual sprite in the first column, read live from an installation.
That column is the only thing that can tell you whether a hull is right -- comparing a render
against your own previous render just tells you what changed. **Its output is scratch and does
not go in the repository**, same rule as everywhere else here: the tracer reads an install, the
repository holds only the simplified contour.

The page is the renderer of record; this is a design aid. It reads the hull data straight out of
`hangar-light.html`, so the outlines and deck lanes can only ever be the page's own, and it ports
the page's `build()` and `project()` so the picture agrees with what the browser draws.

Why it exists: turning one ship at a time in a browser and remembering what the last one looked
like is how you get five hulls that are each defensible and do not look like a fleet. The faults
worth fixing -- a deck laid down the centreline of a ship that is not straight, a prow whose
trenches have been bridged over, the same nose cap on all of them -- are only visible in a row.
"""
import importlib.util
import json
import math
import re
import struct
import sys
import zlib
from pathlib import Path

DEFAULT_GAME = "/Applications/Starsector.app/Contents/Resources/Java"


def tracer():
    """trace-hulls.py already has a PNG decoder; load it rather than keeping a second one."""
    spec = importlib.util.spec_from_file_location(
        "trace_hulls", Path(__file__).with_name("trace-hulls.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod

PAGE = Path(__file__).with_name("hangar-light.html")
ORDER = ["odyssey", "onslaught", "conquest", "paragon", "astral", "hammerhead"]

# Same five cameras as the page's sheet. Plan view first: it is the only one the sprite vouches for.
# (label, yaw, pitch, turn). `turn` spins the finished picture 180 degrees in screen space so
# the bow points up. Getting there by yawing the camera round instead would also mirror the ship,
# which for four of these is invisible and for the Odyssey is a lie.
ANGLES = [
    ("Plan", 0.00, 1.42, True),
    ("Bow 3/4", -0.62, 0.62, False),
    ("Beam", -1.57, 0.30, False),
    ("Quarter", -2.44, 0.55, False),
    ("Ahead", 0.00, 0.10, True),
]

INK = (14, 12, 9)          # --sink, the stage ground
NEAR = (0xF2, 0xC9, 0x87)  # --wire-near
FAR = (0x6B, 0x54, 0x2E)   # --wire-far
GRID = (0x2E, 0x27, 0x1B)
GOLD = (0xE0, 0xA8, 0x4E)
RULE = (0x39, 0x30, 0x22)


# ---------------------------------------------------------------- hull data

def hulls():
    """Pull the HULLS table out of the page. The page is the single source of truth."""
    src = PAGE.read_text()
    at = src.index("var HULLS={")
    blk = src[at:src.index("\n  };", at)]
    out = {}
    for name in ORDER:
        seg = blk[blk.index(name + ":{"):]
        seg = re.sub(r"/\*.*?\*/", "", seg, flags=re.S)

        def arr(key):
            """Bracket-match rather than pattern-match: the arrays sit among hand-written
            comments and trailing commas, and every regex for them has broken at least once."""
            s = seg.index(key) + len(key) - 1
            depth, i = 0, s
            while True:
                if seg[i] == "[":
                    depth += 1
                elif seg[i] == "]":
                    depth -= 1
                    if depth == 0:
                        return json.loads(seg[s:i + 1])
                i += 1

        out[name] = {
            "name": re.search(r'name:"([^"]*)"', seg).group(1),
            "klass": re.search(r'klass:"([^"]*)"', seg).group(1).replace("&middot;", "·"),
            "thick": float(re.search(r"thick:([\d.]+)", seg).group(1)),
            "bells": int(re.search(r"bells:(\d+)", seg).group(1)),
            "o": arr("o:["),
            "holes": arr("holes:["),
            "decks": arr("decks:["),
        }
    return out


# ---------------------------------------------------------------- geometry
# A faithful port of the page's build(). Keep the two in step: when one changes, change both.

def build(hull):
    V, E = [], []

    def vert(x, y, z):
        V.append([x, y, z])
        return len(V) - 1

    o, TH = hull["o"], hull["thick"]
    wmax = max(abs(p[1]) for p in o)
    zmin = min(p[0] for p in o)
    zmax = max(p[0] for p in o)

    def half_height(z, x):
        along = (z - zmin) / (zmax - zmin)
        ends = math.sin(math.pi * min(1.0, max(0.0, along)))
        rim = 1 - min(1.0, abs(x) / wmax) ** 2.6
        return TH * (0.55 + 0.45 * ends) * (0.10 + 0.90 * rim)

    loops = [o] + hull["holes"]
    for li, loop in enumerate(loops):
        m = len(loop)
        mid = [vert(p[1], 0, p[0]) for p in loop]
        for i in range(m):
            E.append((mid[i], mid[(i + 1) % m]))
        deck_r, keel_r = [], []
        for p in loop:
            hh = half_height(p[0], p[1]) * (0.6 if li else 1)
            deck_r.append(vert(p[1], hh, p[0]))
            keel_r.append(vert(p[1], -hh * 0.72, p[0]))
        for i in range(m):
            j = (i + 1) % m
            E.append((deck_r[i], deck_r[j]))
            E.append((keel_r[i], keel_r[j]))
        # Verticals at the hull's corners, and nowhere else. One every nth vertex boxes the
        # whole rim into rectangles; none at all and the three rings read as contour lines on a
        # map rather than a solid. So they go where the outline actually turns -- the six
        # sharpest corners of the loop, which on these ships are the prow, the shoulders and
        # the transom, and which is where a real frame would be.
        turn = []
        for i in range(m):
            az, ax = loop[i - 1]
            bz, bx = loop[i]
            cz2, cx2 = loop[(i + 1) % m]
            u = math.atan2(bx - ax, bz - az)
            w = math.atan2(cx2 - bx, cz2 - bz)
            d = abs((w - u + math.pi) % (2 * math.pi) - math.pi)
            turn.append((d, i))
        picks, taken = [], []
        for d, i in sorted(turn, reverse=True):
            if all(min(abs(i - j), m - abs(i - j)) > m / 12 for j in taken):
                taken.append(i)
                picks.append(i)
            if len(picks) >= (9 if li == 0 else 4):
                break
        for i in picks:
            E.append((deck_r[i], mid[i]))
            E.append((mid[i], keel_r[i]))

    def crossings(z):
        xs = []
        for loop in loops:
            m = len(loop)
            for i in range(m):
                az, ax = loop[i]
                bz, bx = loop[i - 1]
                if (az > z) != (bz > z):
                    xs.append(ax + (bx - ax) * (z - az) / (bz - az))
        return sorted(xs)

    def inside(z, x):
        c = False
        for loop in loops:
            m = len(loop)
            for i in range(m):
                az, ax = loop[i]
                bz, bx = loop[i - 1]
                if (az > z) != (bz > z) and x < (bx - ax) * (z - az) / (bz - az) + ax:
                    c = not c
        return c

    def flank(z, mid_x):
        """Where the hull edge is at this z, on the lane's own side of the ship."""
        xs = crossings(z)
        for q in range(0, len(xs) - 1, 2):
            if xs[q] <= mid_x <= xs[q + 1]:
                return xs[q], xs[q + 1]
        return None

    for lane in hull["decks"]:
        port, stbd, live = [], [], []
        for st in lane:
            dz, lift, wid = st[0], st[1], st[2]
            mid_x = st[3] if len(st) > 3 else 0.0
            span = flank(dz, mid_x)
            if span is None:
                continue
            y = half_height(dz, mid_x) + TH * 0.95 * lift
            wl = min(wid, (mid_x - span[0]) * 0.9)
            wr = min(wid, (span[1] - mid_x) * 0.9)
            l = vert(mid_x - wl, y, dz)
            r = vert(mid_x + wr, y, dz)
            port.append(l); stbd.append(r)
            live.append((dz, mid_x, mid_x - wl, mid_x + wr))
        # The deck is one closed plate, not a ladder of crossbars: the two long edges plus a
        # cap at each end. A bar at every station fills the middle of the ship with rectangles,
        # and the plate reads as a plate from its outline alone.
        for k in range(len(live) - 1):
            mz = (live[k][0] + live[k + 1][0]) / 2
            if inside(mz, (live[k][2] + live[k + 1][2]) / 2):
                E.append((port[k], port[k + 1]))
            if inside(mz, (live[k][3] + live[k + 1][3]) / 2):
                E.append((stbd[k], stbd[k + 1]))
        # Caps at the ends of the plate and nothing between them. A bar at the beam was tried
        # and is a rule rather than an observation: no sprite has a feature there, it was put in
        # because a plate "should" be divided somewhere.
        for k in ({0, len(live) - 1}):
            if live and live[k][3] - live[k][2] > 1e-4:
                E.append((port[k], stbd[k]))

        # Brace the deck to the hull with raked struts, not with rungs. A strut square to
        # the keel is a ladder rung whatever you do to its length, and a deck tied to
        # nothing is an island floating in the middle of the ship -- this construction has
        # produced both. Each station throws one strut forward and one aft, landing on the
        # silhouette between itself and its neighbours, so the deck sits in a truss.
        #
        # What is held constant is the strut's ANGLE, not how far along the ship it reaches.
        # Reaching a fixed fraction of the gap to the next station means the strut's rake is
        # decided by how wide the ship happens to be there, and across an Onslaught's beam
        # that lies down flat and is a rung again. So the run aft is set from the run
        # outboard, then clipped so a strut cannot overshoot its neighbour.
        RAKE = 0.85
        n = len(live)
        for k in ([0, n - 1] if n > 1 else [0]):
            dz, mid_x = live[k][0], live[k][1]
            gap_f = (live[k - 1][0] - dz) if k else (dz - live[1][0] if n > 1 else 0.12)
            gap_a = (dz - live[k + 1][0]) if k + 1 < n else (live[n - 2][0] - dz if n > 1 else 0.12)
            here = flank(dz, mid_x)
            if here is None:
                continue
            for side, anchor, edge_x in ((0, port[k], live[k][2]), (1, stbd[k], live[k][3])):
                lateral = abs(here[side] - edge_x)
                for gap, sign in ((gap_f, 1), (gap_a, -1)):
                    tz = dz + sign * min(lateral * RAKE, abs(gap) * 0.72)
                    sp = flank(tz, mid_x)
                    if sp is None:
                        continue
                    E.append((anchor, vert(sp[side], half_height(tz, sp[side]), tz)))

    stern_half = max([abs(p[1]) for p in o if p[0] < zmin + (zmax - zmin) * 0.18] or [0])
    if stern_half <= 0:
        stern_half = wmax * 0.5
    bells = hull["bells"]
    spread = stern_half * 0.62
    for b in range(bells):
        bx = 0 if bells == 1 else -spread + 2 * spread * b / (bells - 1)
        r0 = min(stern_half / (bells * 1.25), 0.11)
        r1 = r0 * 1.25
        f, kk = [], []
        for sd in range(6):
            t = sd / 6 * math.pi * 2
            f.append(vert(bx + math.cos(t) * r0, math.sin(t) * r0 * 0.75, zmin + 0.06))
            kk.append(vert(bx + math.cos(t) * r1, math.sin(t) * r1 * 0.75, zmin - 0.10))
        for sd in range(6):
            E.append((f[sd], f[(sd + 1) % 6]))
            E.append((kk[sd], kk[(sd + 1) % 6]))
            E.append((f[sd], kk[sd]))

    lo = [min(v[a] for v in V) for a in range(3)]
    hi = [max(v[a] for v in V) for a in range(3)]
    mid_v = [(lo[a] + hi[a]) / 2 for a in range(3)]
    for v in V:
        for a in range(3):
            v[a] -= mid_v[a]
    ground = lo[1] - mid_v[1] - 0.16
    reach = max(hi[0] - lo[0], hi[2] - lo[2]) / 2
    return V, E, ground, (0.95 / reach if reach > 0 else 1)


def project(v, fit, W, H, cy, sy, cp, sp):
    vx, vy, vz = v[0] * fit, v[1] * fit, v[2] * fit
    x, z = vx * cy - vz * sy, vx * sy + vz * cy
    y, z = vy * cp - z * sp, vy * sp + z * cp
    d = 7.6
    s = d / (d - z)
    sc = min(W, H) * 0.50
    return W / 2 + x * s * sc, H / 2 - y * s * sc, z


# ---------------------------------------------------------------- raster

class Canvas:
    def __init__(self, w, h, bg):
        self.w, self.h = w, h
        self.px = bytearray(bytes(bg) * (w * h))

    def blend(self, x, y, rgb, a):
        if a <= 0 or not (0 <= x < self.w and 0 <= y < self.h):
            return
        a = min(1.0, a)
        i = (y * self.w + x) * 3
        for k in range(3):
            self.px[i + k] = int(self.px[i + k] + (rgb[k] - self.px[i + k]) * a)

    def line(self, x0, y0, x1, y1, rgb, alpha=1.0, width=1.0):
        """Xiaolin Wu, thickened by stamping parallel offsets."""
        n = max(1, int(round(width)))
        steep = abs(y1 - y0) > abs(x1 - x0)
        for k in range(n):
            off = k - (n - 1) / 2
            ax, ay = (x0, y0 + off) if steep is False else (x0 + off, y0)
            bx, by = (x1, y1 + off) if steep is False else (x1 + off, y1)
            self._wu(ax, ay, bx, by, rgb, alpha)

    def _wu(self, x0, y0, x1, y1, rgb, alpha):
        steep = abs(y1 - y0) > abs(x1 - x0)
        if steep:
            x0, y0, x1, y1 = y0, x0, y1, x1
        if x0 > x1:
            x0, x1, y0, y1 = x1, x0, y1, y0
        dx, dy = x1 - x0, y1 - y0
        if dx == 0 and dy == 0:
            return
        grad = dy / dx if dx else 1.0
        inter = y0 + grad * (round(x0) - x0)
        for x in range(int(round(x0)), int(round(x1)) + 1):
            f = inter - math.floor(inter)
            yi = int(math.floor(inter))
            if steep:
                self.blend(yi, x, rgb, alpha * (1 - f))
                self.blend(yi + 1, x, rgb, alpha * f)
            else:
                self.blend(x, yi, rgb, alpha * (1 - f))
                self.blend(x, yi + 1, rgb, alpha * f)
            inter += grad

    def blit(self, img, w, h, ox, oy, box):
        """Nearest-neighbour fit of an RGBA sprite into a box, composited over the ground."""
        k = min(box / w, box / h)
        dw, dh = max(1, int(w * k)), max(1, int(h * k))
        px, py = ox + (box - dw) // 2, oy + (box - dh) // 2
        for y in range(dh):
            sy = int(y / k)
            for x in range(dw):
                sx = int(x / k)
                i = (sy * w + sx) * 4
                a = img[i + 3] / 255
                if a > 0.02:
                    self.blend(px + x, py + y, (img[i], img[i + 1], img[i + 2]), a)

    def rect(self, x0, y0, x1, y1, rgb):
        for y in range(max(0, y0), min(self.h, y1)):
            for x in range(max(0, x0), min(self.w, x1)):
                self.blend(x, y, rgb, 1.0)

    def png(self, path):
        raw = bytearray()
        stride = self.w * 3
        for y in range(self.h):
            raw.append(0)
            raw += self.px[y * stride:(y + 1) * stride]

        def chunk(kind, data):
            c = struct.pack(">I", len(data)) + kind + data
            return c + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

        Path(path).write_bytes(
            b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", self.w, self.h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw), 6))
            + chunk(b"IEND", b"")
        )


# ------------------------------------------------------------- 5x7 label type

GLYPHS = {
    "A": ["01110", "10001", "10001", "11111", "10001", "10001", "10001"],
    "B": ["11110", "10001", "11110", "10001", "10001", "10001", "11110"],
    "C": ["01110", "10001", "10000", "10000", "10000", "10001", "01110"],
    "D": ["11110", "10001", "10001", "10001", "10001", "10001", "11110"],
    "E": ["11111", "10000", "11110", "10000", "10000", "10000", "11111"],
    "F": ["11111", "10000", "11110", "10000", "10000", "10000", "10000"],
    "G": ["01110", "10001", "10000", "10111", "10001", "10001", "01110"],
    "H": ["10001", "10001", "10001", "11111", "10001", "10001", "10001"],
    "I": ["11111", "00100", "00100", "00100", "00100", "00100", "11111"],
    "K": ["10001", "10010", "10100", "11000", "10100", "10010", "10001"],
    "L": ["10000", "10000", "10000", "10000", "10000", "10000", "11111"],
    "M": ["10001", "11011", "10101", "10101", "10001", "10001", "10001"],
    "N": ["10001", "11001", "10101", "10011", "10001", "10001", "10001"],
    "O": ["01110", "10001", "10001", "10001", "10001", "10001", "01110"],
    "P": ["11110", "10001", "10001", "11110", "10000", "10000", "10000"],
    "Q": ["01110", "10001", "10001", "10001", "10101", "10010", "01101"],
    "R": ["11110", "10001", "10001", "11110", "10100", "10010", "10001"],
    "S": ["01111", "10000", "10000", "01110", "00001", "00001", "11110"],
    "T": ["11111", "00100", "00100", "00100", "00100", "00100", "00100"],
    "U": ["10001", "10001", "10001", "10001", "10001", "10001", "01110"],
    "V": ["10001", "10001", "10001", "10001", "10001", "01010", "00100"],
    "W": ["10001", "10001", "10001", "10101", "10101", "11011", "10001"],
    "Y": ["10001", "10001", "01010", "00100", "00100", "00100", "00100"],
    "0": ["01110", "10001", "10011", "10101", "11001", "10001", "01110"],
    "1": ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    "2": ["01110", "10001", "00001", "00010", "00100", "01000", "11111"],
    "3": ["11110", "00001", "00001", "01110", "00001", "00001", "11110"],
    "4": ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
    "5": ["11111", "10000", "11110", "00001", "00001", "10001", "01110"],
    "6": ["00110", "01000", "10000", "11110", "10001", "10001", "01110"],
    "7": ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
    "8": ["01110", "10001", "10001", "01110", "10001", "10001", "01110"],
    "9": ["01110", "10001", "10001", "01111", "00001", "00010", "01100"],
    "/": ["00001", "00010", "00010", "00100", "01000", "01000", "10000"],
    " ": ["00000"] * 7,
}


def text(cv, s, x, y, rgb, scale=1, alpha=1.0):
    for ch in s.upper():
        rows = GLYPHS.get(ch)
        if rows is None:
            x += 6 * scale
            continue
        for ry, row in enumerate(rows):
            for rx, bit in enumerate(row):
                if bit == "1":
                    cv.rect(x + rx * scale, y + ry * scale,
                            x + (rx + 1) * scale, y + (ry + 1) * scale, rgb)
                    if alpha < 1:
                        pass
        x += (len(rows[0]) + 1) * scale
    return x


def relief(px, w, h):
    """Blur the sprite's own lighting, then posterise it, so what it raises reads as masses.

    Starsector's art is lit from straight above: a raised deck is bright, a well between two
    blocks is dark, and that is the only thing a flat sprite says about where a third axis
    would go. Posterising it as-is does not work -- the bands chase the greebling, which is
    high-frequency detail sitting at every tone. Blurring first at about a twentieth of the
    hull's width throws the rivets away and leaves the blocks, and four bands of that is a
    map of where a line is worth drawing at all.
    """
    lum = [0.0] * (w * h)
    on = [False] * (w * h)
    for i in range(w * h):
        if px[i * 4 + 3] > 16:
            on[i] = True
            lum[i] = (0.299 * px[i * 4] + 0.587 * px[i * 4 + 1] + 0.114 * px[i * 4 + 2]) / 255

    r = max(2, w // 20)
    # separable box blur over the hull only, so the transparent ground does not drag the
    # edges dark and invent a rim that the artwork does not have.
    def pass_(src, stride, count, length):
        dst = [0.0] * (w * h)
        for c in range(count):
            acc, num = 0.0, 0
            for t in range(length):
                i = c * (stride if stride == 1 else 1) + t * (1 if stride == 1 else w)
                i = (c * w + t) if stride == 1 else (t * w + c)
                if on[i]:
                    acc += src[i]
                    num += 1
                if t > 2 * r:
                    j = (c * w + t - 2 * r - 1) if stride == 1 else ((t - 2 * r - 1) * w + c)
                    if on[j]:
                        acc -= src[j]
                        num -= 1
                m = t - r
                if 0 <= m < length:
                    k = (c * w + m) if stride == 1 else (m * w + c)
                    dst[k] = acc / num if num else 0.0
        return dst

    blur = pass_(pass_(lum, 1, h, w), 0, w, h)
    vals = sorted(blur[i] for i in range(w * h) if on[i])
    if not vals:
        return bytearray(w * h * 4)
    lo, hi = vals[len(vals) // 50], vals[-len(vals) // 50 - 1]
    span = (hi - lo) or 1
    out = bytearray(w * h * 4)
    for i in range(w * h):
        if not on[i]:
            continue
        k = min(3, max(0, int((blur[i] - lo) / span * 4))) / 3
        out[i * 4] = int(FAR[0] + (NEAR[0] - FAR[0]) * k)
        out[i * 4 + 1] = int(FAR[1] + (NEAR[1] - FAR[1]) * k)
        out[i * 4 + 2] = int(FAR[2] + (NEAR[2] - FAR[2]) * k)
        out[i * 4 + 3] = 255
    return out


# ---------------------------------------------------------------- sheet

def draw_cell(cv, ox, oy, size, geom, yaw, pitch, turn):
    """Project, then fit what came out to the cell.

    Fitting in 3D is not enough on its own: build() scales a hull so its longest planar axis is
    a fixed reach, but a plan view maps that axis onto the short screen axis and the perspective
    divide magnifies whatever is nearest the camera, so a long hull walked straight out of its
    cell and into the row below. Measuring the projected points costs one pass and cannot be
    wrong about it.
    """
    V, E, ground, fit = geom
    cy, sy, cp, sp = math.cos(yaw), math.sin(yaw), math.cos(pitch), math.sin(pitch)
    W = H = size

    P = [project(v, fit, W, H, cy, sy, cp, sp) for v in V]
    xs = [p[0] for p in P]
    ys = [p[1] for p in P]
    span = max(max(xs) - min(xs), max(ys) - min(ys)) or 1
    k = size * 0.86 / span
    mx, my = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2

    def place(p):
        x, y = (p[0] - mx) * k, (p[1] - my) * k
        if turn:
            x, y = -x, -y
        return ox + size / 2 + x, oy + size / 2 + y

    P = [(place(p)[0], place(p)[1], p[2]) for p in P]

    gs = 1.25 / fit
    for g in range(-3, 4):
        t = g / 3 * gs
        for a, b in (([t, ground, -gs], [t, ground, gs]), ([-gs, ground, t], [gs, ground, t])):
            p1 = place(project(a, fit, W, H, cy, sy, cp, sp))
            p2 = place(project(b, fit, W, H, cy, sy, cp, sp))
            cv.line(ox + p1[0] - ox, oy + p1[1] - oy, p2[0], p2[1], GRID, 0.75, 1)

    order = sorted(range(len(E)), key=lambda i: (P[E[i][0]][2] + P[E[i][1]][2]) / 2)
    for i in order:
        a, b = P[E[i][0]], P[E[i][1]]
        depth = max(0.0, min(1.0, ((a[2] + b[2]) / 2 + 1.05) / 2.1))
        rgb = tuple(int(FAR[c] + (NEAR[c] - FAR[c]) * depth) for c in range(3))
        cv.line(a[0], a[1], b[0], b[1], rgb, 0.35 + depth * 0.65, 1)

    nose = P[0]
    for dy in range(-2, 3):
        for dx in range(-2, 3):
            if dx * dx + dy * dy <= 4:
                cv.blend(int(nose[0]) + dx, int(nose[1]) + dy, GOLD, 1.0)


def main(argv):
    out = "sheet.png"
    cell = 260
    game = None
    if "--sprites" in argv:
        i = argv.index("--sprites")
        rest = argv[i + 1:]
        if rest and not rest[0].startswith("-") and Path(rest[0], "data", "hulls").is_dir():
            game, argv = rest[0], argv[:i] + argv[i + 2:]
        else:
            game, argv = DEFAULT_GAME, argv[:i] + argv[i + 1:]
    if "-o" in argv:
        i = argv.index("-o")
        out, argv = argv[i + 1], argv[:i] + argv[i + 2:]
    if "--plan" in argv:
        # Sprite, relief and plan only, big. The three that can be compared like for like.
        argv.remove("--plan")
        del ANGLES[1:]
    if "--cell" in argv:
        i = argv.index("--cell")
        cell, argv = int(argv[i + 1]), argv[:i] + argv[i + 2:]

    data = hulls()
    names = [n for n in (argv or ORDER) if n in data]
    T = tracer() if game else None
    cols = (["Sprite", "Relief"] if game else []) + [a[0] for a in ANGLES]
    pad, head, side = 2, 22, 132
    W = side + len(cols) * (cell + pad) + pad
    H = head + len(names) * (cell + pad) + pad
    cv = Canvas(W, H, INK)

    for c, label in enumerate(cols):
        text(cv, label, side + c * (cell + pad) + 10, 8, GOLD, 2)
    cv.rect(0, head - 1, W, head, RULE)

    for r, name in enumerate(names):
        geom = build(data[name])
        oy = head + r * (cell + pad) + pad
        text(cv, data[name]["name"], 12, oy + 12, NEAR, 2)
        text(cv, "%d EDGES" % len(geom[1]), 12, oy + 30, FAR, 1)
        if r:
            cv.rect(0, oy - pad, W, oy - pad + 1, RULE)
        shift = 0
        if game:
            ship = T.load_ship(game, name)
            sw, sh, spx = T.read_png(f"{game}/{ship['spriteName']}")
            cv.blit(spx, sw, sh, side + pad, oy, cell)
            cv.blit(relief(spx, sw, sh), sw, sh, side + (cell + pad) + pad, oy, cell)
            shift = 2
        for c, (_, yaw, pitch, turn) in enumerate(ANGLES):
            ox = side + (c + shift) * (cell + pad) + pad
            cv.rect(ox - pad, oy, ox - pad + 1, oy + cell, RULE)
            draw_cell(cv, ox, oy, cell, geom, yaw, pitch, turn)
        print(f"{name:11} {len(geom[0]):5} verts  {len(geom[1]):5} edges  "
              f"{len(data[name]['decks'])} deck lane(s)")

    cv.png(out)
    print(f"wrote {out}  {W}x{H}")


main(sys.argv[1:])
