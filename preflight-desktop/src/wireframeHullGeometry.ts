import type { WireframeHull, WireframePoint } from "./types";

export interface HullVertex {
  x: number;
  y: number;
  z: number;
}

export interface HullSegment {
  from: HullVertex;
  to: HullVertex;
  kind: "outline" | "deck" | "keel" | "structure" | "interior" | "engine";
}

export interface ProjectedHull {
  segments: Array<{ from: WireframePoint; to: WireframePoint; kind: HullSegment["kind"] }>;
  deck: WireframePoint[];
  mounts: Array<WireframePoint & { size: "MEDIUM" | "LARGE" }>;
}

type HullDetail = "small" | "medium" | "showcase";

interface PreparedHull {
  segments: HullSegment[];
  deck: HullVertex[];
  mounts: Array<HullVertex & { size: "MEDIUM" | "LARGE" }>;
}

// A selected hull is immutable for the lifetime of the renderer. Its drafting topology is much
// more expensive than rotating that topology, so retain one model per display detail instead of
// rebuilding rings, the raised deck and nearest-outline braces on every animation frame.
const preparedHulls = new WeakMap<WireframeHull, Map<HullDetail, PreparedHull>>();

function ring(points: HullVertex[], kind: HullSegment["kind"]): HullSegment[] {
  return points.map((point, index) => ({
    from: point,
    to: points[(index + 1) % points.length],
    kind,
  }));
}

function scalePoint(point: WireframePoint, center: WireframePoint, xScale: number, yScale: number, z: number): HullVertex {
  return {
    x: center.x + (point.x - center.x) * xScale,
    y: center.y + (point.y - center.y) * yScale,
    z,
  };
}

function hullFrame(hull: WireframeHull) {
  const center = hull.bounds.reduce(
    (sum, point) => ({ x: sum.x + point.x / hull.bounds.length, y: sum.y + point.y / hull.bounds.length }),
    { x: 0, y: 0 },
  );
  const minX = Math.min(...hull.bounds.map((point) => point.x));
  const maxX = Math.max(...hull.bounds.map((point) => point.x));
  const minY = Math.min(...hull.bounds.map((point) => point.y));
  const maxY = Math.max(...hull.bounds.map((point) => point.y));
  const extent = Math.max(maxX - minX, maxY - minY, 1);
  return { center, minX, maxX, minY, maxY, extent };
}

/*
 * ---------------------------------------------------------------------------------------------
 * Shipped hulls, traced inside and out.
 *
 * This is the design page's own construction, ported. `docs/design/hangar-light/` holds the
 * prototype it came from, its reasoning, and the list of approaches that are wrong -- read that
 * before changing anything here, because most of the obvious ideas have already been tried.
 *
 * The short version: every closed loop is drawn the same way. The silhouette, the voids punched
 * through it and the raised interior blocks are all just loops, so a Paragon's hole needs no
 * special case. Verticals go where the outline actually turns, and interior blocks stand on four
 * short legs of their own rather than reaching across the ship for the silhouette -- that last
 * one was rediscovered under four different names before it stayed dead.
 */

/** Simplification tolerances, as a fraction of the hull's extent. The page's defaults, accepted. */
const OUTER_DETAIL = 0.012;
const INNER_DETAIL = 0.016;
/** Smallest closed shape worth drawing, as a fraction of the hull's plan area. */
const OUTER_MINIMUM = 0.004;
const INNER_MINIMUM = 0.012;

/** Shoelace, for dropping shapes below a size. */
function polygonArea(loop: WireframePoint[]): number {
  let total = 0;
  for (let index = 0, previous = loop.length - 1; index < loop.length; previous = index, index += 1) {
    total += loop[previous].x * loop[index].y - loop[index].x * loop[previous].y;
  }
  return Math.abs(total / 2);
}

/**
 * Douglas-Peucker over the open walk, deliberately not the closed ring: handing it a loop whose
 * last point repeats its first gives a zero-length baseline, every distance measures zero, and it
 * returns two points at any tolerance at all. The tracer feeds it the same way.
 */
function simplify(loop: WireframePoint[], tolerance: number): WireframePoint[] {
  if (loop.length < 3) return loop;
  const first = loop[0];
  const last = loop[loop.length - 1];
  const runX = last.x - first.x;
  const runY = last.y - first.y;
  const norm = Math.hypot(runX, runY) || 1;
  let worst = -1;
  let at = 0;
  for (let index = 1; index < loop.length - 1; index += 1) {
    const point = loop[index];
    const distance = Math.abs(
      runY * point.x - runX * point.y + last.x * first.y - last.y * first.x,
    ) / norm;
    if (distance > worst) {
      worst = distance;
      at = index;
    }
  }
  if (worst <= tolerance) return [first, last];
  return [
    ...simplify(loop.slice(0, at + 1), tolerance).slice(0, -1),
    ...simplify(loop.slice(at), tolerance),
  ];
}

/** Simplify, but never below the point where the shape stops being one. */
function thin(loop: WireframePoint[], tolerance: number, floor: number): WireframePoint[] {
  const thinned = simplify(loop, tolerance);
  return thinned.length >= floor ? thinned : loop;
}

/**
 * Verticals at the hull's corners, and nowhere else. One every nth vertex boxes the whole rim
 * into rectangles; none at all and the three rings read as contour lines on a map rather than a
 * solid. So they go where the outline turns hardest -- on these ships the prow, the shoulders and
 * the transom, which is where a real frame would be.
 */
function corners(loop: WireframePoint[], wanted: number): number[] {
  const turns = loop.map((point, index) => {
    const previous = loop[(index + loop.length - 1) % loop.length];
    const next = loop[(index + 1) % loop.length];
    const incoming = Math.atan2(point.y - previous.y, point.x - previous.x);
    const outgoing = Math.atan2(next.y - point.y, next.x - point.x);
    const swing = ((outgoing - incoming + Math.PI) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI) - Math.PI;
    return { index, turn: Math.abs(swing) };
  }).sort((left, right) => right.turn - left.turn);

  const taken: number[] = [];
  for (const candidate of turns) {
    if (taken.length >= wanted) break;
    const clear = taken.every((index) => {
      const gap = Math.abs(index - candidate.index);
      return Math.min(gap, loop.length - gap) > loop.length / 12;
    });
    if (clear) taken.push(candidate.index);
  }
  return taken;
}

function buildCuratedHull(hull: WireframeHull, detail: HullDetail): PreparedHull {
  const geometry = hull.curated;
  if (!geometry) return { segments: [], deck: [], mounts: [] };
  const { minX, maxX, minY, maxY, extent } = hullFrame(hull);
  /*
   * The centreline is the midpoint of the hull's own extent, not the mean of its vertices. The
   * exported hulls are normalised about that midpoint, and the vertex mean is not the same thing
   * -- it drifts toward whichever flank carries more traced points, which on the Astral is 6% of
   * half-width and tilts the rim falloff with it. `hullFrame` keeps the mean for scanned hulls,
   * which have no such guarantee.
   */
  const axis = (minY + maxY) / 2;
  const outline = thin(hull.bounds, OUTER_DETAIL * extent / 2, 12);
  const hullArea = polygonArea(outline);
  const thickness = geometry.thickness * extent / 2;
  const halfWidth = Math.max(...outline.map((point) => Math.abs(point.y - axis)), 1);

  /*
   * Half-thickness at a point. These are flat plates, not zeppelins: a gentle curve over the whole
   * body reads as bulbous, so the body stays nearly flat and the rim falls away fast, leaving a
   * knife edge. The vertical interest comes from the interior blocks, not from inflating the hull.
   */
  const halfHeight = (point: WireframePoint) => {
    const along = Math.min(1, Math.max(0, (point.x - minX) / Math.max(maxX - minX, 1)));
    const ends = Math.sin(Math.PI * along);
    const rim = 1 - Math.min(1, Math.abs(point.y - axis) / halfWidth) ** 2.6;
    return thickness * (0.55 + 0.45 * ends) * (0.1 + 0.9 * rim);
  };

  const segments: HullSegment[] = [];
  let deck: HullVertex[] = [];
  const loops = [outline, ...geometry.holes
    .filter((void_) => polygonArea(void_) >= OUTER_MINIMUM * hullArea)
    .map((void_) => thin(void_, OUTER_DETAIL * extent / 2, 6))];

  loops.forEach((loop, index) => {
    const isVoid = index > 0;
    const middle = loop.map((point) => ({ ...point, z: 0 }));
    // Deck and keel follow the outline point for point. Sampling them every nth vertex chords
    // straight across the Onslaught's prow slots: a notch is exactly the feature a decimated ring
    // loses, and the line it draws instead runs through the empty space the notch is for.
    const upper = loop.map((point) => ({ ...point, z: halfHeight(point) * (isVoid ? 0.6 : 1) }));
    const lower = loop.map((point) => ({ ...point, z: -halfHeight(point) * (isVoid ? 0.6 : 1) * 0.72 }));
    if (!isVoid) deck = upper;

    segments.push(...ring(middle, "outline"), ...ring(upper, "deck"));
    if (detail !== "small") segments.push(...ring(lower, "keel"));

    const wanted = isVoid ? 4 : detail === "small" ? 4 : detail === "medium" ? 7 : 9;
    for (const at of corners(loop, wanted)) {
      segments.push({ from: middle[at], to: upper[at], kind: "structure" });
      if (detail !== "small") segments.push({ from: middle[at], to: lower[at], kind: "structure" });
    }
  });

  /*
   * The inside of the ship, traced the same way as the outside: each tier is a closed contour of
   * what the sprite lights above a threshold. The silhouette is the boundary between hull and
   * space; these are the boundaries between one raised block and the next, and they are what makes
   * a ship recognisable at a glance. Dropped at thumbnail size, where they only muddy the outline.
   */
  if (detail !== "small") {
    for (const tier of geometry.inner) {
      if (polygonArea(tier.points) < INNER_MINIMUM * hullArea) continue;
      const loop = thin(tier.points, INNER_DETAIL * extent / 2, 6);
      if (loop.length < 3) continue;
      const raised = loop.map((point) => ({
        ...point,
        z: halfHeight(point) + thickness * 0.95 * tier.height,
      }));
      segments.push(...ring(raised, "interior"));
      // Legs at the shape's four compass extremes, down to the hull under them. Enough to stand
      // it up, few enough that the block keeps its own outline.
      const extremes = [
        loop.reduce((best, point, index) => (point.x < loop[best].x ? index : best), 0),
        loop.reduce((best, point, index) => (point.x > loop[best].x ? index : best), 0),
        loop.reduce((best, point, index) => (point.y < loop[best].y ? index : best), 0),
        loop.reduce((best, point, index) => (point.y > loop[best].y ? index : best), 0),
      ];
      for (const at of new Set(extremes)) {
        segments.push({
          from: raised[at],
          to: { ...loop[at], z: halfHeight(loop[at]) },
          kind: "structure",
        });
      }
    }
  }

  /*
   * Engine bells. The traced silhouette stops at the transom, so the drives are the one piece of a
   * shipped hull that is constructed rather than traced -- a stack of hexagonal rings behind the
   * stern, sized off the stern's own width so a Hammerhead's two do not read like a Paragon's.
   */
  const sternWidth = outline.reduce((widest, point) => (
    point.x < minX + (maxX - minX) * 0.18 ? Math.max(widest, Math.abs(point.y - axis)) : widest
  ), 0) || halfWidth * 0.5;
  const spread = sternWidth * 0.62;
  const radius = Math.min(sternWidth / (geometry.engineBells * 1.25), extent * 0.055);
  const bellRing = (count: number, x: number, y: number, scale: number) => Array.from({ length: count }, (_, side) => {
    const angle = side / count * Math.PI * 2;
    return { x, y: y + Math.cos(angle) * radius * scale, z: Math.sin(angle) * radius * scale * 0.75 };
  });
  for (let bell = 0; bell < geometry.engineBells; bell += 1) {
    const lateral = geometry.engineBells === 1
      ? axis
      : axis - spread + 2 * spread * bell / (geometry.engineBells - 1);
    const mouth = bellRing(6, minX + extent * 0.03, lateral, 1);
    const flare = bellRing(6, minX - extent * 0.05, lateral, 1.25);
    segments.push(...ring(mouth, "engine"), ...ring(flare, "engine"));
    mouth.forEach((point, index) => segments.push({ from: point, to: flare[index], kind: "engine" }));
  }

  return { segments, deck, mounts: [] };
}

/** A shared raised cabin keeps the source silhouette legible instead of duplicating every notch. */
function buildDeck(hull: WireframeHull): HullVertex[] {
  const { center, minX, maxX, minY, maxY, extent } = hullFrame(hull);
  const length = Math.max(maxX - minX, 1);
  const halfWidth = Math.max(maxY - minY, 1) / 2;
  const z = extent * 0.16;
  return [
    { x: maxX - length * 0.1, y: center.y, z },
    { x: maxX - length * 0.3, y: center.y + halfWidth * 0.36, z },
    { x: minX + length * 0.34, y: center.y + halfWidth * 0.29, z },
    { x: minX + length * 0.18, y: center.y, z },
    { x: minX + length * 0.34, y: center.y - halfWidth * 0.29, z },
    { x: maxX - length * 0.3, y: center.y - halfWidth * 0.36, z },
  ];
}

/**
 * Turns Starsector's authoritative plan-view silhouette into a restrained drafting model.
 * The deck and keel are deliberately generic: Preflight doesn't claim the flat source defines a
 * canon third axis, and one shared loft keeps mod hulls from needing hand-authored meshes.
 */
function buildScannedHullSegments(hull: WireframeHull, detail: HullDetail): HullSegment[] {
  if (hull.bounds.length < 3) return [];
  const { center, extent } = hullFrame(hull);
  const deckHeight = extent * 0.17;
  const outline = hull.bounds.map((point) => ({ ...point, z: 0 }));
  const deck = buildDeck(hull);
  const keel = hull.bounds.map((point) => scalePoint(point, center, 0.9, 0.82, -deckHeight * 0.42));
  const structure = deck.map((point) => {
    const nearest = outline.reduce((best, candidate) => {
      const distance = (candidate.x - point.x) ** 2 + (candidate.y - point.y) ** 2;
      const bestDistance = (best.x - point.x) ** 2 + (best.y - point.y) ** 2;
      return distance < bestDistance ? candidate : best;
    });
    return {
      from: nearest,
      to: point,
      kind: "structure" as const,
    };
  });
  const segments = [
    ...ring(outline, "outline"),
    ...ring(deck, "deck"),
    ...(detail === "small" ? [] : ring(keel, "keel")),
    ...structure,
  ];

  for (const engine of hull.engines) {
    const angle = engine.angle * Math.PI / 180;
    const direction = { x: Math.cos(angle), y: Math.sin(angle) };
    const normal = { x: -direction.y, y: direction.x };
    const half = Math.min(engine.width, extent * 0.16) / 2;
    const length = Math.min(engine.length, extent * 0.22);
    const left = { x: engine.x + normal.x * half, y: engine.y + normal.y * half, z: 0 };
    const right = { x: engine.x - normal.x * half, y: engine.y - normal.y * half, z: 0 };
    const tail = { x: engine.x + direction.x * length, y: engine.y + direction.y * length, z: 0 };
    segments.push(
      { from: left, to: right, kind: "engine" },
      { from: left, to: tail, kind: "engine" },
      { from: right, to: tail, kind: "engine" },
    );
  }
  return segments;
}

/**
 * A shipped hull carries its own traced interior; a scanned one gets the shared generic loft.
 * Both end up as the same list of segments, so nothing downstream knows which it is holding.
 */
export function buildHullSegments(hull: WireframeHull, detail: HullDetail): HullSegment[] {
  return hull.curated ? buildCuratedHull(hull, detail).segments : buildScannedHullSegments(hull, detail);
}

function prepareHull(hull: WireframeHull, detail: HullDetail): PreparedHull {
  const cached = preparedHulls.get(hull)?.get(detail);
  if (cached) return cached;

  const { extent } = hullFrame(hull);
  const prepared = hull.curated ? buildCuratedHull(hull, detail) : {
    segments: buildScannedHullSegments(hull, detail),
    deck: buildDeck(hull),
    mounts: detail === "small" ? [] : hull.mounts.map((mount) => ({
      x: mount.x,
      y: mount.y,
      z: extent * 0.18,
      size: mount.size,
    })),
  } satisfies PreparedHull;
  let details = preparedHulls.get(hull);
  if (!details) {
    details = new Map();
    preparedHulls.set(hull, details);
  }
  details.set(detail, prepared);
  return prepared;
}

function project(vertex: HullVertex, yaw: number): WireframePoint {
  const cosine = Math.cos(yaw);
  const sine = Math.sin(yaw);
  const forward = vertex.x * cosine - vertex.y * sine;
  const lateral = vertex.x * sine + vertex.y * cosine;
  return {
    x: lateral,
    y: -forward * 0.62 - vertex.z,
  };
}

export function projectHull(hull: WireframeHull, yaw: number, detail: HullDetail): ProjectedHull {
  const prepared = prepareHull(hull, detail);
  const segments = prepared.segments.map((segment) => ({
    from: project(segment.from, yaw),
    to: project(segment.to, yaw),
    kind: segment.kind,
  }));
  const deck = prepared.deck.map((point) => project(point, yaw));
  const mounts = prepared.mounts.map((mount) => ({
    ...project(mount, yaw),
    size: mount.size,
  }));
  return { segments, deck, mounts };
}
