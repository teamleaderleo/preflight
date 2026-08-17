import artifact from "./generated/curated-wireframe-hulls.json";
import type { CuratedWireframeGeometry, WireframeHull, WireframePoint } from "./types";

/**
 * The six hulls Preflight ships with, traced inside and out from the game's own artwork in
 * `docs/design/hangar-light/` and exported by `export-product-hulls.py`.
 *
 * They are bundled rather than scanned, so they are on screen before the optional installation
 * catalog has been read -- and they exist at all because collision bounds cannot say what is
 * inside a ship, only where its edge is.
 *
 * The artifact is generated and compiled in, so this file does not re-litigate its shape. It
 * checks only what would make the renderer throw, and drops anything that would. The real
 * assertion that this file matches the design page lives in the tests, which is where a bad
 * regeneration can still be caught before it ships.
 */

export const CURATED_HULL_FORMAT = "preflight-curated-wireframe-hulls-v1";
export const CURATED_HULL_IDS = ["odyssey", "onslaught", "conquest", "paragon", "astral", "hammerhead"] as const;

/** Enough points to be a closed shape at all; the builder simplifies below this and gives up. */
const MINIMUM_CONTOUR = 3;

function isPoint(value: unknown): value is WireframePoint {
  const point = value as WireframePoint | null;
  return point !== null && Number.isFinite(point?.x) && Number.isFinite(point?.y);
}

function isContour(value: unknown): value is WireframePoint[] {
  return Array.isArray(value) && value.length >= MINIMUM_CONTOUR && value.every(isPoint);
}

function isCurated(value: unknown): value is CuratedWireframeGeometry {
  const geometry = value as CuratedWireframeGeometry | null;
  if (!geometry || geometry.format !== "preflight-curated-wireframe-v1") return false;
  return Number.isFinite(geometry.thickness) && geometry.thickness > 0
    && Number.isInteger(geometry.engineBells) && geometry.engineBells >= 1
    && Array.isArray(geometry.holes) && geometry.holes.every(isContour)
    && Array.isArray(geometry.inner)
    && geometry.inner.every((tier) => Number.isFinite(tier?.height) && isContour(tier?.points));
}

function isHull(value: unknown): value is WireframeHull {
  const hull = value as WireframeHull | null;
  if (!hull) return false;
  return typeof hull.id === "string" && typeof hull.name === "string"
    && isContour(hull.bounds) && isCurated(hull.curated);
}

/** Keeps the well-formed records and discards the rest, so one bad hull cannot cost the other five. */
export function readCuratedWireframeArtifact(value: unknown): WireframeHull[] {
  const source = value as { format?: unknown; hulls?: unknown };
  if (source?.format !== CURATED_HULL_FORMAT || !Array.isArray(source.hulls)) return [];
  return source.hulls.filter(isHull);
}

export const CURATED_WIREFRAME_HULLS: WireframeHull[] = readCuratedWireframeArtifact(artifact);
