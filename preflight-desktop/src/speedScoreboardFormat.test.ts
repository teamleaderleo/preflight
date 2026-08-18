import { describe, expect, test } from "vitest";
import { startupComparisonPresentation } from "./speedScoreboardFormat";

describe("startup comparison presentation", () => {
  test("calls a meaningful improvement faster", () => {
    expect(startupComparisonPresentation(100_000, 75_000)).toEqual({
      kind: "faster",
      improvementPercent: 25,
      headline: "Faster",
      detail: "25.0% less startup time",
    });
  });

  test("keeps a tiny difference neutral", () => {
    const result = startupComparisonPresentation(100_000, 99_500);
    expect(result.kind).toBe("similar");
    expect(result.headline).toBe("About the same");
    expect(result.detail).toBe("0.5% difference");
  });

  test("calls an unfavorable result slower", () => {
    expect(startupComparisonPresentation(80_000, 100_000)).toEqual({
      kind: "slower",
      improvementPercent: -25,
      headline: "Slower",
      detail: "25.0% more startup time",
    });
  });
});
