import type { WireframeHull, WireframePoint, WireframeTuning } from "./types";

export interface HullVertex {
  x: number;
  y: number;
  z: number;
}

export type HullSegmentKind = "outline" | "deck" | "keel" | "structure" | "engine";

export interface HullSegment {
  from: HullVertex;
  to: HullVertex;
  kind: HullSegmentKind;
}

/** A projected point carries its view depth, which is what shades the wireframe. */
export interface ProjectedPoint extends WireframePoint {
  depth: number;
}

export interface ProjectedHull {
  segments: Array<{ from: ProjectedPoint; to: ProjectedPoint; kind: HullSegment["kind"] }>;
  deck: ProjectedPoint[];
  mounts: Array<ProjectedPoint & { size: "MEDIUM" | "LARGE" }>;
  /** The reference grid on the keel plane, projected with everything else. */
  ground: Array<{ from: ProjectedPoint; to: ProjectedPoint }>;
  /** The bow, so the one bright marker knows where to sit. */
  nose: ProjectedPoint | null;
}

type HullDetail = "small" | "medium" | "showcase";

/**
 * The camera, copied from the prototype rather than re-derived.
 *
 * Pitched well over toward plan view on purpose: the silhouette is the only faithful part of any
 * of this, so the camera favours it. The sign is the whole ballgame -- the camera sits above, so
 * after the pitch a deck point lands higher than the keel under it. Negative parks the camera
 * under the hull looking up through the grid.
 */
const PITCH = 0.62;
/** Eye distance for the perspective divide, in the normalised frame `fit` maps every hull into. */
const EYE = 7.6;
/** Half-extent every hull's longest planar axis is fitted to, so one EYE frames all six. */
const REACH = 0.95;

/** What a builder produces: the topology, before the camera knows anything about it. */
interface HullModel {
  segments: HullSegment[];
  deck: HullVertex[];
  mounts: Array<HullVertex & { size: "MEDIUM" | "LARGE" }>;
}

interface PreparedHull extends HullModel {
  /** Scale into the normalised frame, and the hull's own middle, so perspective stays even. */
  fit: number;
  centre: HullVertex;
  /** Where the keel plane sits, for the ground grid to be drawn on. */
  groundZ: number;
  nose: HullVertex | null;
}

// A selected hull is immutable for the lifetime of the renderer. Its drafting topology is much
// more expensive than rotating that topology, so retain one model per display detail instead of
// rebuilding rings, the raised deck and nearest-outline braces on every animation frame.
const preparedHulls = new WeakMap<WireframeHull, Map<HullDetail, PreparedHull>>();

/*
 * The design page's settled dials, by value.
 *
 * A traced contour arrives with a point for nearly every turn the sprite's edge makes, and drawn
 * raw it reads as a contour map rather than a ship: the Astral came to 830 edges against the
 * page's 587, and looked busier for them. The page answers this with one simplification pass in
 * the renderer, per loop family, at a tolerance that is a fraction of the hull's own size -- so a
 * hull lands where its own complexity puts it rather than inside a shared point budget.
 */
export const DEFAULT_WIREFRAME_TUNING: Readonly<WireframeTuning> = Object.freeze({
  outerDetail: 0.012,
  outerSmooth: 0,
  innerDetail: 0.016,
  innerSmooth: 0,
  height: 1,
});

/** Below these a loop has stopped being a shape, so a tolerance that would is refused instead. */
const OUTLINE_FLOOR = 12;
const LOOP_FLOOR = 6;

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

function polygonArea(loop: WireframePoint[]): number {
  let total = 0;
  for (let index = 0, previous = loop.length - 1; index < loop.length; previous = index, index += 1) {
    total += loop[previous].x * loop[index].y - loop[index].x * loop[previous].y;
  }
  return Math.abs(total / 2);
}

/** Put the few verticals where the contour actually turns instead of making a ladder. */
/** How hard the loop turns at a vertex, in radians, which is what makes a corner a corner. */
function turnAt(loop: WireframePoint[], index: number): number {
  const point = loop[index];
  const previous = loop[(index + loop.length - 1) % loop.length];
  const next = loop[(index + 1) % loop.length];
  const incoming = Math.atan2(point.y - previous.y, point.x - previous.x);
  const outgoing = Math.atan2(next.y - point.y, next.x - point.x);
  return Math.abs(Math.atan2(Math.sin(outgoing - incoming), Math.cos(outgoing - incoming)));
}

export interface SideStation {
  waterline: HullVertex;
  deck: HullVertex;
  keel: HullVertex;
}

/**
 * Evenly spaced stations around a loop, derived directly by interpolating along the 3D ring segments.
 *
 * Interpolating between the upper (deck), middle (waterline), and lower (keel) ring vertices ensures
 * that each facet station endpoint lies exactly on the straight 3D ring segment it joins, rather than
 * deviating vertically through a nonlinear height function on sparse contour runs.
 */
export function sideStations(
  middle: HullVertex[],
  upper: HullVertex[],
  lower: HullVertex[],
  wanted: number,
): SideStation[] {
  if (middle.length < 3 || wanted < 3) return [];
  const walked = [0];
  for (let index = 1; index <= middle.length; index += 1) {
    const previous = middle[index - 1];
    const point = middle[index % middle.length];
    walked.push(walked[index - 1] + Math.hypot(point.x - previous.x, point.y - previous.y));
  }
  const perimeter = walked[middle.length];
  if (perimeter <= 0) return [];

  const stations: SideStation[] = [];
  let cursor = 0;
  for (let step = 0; step < wanted; step += 1) {
    const target = step * perimeter / wanted;
    while (cursor < middle.length - 1 && walked[cursor + 1] <= target) cursor += 1;
    const nextIdx = (cursor + 1) % middle.length;
    const span = walked[cursor + 1] - walked[cursor];
    const along = span > 0 ? (target - walked[cursor]) / span : 0;

    const interp = (from: HullVertex, to: HullVertex): HullVertex => ({
      x: from.x + (to.x - from.x) * along,
      y: from.y + (to.y - from.y) * along,
      z: from.z + (to.z - from.z) * along,
    });

    stations.push({
      waterline: interp(middle[cursor], middle[nextIdx]),
      deck: interp(upper[cursor], upper[nextIdx]),
      keel: interp(lower[cursor], lower[nextIdx]),
    });
  }
  return stations;
}


function contourCorners(loop: WireframePoint[], wanted: number): number[] {
  const turns = loop
    .map((_, index) => ({ index, turn: turnAt(loop, index) }))
    .sort((left, right) => right.turn - left.turn);

  const selected: number[] = [];
  for (const candidate of turns) {
    if (selected.length >= wanted) break;
    const separated = selected.every((index) => {
      const distance = Math.abs(index - candidate.index);
      return Math.min(distance, loop.length - distance) > loop.length / 12;
    });
    if (separated) selected.push(candidate.index);
  }
  return selected;
}

function smoothContour(points: WireframePoint[], weight: number): WireframePoint[] {
  let current = points;
  if (weight <= 0) return current;
  for (let pass = 0; pass < 2; pass += 1) {
    current = current.map((point, index) => {
      const previous = current[(index + current.length - 1) % current.length];
      const next = current[(index + 1) % current.length];
      return {
        x: point.x * (1 - weight) + (previous.x + next.x) / 2 * weight,
        y: point.y * (1 - weight) + (previous.y + next.y) / 2 * weight,
      };
    });
  }
  return current;
}

function simplifyContour(points: WireframePoint[], epsilon: number): WireframePoint[] {
  if (points.length < 3 || epsilon <= 0) return points;
  const first = points[0];
  const last = points[points.length - 1];
  const dx = last.x - first.x;
  const dy = last.y - first.y;
  const norm = Math.hypot(dx, dy) || 1;
  let furthestDistance = -1;
  let furthestIndex = 0;
  for (let index = 1; index < points.length - 1; index += 1) {
    const point = points[index];
    const distance = Math.abs(dy * point.x - dx * point.y + last.x * first.y - last.y * first.x) / norm;
    if (distance > furthestDistance) {
      furthestDistance = distance;
      furthestIndex = index;
    }
  }
  if (furthestDistance <= epsilon) return [first, last];
  return [
    ...simplifyContour(points.slice(0, furthestIndex + 1), epsilon).slice(0, -1),
    ...simplifyContour(points.slice(furthestIndex), epsilon),
  ];
}

/** Smooth, then simplify, then refuse the result if it has stopped being a shape. */
function shapeContour(
  points: WireframePoint[],
  smooth: number,
  epsilon: number,
  floor: number,
): WireframePoint[] {
  const smoothed = smoothContour(points, smooth);
  const simplified = simplifyContour(smoothed, epsilon);
  return simplified.length >= floor ? simplified : smoothed;
}

function tunedHull(hull: WireframeHull): WireframeHull {
  if (hull.bounds.length < 3) return hull;
  const tuning = hull.tuning ?? DEFAULT_WIREFRAME_TUNING;
  const { extent } = hullFrame(hull);
  // The dials are fractions of the hull's own size; these points are in the source's units, so
  // the tolerance is scaled by the extent before it means the same thing on a frigate and a
  // capital. Smoothing then simplifying is not the same as simplifying harder: one softens the
  // shape and keeps its extent, the other keeps the shape and drops its middles.
  const outer = tuning.outerDetail * extent / 2;
  const inner = tuning.innerDetail * extent / 2;
  const bounds = shapeContour(hull.bounds, tuning.outerSmooth, outer, OUTLINE_FLOOR);
  // The voids belong to the outer family: they are marched off the same alpha as the silhouette.
  const trace = hull.trace && {
    holes: hull.trace.holes.map((hole) => shapeContour(hole, tuning.outerSmooth, outer, LOOP_FLOOR)),
    inner: hull.trace.inner.map((contour) => ({
      ...contour,
      points: shapeContour(contour.points, tuning.innerSmooth, inner, LOOP_FLOOR),
    })),
  };
  if (bounds === hull.bounds && !trace) return hull;
  return { ...hull, bounds, ...(trace ? { trace } : {}) };
}

/** A shared raised cabin keeps the source silhouette legible instead of duplicating every notch. */
function buildDeck(hull: WireframeHull): HullVertex[] {
  const { minX, maxX, minY, maxY, extent } = hullFrame(hull);
  const midY = (minY + maxY) / 2;
  const length = Math.max(maxX - minX, 1);
  const halfWidth = Math.max(maxY - minY, 1) / 2;
  const z = extent * 0.16 * (hull.tuning?.height ?? 1);
  return [
    { x: maxX - length * 0.1, y: midY, z },
    { x: maxX - length * 0.3, y: midY + halfWidth * 0.36, z },
    { x: minX + length * 0.34, y: midY + halfWidth * 0.29, z },
    { x: minX + length * 0.18, y: midY, z },
    { x: minX + length * 0.34, y: midY - halfWidth * 0.29, z },
    { x: maxX - length * 0.3, y: midY - halfWidth * 0.36, z },
  ];
}

/**
 * A readable plate from the sprite-derived silhouette, voids and light contours.
 *
 * This is deliberately separate from the generic collision-bound loft below. A trace already
 * says where the raised shapes are. Adding the generic six-sided cabin on top of it creates a
 * second, unrelated ship in the middle of the first one.
 */
function buildTracedHull(hull: WireframeHull, detail: HullDetail): HullModel {
  const trace = hull.trace;
  if (!trace || hull.bounds.length < 3) return { segments: [], deck: [], mounts: [] };
  const { minX, maxX, minY, maxY, extent } = hullFrame(hull);
  const axis = (minY + maxY) / 2;
  const halfWidth = Math.max(...hull.bounds.map((point) => Math.abs(point.y - axis)), 1);
  const height = hull.tuning?.height ?? 1;
  const thickness = extent * 0.085 * height;
  const hullArea = polygonArea(hull.bounds);
  const halfHeight = (point: WireframePoint) => {
    const along = Math.min(1, Math.max(0, (point.x - minX) / Math.max(maxX - minX, 1)));
    const ends = Math.sin(Math.PI * along);
    const rim = 1 - Math.min(1, Math.abs(point.y - axis) / halfWidth) ** 2.6;
    return thickness * (0.55 + 0.45 * ends) * (0.1 + 0.9 * rim);
  };

  const segments: HullSegment[] = [];
  const minimumVoidArea = hullArea * 0.004;
  const loops = [hull.bounds, ...trace.holes.filter((loop) => polygonArea(loop) >= minimumVoidArea)];
  loops.forEach((loop, index) => {
    const isVoid = index > 0;
    const middle = loop.map((point) => ({ ...point, z: 0 }));
    const upper = loop.map((point) => ({ ...point, z: halfHeight(point) * (isVoid ? 0.62 : 1) }));
    const lower = loop.map((point) => ({ ...point, z: -halfHeight(point) * (isVoid ? 0.62 : 1) * 0.72 }));
    segments.push(...ring(middle, "outline"), ...ring(upper, "deck"));
    if (detail === "small") {
      for (const at of contourCorners(loop, 4)) {
        segments.push({ from: middle[at], to: upper[at], kind: "structure" });
      }
      return;
    }
    segments.push(...ring(lower, "keel"));
    /*
     * The side is a band of triangles, not a ladder between two loose rings.
     *
     * Three parallel contours tied together by vertical struts read as a contour map, because
     * nothing in the picture says the space between two rings is a surface. One diagonal per
     * station closes each quad into two triangles, and the eye takes a triangulated band as skin.
     * The diagonals lean opposite ways above and below the waterline so the two bands read as one
     * truss rather than two sets of parallel slashes.
     */
    const stations = sideStations(
      middle,
      upper,
      lower,
      isVoid ? 8 : detail === "medium" ? 18 : 28,
    );
    stations.forEach((station, index) => {
      const next = stations[(index + 1) % stations.length];
      segments.push(
        { from: station.waterline, to: station.deck, kind: "structure" },
        { from: station.waterline, to: next.deck, kind: "structure" },
        { from: station.waterline, to: station.keel, kind: "structure" },
        { from: station.keel, to: next.waterline, kind: "structure" },
      );
    });

  });

  if (detail !== "small") {
    const minimumInnerArea = hullArea * 0.012;
    for (const contour of trace.inner) {
      if (contour.points.length < 3 || polygonArea(contour.points) < minimumInnerArea) continue;
      const raised = contour.points.map((point) => ({
        ...point,
        z: halfHeight(point) + thickness * 0.95 * contour.height,
      }));
      segments.push(...ring(raised, "deck"));
      const extremes = [
        contour.points.reduce((best, point, index) => point.x < contour.points[best].x ? index : best, 0),
        contour.points.reduce((best, point, index) => point.x > contour.points[best].x ? index : best, 0),
        contour.points.reduce((best, point, index) => point.y < contour.points[best].y ? index : best, 0),
        contour.points.reduce((best, point, index) => point.y > contour.points[best].y ? index : best, 0),
      ];
      for (const at of new Set(extremes)) {
        segments.push({
          from: raised[at],
          to: { ...contour.points[at], z: halfHeight(contour.points[at]) },
          kind: "structure",
        });
      }
    }
  }

  // A single Canvas fill path cannot preserve the trace's cutouts. Keep traced hulls honest and
  // let their layered contours carry the form; the generic fallback below still has a solid deck.
  return { segments, deck: [], mounts: [] };
}

/**
 * Turns Starsector's authoritative plan-view silhouette into a restrained drafting model.
 * The deck and keel are deliberately generic: Preflight doesn't claim the flat source defines a
 * canon third axis, and one shared loft keeps mod hulls from needing hand-authored meshes.
 */
function buildSegments(hull: WireframeHull, detail: HullDetail): HullSegment[] {
  if (hull.bounds.length < 3) return [];
  const { minX, maxX, minY, maxY, extent } = hullFrame(hull);
  const midpoint = { x: (minX + maxX) / 2, y: (minY + maxY) / 2 };
  const deckHeight = extent * 0.17 * (hull.tuning?.height ?? 1);
  const outline = hull.bounds.map((point) => ({ ...point, z: 0 }));
  const deck = buildDeck(hull);
  const keel = hull.bounds.map((point) => scalePoint(point, midpoint, 0.9, 0.82, -deckHeight * 0.42));
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

export function buildHullSegments(hull: WireframeHull, detail: HullDetail): HullSegment[] {
  const effectiveHull = tunedHull(hull);
  return effectiveHull.trace
    ? buildTracedHull(effectiveHull, detail).segments
    : buildSegments(effectiveHull, detail);
}

function prepareHull(hull: WireframeHull, detail: HullDetail): PreparedHull {
  const cached = preparedHulls.get(hull)?.get(detail);
  if (cached) return cached;

  const effectiveHull = tunedHull(hull);
  const { extent } = hullFrame(effectiveHull);
  const built = effectiveHull.trace
    ? buildTracedHull(effectiveHull, detail)
    : {
        segments: buildSegments(effectiveHull, detail),
        deck: buildDeck(effectiveHull),
        mounts: detail === "small" ? [] : effectiveHull.mounts.map((mount) => ({
          x: mount.x,
          y: mount.y,
          z: extent * 0.18 * (effectiveHull.tuning?.height ?? 1),
          size: mount.size,
        })),
      } satisfies HullModel;

  /*
   * Fit every hull into one normalised frame. A stubby Hammerhead and a long Conquest have very
   * different extents, and without this each would need its own eye distance to avoid either
   * flattening out or bulging through the near plane.
   */
  const vertices = built.segments.flatMap((segment) => [segment.from, segment.to]);
  const span = (axis: "x" | "y" | "z") => {
    const values = vertices.map((vertex) => vertex[axis]);
    return { low: Math.min(...values), high: Math.max(...values) };
  };
  const along = span("x");
  const across = span("y");
  const up = span("z");
  const centre = {
    x: (along.low + along.high) / 2,
    y: (across.low + across.high) / 2,
    z: (up.low + up.high) / 2,
  };
  const reach = Math.max(along.high - along.low, across.high - across.low, 1) / 2;
  const fit = REACH / reach;
  const prepared: PreparedHull = {
    ...built,
    fit,
    centre,
    // A hand's width under the keel, so the grid reads as a floor rather than a section cut.
    groundZ: up.low - centre.z - 0.16 / fit,
    nose: vertices.reduce<HullVertex | null>(
      (best, vertex) => (!best || vertex.x > best.x ? vertex : best),
      null,
    ),
  };

  let details = preparedHulls.get(hull);
  if (!details) {
    details = new Map();
    preparedHulls.set(hull, details);
  }
  details.set(detail, prepared);
  return prepared;
}

/**
 * Yaw about the vertical, then pitch the camera over the top, then a perspective divide.
 *
 * The depth that comes back out is the point of it: near edges are drawn brighter, heavier and
 * last, which is the whole reason a flat wireframe reads as a solid at all.
 */
function projector(prepared: PreparedHull, yaw: number, pitch: number) {
  const cosYaw = Math.cos(yaw);
  const sinYaw = Math.sin(yaw);
  const cosPitch = Math.cos(pitch);
  const sinPitch = Math.sin(pitch);
  const { fit, centre } = prepared;
  return (vertex: HullVertex): ProjectedPoint => {
    const across = (vertex.y - centre.y) * fit;
    const up = (vertex.z - centre.z) * fit;
    const along = (vertex.x - centre.x) * fit;
    const screenX = across * cosYaw - along * sinYaw;
    const yawed = across * sinYaw + along * cosYaw;
    const screenY = up * cosPitch - yawed * sinPitch;
    const depth = up * sinPitch + yawed * cosPitch;
    const scale = EYE / (EYE - depth);
    return { x: screenX * scale, y: -screenY * scale, depth };
  };
}

export function projectHull(
  hull: WireframeHull,
  yaw: number,
  detail: HullDetail,
  pitch: number = PITCH,
): ProjectedHull {
  const prepared = prepareHull(hull, detail);
  const project = projector(prepared, yaw, pitch);
  const segments = prepared.segments.map((segment) => ({
    from: project(segment.from),
    to: project(segment.to),
    kind: segment.kind,
  }));

  /*
   * A reference grid on the keel plane. It is the one thing that says the ship is standing
   * somewhere rather than floating in a swatch, and because it is projected with the same camera
   * it turns with the hull instead of sliding behind it like a backdrop.
   */
  const ground: ProjectedHull["ground"] = [];
  const reach = 1.25 / prepared.fit;
  const steps = detail === "small" ? 0 : detail === "medium" ? 3 : 4;
  const floor = prepared.groundZ + prepared.centre.z;
  for (let step = -steps; step <= steps; step += 1) {
    const at = step / Math.max(steps, 1) * reach;
    ground.push({
      from: project({ x: at + prepared.centre.x, y: -reach + prepared.centre.y, z: floor }),
      to: project({ x: at + prepared.centre.x, y: reach + prepared.centre.y, z: floor }),
    });
    ground.push({
      from: project({ x: -reach + prepared.centre.x, y: at + prepared.centre.y, z: floor }),
      to: project({ x: reach + prepared.centre.x, y: at + prepared.centre.y, z: floor }),
    });
  }

  return {
    segments,
    deck: prepared.deck.map(project),
    mounts: prepared.mounts.map((mount) => ({ ...project(mount), size: mount.size })),
    ground,
    nose: prepared.nose ? project(prepared.nose) : null,
  };
}
