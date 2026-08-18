import { render, screen } from "@testing-library/react";
import { BenchmarkResult } from "./BenchmarkPage";

test.each([
  ["faster", 100_000, 75_000, /Faster · 25\.0% less startup time/],
  ["similar", 100_000, 99_500, /About the same · 0\.5% difference/],
  ["slower", 80_000, 100_000, /Slower · 25\.0% more startup time/],
])("renders a %s startup comparison without celebratory fallback wording", (_name, measurementOnly, optimized, expected) => {
  render(
    <BenchmarkResult
      label="Main menu"
      metric={{ measurementOnly, optimized, improvementPercent: (1 - optimized / measurementOnly) * 100 }}
      unit="time"
    />,
  );

  expect(screen.getByText(expected)).toBeInTheDocument();
  expect(screen.queryByText(/% better/)).not.toBeInTheDocument();
});
