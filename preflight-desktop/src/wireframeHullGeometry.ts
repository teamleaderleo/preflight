import type { WireframeHull, WireframePoint } from "./types";

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
export function buildHullSegments(hull: WireframeHull, detail: "small" | "medium" | "showcase"): HullSegment[] {
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

export function projectHull(hull: WireframeHull, yaw: number, detail: "small" | "medium" | "showcase"): ProjectedHull {
  const segments = buildHullSegments(hull, detail).map((segment) => ({
    from: project(segment.from, yaw),
    to: project(segment.to, yaw),
    kind: segment.kind,
  }));
  const { extent } = hullFrame(hull);
  const deck = buildDeck(hull).map((point) => project(point, yaw));
  const mounts = detail === "small" ? [] : hull.mounts.map((mount) => ({
    ...project({ x: mount.x, y: mount.y, z: extent * 0.18 }, yaw),
    size: mount.size,
  }));
  return { segments, deck, mounts };
}
