import type { WireframeHull, WireframePoint, WireframeTuning } from "./types";

export interface HullVertex {
  x: number;
  y: number;
  z: number;
}

export interface HullSegment {
  from: HullVertex;
  to: HullVertex;
  kind: "outline" | "deck" | "keel" | "structure" | "engine";
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

export const DEFAULT_WIREFRAME_TUNING: Readonly<WireframeTuning> = Object.freeze({
  outerDetail: 0,
  outerSmooth: 0,
  height: 1,
});

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

function tunedHull(hull: WireframeHull): WireframeHull {
  if (!hull.tuning || hull.bounds.length < 3) return hull;
  const { extent } = hullFrame(hull);
  const smoothed = smoothContour(hull.bounds, hull.tuning.outerSmooth);
  const simplified = simplifyContour(smoothed, hull.tuning.outerDetail * extent / 2);
  const bounds = simplified.length >= 3 ? simplified : smoothed;
  return bounds === hull.bounds ? hull : { ...hull, bounds };
}

/** A shared raised cabin keeps the source silhouette legible instead of duplicating every notch. */
function buildDeck(hull: WireframeHull): HullVertex[] {
  const { center, minX, maxX, minY, maxY, extent } = hullFrame(hull);
  const length = Math.max(maxX - minX, 1);
  const halfWidth = Math.max(maxY - minY, 1) / 2;
  const z = extent * 0.16 * (hull.tuning?.height ?? 1);
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
function buildSegments(hull: WireframeHull, detail: HullDetail): HullSegment[] {
  if (hull.bounds.length < 3) return [];
  const { center, extent } = hullFrame(hull);
  const deckHeight = extent * 0.17 * (hull.tuning?.height ?? 1);
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

  if (detail !== "small" && hull.trace) {
    for (const hole of hull.trace.holes) {
      segments.push(...ring(hole.map((point) => ({ ...point, z: deckHeight * 0.03 })), "structure"));
    }
    for (const contour of hull.trace.inner) {
      segments.push(...ring(
        contour.points.map((point) => ({ ...point, z: deckHeight * contour.height })),
        "deck",
      ));
    }
  }

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
  return buildSegments(tunedHull(hull), detail);
}

function prepareHull(hull: WireframeHull, detail: HullDetail): PreparedHull {
  const cached = preparedHulls.get(hull)?.get(detail);
  if (cached) return cached;

  const effectiveHull = tunedHull(hull);
  const { extent } = hullFrame(effectiveHull);
  const built = {
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
