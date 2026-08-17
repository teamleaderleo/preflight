import { describe, expect, it } from "vitest";
import { CURATED_WIREFRAME_HULLS } from "./curatedWireframeHulls";
import type { WireframeHull } from "./types";
import { buildHullSegments, projectHull } from "./wireframeHullGeometry";

const hull: WireframeHull = {
  id: "test",
  name: "Test hull",
  hullSize: "FRIGATE",
  style: "TEST",
  featured: false,
  bounds: [
    { x: 10, y: 0 },
    { x: -5, y: 8 },
    { x: -5, y: -8 },
  ],
  engines: [{ x: -5, y: 0, angle: 180, width: 4, length: 8 }],
  mounts: [
    { x: 4, y: 0, angle: 0, size: "LARGE", mount: "HARDPOINT" },
  ],
};

describe("wireframe hull geometry", () => {
  it("builds a closed outline, raised deck, structure and engine bell", () => {
    const segments = buildHullSegments(hull, "medium");
    expect(segments.filter((segment) => segment.kind === "outline")).toHaveLength(3);
    expect(segments.filter((segment) => segment.kind === "deck")).toHaveLength(6);
    expect(segments.filter((segment) => segment.kind === "engine")).toHaveLength(3);
    expect(segments.some((segment) => segment.from.z > 0 || segment.to.z > 0)).toBe(true);
  });

  it("keeps mounts out of the small presentation and includes them above it", () => {
    expect(projectHull(hull, 0.2, "small").mounts).toHaveLength(0);
    expect(projectHull(hull, 0.2, "medium").mounts).toEqual([
      expect.objectContaining({ size: "LARGE" }),
    ]);
  });

  it("changes the projection when the inspection angle moves", () => {
    const left = projectHull(hull, -0.2, "medium");
    const right = projectHull(hull, 0.2, "medium");
    expect(left.segments[0].from).not.toEqual(right.segments[0].from);
  });
});

/*
 * The design page draws these same six hulls, and its edge count per hull is the number this
 * renderer has to reproduce. Pinning it catches a port that has quietly stopped agreeing with the
 * artefact the design decisions were made against, which is not a hypothetical: the same check
 * caught three divergences between the page and its own contact sheet, each invisible in a
 * thumbnail and one of them a whole ship wearing another ship's bastions.
 *
 * The page's counts at these settings are 406 / 497 / 530 / 538 / 587 / 521. Five match exactly.
 * The Conquest is three higher here, and that is expected rather than drift: it is one outline
 * vertex, tripled because outline, deck and keel all follow the same loop. The page simplifies in
 * a +/-1 frame and the product in a +/-100 one, so a vertex sitting on the tolerance falls the
 * other way. Running the page's own simplifier over the scaled points reproduces the product's
 * count, which is how that was settled rather than assumed. A count that moves for any other
 * reason is a real change.
 */
const SHOWCASE_EDGES: Record<string, number> = {
  odyssey: 406,
  onslaught: 497,
  conquest: 533,
  paragon: 538,
  astral: 587,
  hammerhead: 521,
};

describe("shipped hull geometry", () => {
  const shipped = new Map(CURATED_WIREFRAME_HULLS.map((record) => [record.id, record]));
  const ship = (id: string): WireframeHull => {
    const found = shipped.get(id);
    if (!found) throw new Error(`${id} is not shipped`);
    return found;
  };

  it("reproduces the design page's construction, hull for hull", () => {
    for (const [id, expected] of Object.entries(SHOWCASE_EDGES)) {
      expect({ id, edges: buildHullSegments(ship(id), "showcase").length })
        .toEqual({ id, edges: expected });
    }
  });

  it("draws less as the instrument gets smaller, and keeps the silhouette at every size", () => {
    for (const id of Object.keys(SHOWCASE_EDGES)) {
      const small = buildHullSegments(ship(id), "small");
      const medium = buildHullSegments(ship(id), "medium");
      const showcase = buildHullSegments(ship(id), "showcase");
      expect(small.length).toBeLessThan(medium.length);
      expect(medium.length).toBeLessThanOrEqual(showcase.length);
      // Interiors are the first thing dropped at thumbnail size; the outline never is.
      expect(small.some((segment) => segment.kind === "interior")).toBe(false);
      expect(showcase.some((segment) => segment.kind === "interior")).toBe(true);
      for (const built of [small, medium, showcase]) {
        expect(built.some((segment) => segment.kind === "outline")).toBe(true);
      }
    }
  });

  it("takes the centreline from the hull's extent, not from where its vertices bunch up", () => {
    /*
     * The plate's rim falls away from a centreline, and which line that is decides whether the
     * two flanks get the same thickness. Taking it from the mean of the vertices looks equivalent
     * and is not: a flank traced with more points drags the mean toward itself, and the rim tilts
     * with it. On the Astral that was a third of half-thickness at a point.
     *
     * So this hull is exactly symmetric, and then given four extra vertices along one flank that
     * change nothing about its shape. A renderer working from the extent cannot tell; one working
     * from the vertex mean produces two different plates.
     */
    const mirrored: WireframeHull = {
      ...hull,
      id: "mirror",
      bounds: [
        { x: 100, y: 0 }, { x: 40, y: 60 }, { x: -60, y: 60 }, { x: -100, y: 0 },
        { x: -60, y: -60 }, { x: -30, y: -60 }, { x: -10, y: -60 }, { x: 10, y: -60 },
        { x: 40, y: -60 },
      ],
      curated: {
        format: "preflight-curated-wireframe-v1",
        thickness: 0.14,
        engineBells: 2,
        holes: [],
        inner: [],
      },
    };
    const deck = buildHullSegments(mirrored, "showcase").filter((segment) => segment.kind === "deck");
    const at = (x: number, y: number) => deck.find((segment) => segment.from.x === x && segment.from.y === y);
    for (const [x, y] of [[40, 60], [-60, 60]] as const) {
      const port = at(x, y);
      const starboard = at(x, -y);
      expect(port, `no deck vertex at ${x},${y}`).toBeDefined();
      expect(starboard, `no deck vertex at ${x},${-y}`).toBeDefined();
      expect(port?.from.z).toBeCloseTo(starboard?.from.z ?? Number.NaN, 10);
    }
  });

  it("leaves a hull scanned from an installation on the generic loft", () => {
    const scanned = buildHullSegments(hull, "showcase");
    expect(scanned.length).toBeGreaterThan(0);
    expect(scanned.some((segment) => segment.kind === "interior")).toBe(false);
  });

  it("projects to finite coordinates through a full turn", () => {
    for (const yaw of [0, 0.34, 0.52, 0.7, Math.PI, -1.2]) {
      const projected = projectHull(ship("paragon"), yaw, "showcase");
      expect(projected.segments.length).toBeGreaterThan(0);
      for (const segment of projected.segments) {
        expect(Number.isFinite(segment.from.x) && Number.isFinite(segment.from.y)).toBe(true);
        expect(Number.isFinite(segment.to.x) && Number.isFinite(segment.to.y)).toBe(true);
      }
    }
  });
});
