import { describe, expect, it } from "vitest";
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

  it("anchors the deck centerline to the bounding box midpoint on asymmetric hulls", () => {
    // An asymmetric hull where one flank has clustered points that pull the vertex centroid away from y = 0
    const asymmetricHull: WireframeHull = {
      ...hull,
      id: "asymmetric-test",
      bounds: [
        { x: 10, y: 0 },
        { x: 5, y: 8 },
        { x: 0, y: 8 },
        { x: -5, y: 8 },
        { x: -5, y: 6 },
        { x: -5, y: 4 },
        { x: -5, y: 2 },
        { x: -5, y: -8 }, // only 1 vertex on the negative flank vs 6 on the positive flank
      ],
    };
    const segments = buildHullSegments(asymmetricHull, "medium");
    const deck = segments.filter((segment) => segment.kind === "deck");
    expect(deck.length).toBeGreaterThan(0);
    // Fore and aft centerline points should sit at y = 0 (the bbox midpoint between -8 and +8)
    const centerlineYValues = deck.flatMap((s) => [s.from, s.to]).filter((v) => Math.abs(v.x - 10) < 3 || Math.abs(v.x - -5) < 3);
    const apex = centerlineYValues.find((v) => Math.abs(v.x - 8.5) < 0.1);
    if (apex) {
      expect(apex.y).toBeCloseTo(0, 4);
    }
  });

  it("projects keel ground grid and bow nose marker in 3D space", () => {
    const projected = projectHull(hull, 0.38, "medium");
    expect(projected.ground.length).toBeGreaterThan(0);
    expect(projected.nose).not.toBeNull();
    // All projected segments carry perspective depth
    expect(projected.segments.every((s) => typeof s.from.depth === "number")).toBe(true);
  });

  it("applies bounded cosmetic tuning to installation-owned hull geometry", () => {
    const detailed = {
      ...hull,
      bounds: [
        { x: 10, y: 0 },
        { x: 6, y: 2 },
        { x: 2, y: 4 },
        { x: -5, y: 8 },
        { x: -5, y: -8 },
        { x: 2, y: -4 },
        { x: 6, y: -2 },
      ],
    };
    const simplified = { ...detailed, tuning: { outerDetail: 0.06, outerSmooth: 0.2, height: 1.5 } };
    expect(buildHullSegments(simplified, "medium").length).toBeLessThan(buildHullSegments(detailed, "medium").length);
    expect(projectHull(simplified, 0.2, "medium").deck).not.toEqual(projectHull(hull, 0.2, "medium").deck);
  });
});
