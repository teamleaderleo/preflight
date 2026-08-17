import { describe, expect, it } from "vitest";
import type { WireframeHull } from "./types";
import { DEFAULT_WIREFRAME_TUNING, buildHullSegments, projectHull } from "./wireframeHullGeometry";

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
    // Enough points that the outline floor is not the binding constraint. A traced silhouette
    // carries 90 to 210; the dial can only be observed on a loop that has something to give up.
    const detailed = {
      ...hull,
      bounds: Array.from({ length: 24 }, (_, index) => {
        const angle = (index / 24) * Math.PI * 2;
        const wobble = index % 2 === 0 ? 1 : 0.97;
        return { x: Math.cos(angle) * 10 * wobble, y: Math.sin(angle) * 8 * wobble };
      }),
    };
    // Against an explicit full-detail dial, not against an absent one: every hull now gets the
    // default simplification pass, so "no tuning" is a tolerance rather than the raw contour.
    const full = { ...detailed, tuning: { ...DEFAULT_WIREFRAME_TUNING, outerDetail: 0 } };
    const simplified = {
      ...detailed,
      tuning: { ...DEFAULT_WIREFRAME_TUNING, outerDetail: 0.06, outerSmooth: 0.2, height: 1.5 },
    };
    expect(buildHullSegments(simplified, "medium").length).toBeLessThan(buildHullSegments(full, "medium").length);
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

  it("keeps normalized asymmetric hulls on their bounding-box centerline", () => {
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
        { x: -5, y: -8 },
      ],
    };
    const segments = buildHullSegments(asymmetricHull, "medium");
    const deck = segments.filter((segment) => segment.kind === "deck");
    const keel = segments.filter((segment) => segment.kind === "keel");

    expect(deck).toHaveLength(6);
    expect(deck[0].from.y).toBeCloseTo(0, 8);
    expect(deck[3].from.y).toBeCloseTo(0, 8);
    expect(Math.min(...keel.map((segment) => segment.from.y)))
      .toBeCloseTo(-Math.max(...keel.map((segment) => segment.from.y)), 8);
  });

  it("draws sprite traces as plates instead of adding the generic cabin and weapon circles", () => {
    const traced: WireframeHull = {
      ...hull,
      id: "traced-test",
      bounds: [
        { x: 12, y: 0 },
        { x: 2, y: 9 },
        { x: -10, y: 7 },
        { x: -12, y: 0 },
        { x: -10, y: -7 },
        { x: 2, y: -9 },
      ],
      trace: {
        holes: [[
          { x: 2, y: 2 },
          { x: -2, y: 2 },
          { x: -2, y: -2 },
          { x: 2, y: -2 },
        ]],
        inner: [{
          height: 0.86,
          points: [
            { x: 8, y: 0 },
            { x: 1, y: 3 },
            { x: -5, y: 0 },
            { x: 1, y: -3 },
          ],
        }],
      },
    };

    const segments = buildHullSegments(traced, "medium");
    expect(segments.filter((segment) => segment.kind === "outline")).toHaveLength(10);
    expect(segments.filter((segment) => segment.kind === "deck")).toHaveLength(14);
    expect(segments.filter((segment) => segment.kind === "keel")).toHaveLength(10);
    const projected = projectHull(traced, 0.2, "medium");
    expect(projected.deck).toEqual([]);
    expect(projected.mounts).toEqual([]);
  });

  it("simplifies traced contours by default, on both loop families", () => {
    // A contour with a point on every pixel turn, as marching squares emits it. Drawn raw this
    // is what made the Astral 830 edges against the design page's 587.
    const stair = (radius: number, steps: number) => Array.from({ length: steps }, (_, index) => {
      const angle = (index / steps) * Math.PI * 2;
      const ripple = 1 + (index % 2 === 0 ? 0.004 : -0.004);
      return { x: Math.cos(angle) * radius * ripple, y: Math.sin(angle) * radius * 0.7 * ripple };
    });
    const jagged: WireframeHull = {
      ...hull,
      id: "jagged-test",
      bounds: stair(100, 160),
      trace: { holes: [], inner: [{ height: 0.8, points: stair(50, 120) }] },
    };

    const defaulted = buildHullSegments(jagged, "showcase").length;
    const raw = buildHullSegments(
      { ...jagged, tuning: { ...DEFAULT_WIREFRAME_TUNING, outerDetail: 0, innerDetail: 0 } },
      "showcase",
    ).length;
    expect(defaulted).toBeLessThan(raw);
    // Each family is dialled on its own, so turning one off has to leave the other simplified.
    const outerOnly = buildHullSegments(
      { ...jagged, tuning: { ...DEFAULT_WIREFRAME_TUNING, innerDetail: 0 } },
      "showcase",
    ).length;
    expect(outerOnly).toBeGreaterThan(defaulted);
    expect(outerOnly).toBeLessThan(raw);
  });

  it("keeps a hull readable at the coarsest dial the picker allows", () => {
    // 0.06 is the picker's maximum. A 96-point near-circle is the least forgiving thing a
    // tolerance can be pointed at -- every deviation is small and none of them stand out -- and
    // it still comes back as a shape rather than a triangle.
    const round: WireframeHull = {
      ...hull,
      id: "round-test",
      bounds: Array.from({ length: 96 }, (_, index) => {
        const angle = (index / 96) * Math.PI * 2;
        return { x: Math.cos(angle) * 100, y: Math.sin(angle) * 100 };
      }),
    };
    const outline = (detail: number) => buildHullSegments(
      { ...round, tuning: { ...DEFAULT_WIREFRAME_TUNING, outerDetail: detail } },
      "medium",
    ).filter((segment) => segment.kind === "outline").length;
    expect(outline(0)).toBe(96);
    expect(outline(0.012)).toBeLessThan(96);
    expect(outline(0.06)).toBeGreaterThanOrEqual(12);
  });

  it("keeps the collision-bound fallback for hulls without a readable sprite trace", () => {
    const segments = buildHullSegments(hull, "medium");
    expect(segments.filter((segment) => segment.kind === "deck")).toHaveLength(6);
    expect(projectHull(hull, 0.2, "medium").mounts).toHaveLength(1);
  });
});
