export type StartupComparisonKind = "faster" | "similar" | "slower";

export interface StartupComparisonPresentation {
  kind: StartupComparisonKind;
  improvementPercent: number;
  headline: string;
  detail: string;
}

/**
 * Presentation-only neutral band. It avoids turning tiny run-to-run differences into a victory or
 * regression claim. This is intentionally a UI rule, not a statistical confidence statement.
 */
export const STARTUP_COMPARISON_NEUTRAL_PERCENT = 1;

export function startupImprovementPercent(measurementOnlyMs: number, optimizedMs: number): number {
  return (1 - optimizedMs / measurementOnlyMs) * 100;
}

export function startupComparisonPresentation(
  measurementOnlyMs: number,
  optimizedMs: number,
): StartupComparisonPresentation {
  const improvementPercent = startupImprovementPercent(measurementOnlyMs, optimizedMs);
  if (Math.abs(improvementPercent) < STARTUP_COMPARISON_NEUTRAL_PERCENT) {
    return {
      kind: "similar",
      improvementPercent,
      headline: "About the same",
      detail: `${Math.abs(improvementPercent).toFixed(1)}% difference`,
    };
  }
  if (improvementPercent > 0) {
    return {
      kind: "faster",
      improvementPercent,
      headline: "Faster",
      detail: `${improvementPercent.toFixed(1)}% less startup time`,
    };
  }
  return {
    kind: "slower",
    improvementPercent,
    headline: "Slower",
    detail: `${Math.abs(improvementPercent).toFixed(1)}% more startup time`,
  };
}
