import { expect, test } from "vitest";
import { createBenchmarkShareText } from "./benchmarkShare";

test.each([
  [100_000, 75_000, "25.0% less startup time"],
  [100_000, 99_500, "0.5% difference"],
  [80_000, 100_000, "25.0% more startup time"],
])("creates bounded installation-specific share text", (measurementOnly, optimized, detail) => {
  const text = createBenchmarkShareText({
    measurementOnly,
    optimized,
    delta: optimized - measurementOnly,
    improvementPercent: (1 - optimized / measurementOnly) * 100,
  });

  expect(text).toContain(`Optimizations off: ${(measurementOnly / 1_000).toFixed(2)}s`);
  expect(text).toContain(`Optimizations on: ${(optimized / 1_000).toFixed(2)}s`);
  expect(text).toContain(`Change: ${detail}`);
  expect(text).toContain("Both launches ran through Preflight");
  expect(text).toContain("Results depend on hardware, mods, storage, and system load.");
  expect(text.length).toBeLessThan(1_024);
});

test("rejects malformed timing rather than copying an invented result", () => {
  expect(() => createBenchmarkShareText({
    measurementOnly: Number.NaN,
    optimized: 10_000,
    delta: 0,
    improvementPercent: null,
  })).toThrow(/invalid/);
});
