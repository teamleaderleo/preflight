import type { LaunchSettings } from "../types";
import { maximumUiScale } from "../uiFormat";

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

/**
 * The panel's own pixels, which is what the game gets to ask for.
 *
 * <p>`screen.width` is CSS pixels of the scaled desktop, not the display. A Retina MacBook set to
 * "looks like 1440 x 932" reports exactly that while the panel is 2880 x 1864, and Windows display
 * scaling does the same thing at 1.5. Filtering the resolution list by the CSS number hides every
 * mode above the scaled desktop -- and because the UI scale ceiling is derived from the chosen
 * resolution, it drags that down too: 1440 x 932 allows 110%, where the panel allows 225%.
 *
 * The game's launcher enumerates real display modes and never sees the scaled number.
 */
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

interface ResolutionSelectProps {
  id: string;
  label: string;
  value: string;
  onChange: (resolution: string) => void;
}

export function ResolutionSelect({ id, label, value, onChange }: ResolutionSelectProps) {
  const [maximumWidth, maximumHeight] = displayPixels();
  return (
    <select id={id} aria-label={label} value={value} onChange={(event) => onChange(event.target.value)}>
      {resolutionChoices(value, maximumWidth, maximumHeight).map((resolution) => (
        <option value={resolution} key={resolution}>{resolution.replace("x", " × ")}</option>
      ))}
    </select>
  );
}

interface UiScaleSelectProps {
  id: string;
  label: string;
  settings: LaunchSettings;
  resolution: string;
  value: number;
  onChange: (uiScale: number) => void;
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

export function UiScaleSelect({ id, label, settings, resolution, value, onChange }: UiScaleSelectProps) {
  return (
    <select id={id} aria-label={label} value={value} onChange={(event) => onChange(Number(event.target.value))}>
      {uiScaleChoices(settings, resolution, value).map((scale) => (
        <option value={scale} key={scale}>{Math.round(scale * 100)}%</option>
      ))}
    </select>
  );
}

export function battleSizeUpperBound(settings: LaunchSettings, current: number): number {
  return settings.limits.battleSizeExtendedMax
    ?? settings.limits.battleSizeMax
    ?? Math.max(current, 400);
}

/**
 * A few battle sizes worth jumping straight to, named for what they mean.
 *
 * <p>The installation's own numbers come first -- vanilla is 200/400/400, but a mod can raise
 * maxBattleSize and those anchors move with it. The round steps in between only appear when they
 * sit inside the range, so a stock install offers the same five points a modded one does without
 * ever offering a value the game would refuse.
 */
export function battleSizePresets(settings: LaunchSettings): Array<{ value: number; label: string }> {
  const minimum = settings.limits.battleSizeMin ?? 1;
  const upperBound = battleSizeUpperBound(settings, minimum);
  const named = new Map<number, string>();
  const name = (value: number | null, label: string) => {
    if (value === null || value < minimum || value > upperBound || named.has(value)) return;
    named.set(value, label);
  };
  // Only the numbers the installation actually reported get named. `preflight launch-settings` on
  // an installation it cannot read returns every battle-size limit as null, and a "Minimum" button
  // holding the slider's fallback of 1 would be inventing a game rule.
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
