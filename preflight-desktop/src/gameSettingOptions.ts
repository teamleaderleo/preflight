import type { LaunchSettings } from "./types";
import { maximumUiScale } from "./uiFormat";

const COMMON_RESOLUTIONS = [
  "1024x768",
  "1280x720",
  "1280x800",
  "1366x768",
  "1440x900",
  "1600x900",
  "1680x1050",
  "1920x1080",
  "1920x1200",
  "2560x1440",
  "2560x1600",
  "2880x1800",
  "3024x1964",
  "3840x2160",
] as const;

function resolutionArea(resolution: string): number {
  const [width, height] = resolution.split("x").map(Number);
  return width * height;
}

export function displayPixels(): [number, number] {
  if (typeof window === "undefined" || !window.screen) return [0, 0];
  const ratio = window.devicePixelRatio > 0 ? window.devicePixelRatio : 1;
  return [Math.round(window.screen.width * ratio), Math.round(window.screen.height * ratio)];
}

export function resolutionChoices(current: string, maximumWidth = 0, maximumHeight = 0): string[] {
  return Array.from(new Set([...COMMON_RESOLUTIONS, current]))
    .filter((resolution) => /^\d+x\d+$/.test(resolution))
    .filter((resolution) => {
      if (resolution === current || maximumWidth <= 0 || maximumHeight <= 0) return true;
      const [width, height] = resolution.split("x").map(Number);
      return width <= maximumWidth && height <= maximumHeight;
    })
    .sort((left, right) => resolutionArea(left) - resolutionArea(right));
}

export function uiScaleChoices(settings: LaunchSettings, resolution: string, current: number): number[] {
  const maximum = uiScaleMaximum(settings, resolution);
  const values = new Set<number>([current]);
  for (let value = settings.limits.uiScaleMin; value <= maximum + 0.000_001; value += settings.limits.uiScaleStep) {
    values.add(Number(value.toFixed(2)));
  }
  return Array.from(values).filter((value) => value <= maximum || value === current).sort((left, right) => left - right);
}

export function uiScaleMaximum(settings: LaunchSettings, resolution: string): number {
  return Math.min(
    settings.limits.uiScaleMax,
    maximumUiScale(resolution) ?? settings.limits.uiScaleMax,
  );
}

export function battleSizeUpperBound(settings: LaunchSettings, current: number): number {
  return settings.limits.battleSizeExtendedMax
    ?? settings.limits.battleSizeMax
    ?? Math.max(current, 400);
}

export function battleSizePresets(settings: LaunchSettings): Array<{ value: number; label: string }> {
  const minimum = settings.limits.battleSizeMin ?? 1;
  const upperBound = battleSizeUpperBound(settings, minimum);
  const named = new Map<number, string>();
  const name = (value: number | null, label: string) => {
    if (value === null || value < minimum || value > upperBound || named.has(value)) return;
    named.set(value, label);
  };
  name(settings.limits.battleSizeMin, "Minimum");
  name(settings.limits.battleSizeDefault, "Default");
  name(settings.limits.battleSizeMax, "Vanilla max");
  name(600, "Larger");
  name(1000, "Big");
  name(1500, "Huge");
  name(upperBound, "Maximum");
  return [...named.entries()]
    .sort(([left], [right]) => left - right)
    .map(([value, label]) => ({ value, label }));
}
