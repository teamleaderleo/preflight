import type { LaunchSettings } from "../types";
import { displayPixels, resolutionChoices, uiScaleChoices } from "../gameSettingOptions";

interface ResolutionSelectProps {
  id: string;
  label: string;
  value: string;
  disabled?: boolean;
  onChange: (resolution: string) => void;
}

export function ResolutionSelect({ id, label, value, disabled = false, onChange }: ResolutionSelectProps) {
  const [maximumWidth, maximumHeight] = displayPixels();
  return (
    <select id={id} aria-label={label} value={value} disabled={disabled} onChange={(event) => onChange(event.target.value)}>
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
  disabled?: boolean;
  onChange: (uiScale: number) => void;
}

export function UiScaleSelect({ id, label, settings, resolution, value, disabled = false, onChange }: UiScaleSelectProps) {
  return (
    <select id={id} aria-label={label} value={value} disabled={disabled} onChange={(event) => onChange(Number(event.target.value))}>
      {uiScaleChoices(settings, resolution, value).map((scale) => (
        <option value={scale} key={scale}>{Math.round(scale * 100)}%</option>
      ))}
    </select>
  );
}
