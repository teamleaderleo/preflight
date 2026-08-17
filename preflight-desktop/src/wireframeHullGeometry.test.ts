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

  it("projects the keel grid and bow marker through the same finite 3D camera", () => {
    const projected = projectHull(hull, 0.38, "medium");
    expect(projected.ground).not.toHaveLength(0);
    expect(projected.nose).not.toBeNull();
    expect(projected.segments.every((segment) => (
      Number.isFinite(segment.from.x)
      && Number.isFinite(segment.from.y)
      && Number.isFinite(segment.from.depth)
      && Number.isFinite(segment.to.x)
      && Number.isFinite(segment.to.y)
      && Number.isFinite(segment.to.depth)
    ))).toBe(true);
  });
});
