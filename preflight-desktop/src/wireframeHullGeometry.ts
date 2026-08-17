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

/** A shared raised cabin keeps the source silhouette legible instead of inventing ship-specific depth. */
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
    return { from: nearest, to: point, kind: "structure" as const };
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
  const prepared = {
    segments: buildSegments(effectiveHull, detail),
    deck: buildDeck(effectiveHull),
    mounts: detail === "small" ? [] : effectiveHull.mounts.map((mount) => ({
      x: mount.x,
      y: mount.y,
      z: extent * 0.18 * (effectiveHull.tuning?.height ?? 1),
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
  return { x: lateral, y: -forward * 0.62 - vertex.z };
}

export function projectHull(hull: WireframeHull, yaw: number, detail: HullDetail): ProjectedHull {
  const prepared = prepareHull(hull, detail);
  const segments = prepared.segments.map((segment) => ({
    from: project(segment.from, yaw),
    to: project(segment.to, yaw),
    kind: segment.kind,
  }));
  const deck = prepared.deck.map((point) => project(point, yaw));
  const mounts = prepared.mounts.map((mount) => ({ ...project(mount, yaw), size: mount.size }));
  return { segments, deck, mounts };
}
