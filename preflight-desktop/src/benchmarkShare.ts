import { startupComparisonPresentation } from "./speedScoreboardFormat";
import type { DesktopBenchmarkMetric } from "./types";

const MAX_SHARE_TEXT_CHARACTERS = 1_024;

export function createBenchmarkShareText(metric: DesktopBenchmarkMetric): string {
  const comparison = startupComparisonPresentation(metric.measurementOnly, metric.optimized);
  const lines = [
    "Preflight startup benchmark",
    `Normal launch: ${formatSeconds(metric.measurementOnly)}`,
    `Preflight launch: ${formatSeconds(metric.optimized)}`,
    `Change: ${comparison.detail}`,
    "Measured by Preflight on this installation. Results depend on hardware, mods, storage, and system load.",
  ];
  const text = `${lines.join("\n")}\n`;
  if (text.length > MAX_SHARE_TEXT_CHARACTERS) {
    throw new Error("Benchmark share text exceeded its fixed size limit.");
  }
  return text;
}

function formatSeconds(milliseconds: number): string {
  if (!Number.isFinite(milliseconds) || milliseconds < 0) {
    throw new Error("Benchmark timing is invalid.");
  }
  return `${(milliseconds / 1_000).toFixed(2)}s`;
}
