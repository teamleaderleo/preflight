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
  const maximumWidth = typeof window === "undefined" ? 0 : window.screen?.width ?? 0;
  const maximumHeight = typeof window === "undefined" ? 0 : window.screen?.height ?? 0;
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
