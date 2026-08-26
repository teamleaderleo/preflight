import { useEffect, useState } from "react";
import type { LaunchSettings } from "../types";
import { displayPixels, resolutionChoices, uiScaleChoices } from "../gameSettingOptions";

interface ResolutionSelectProps {
  id: string;
  label: string;
  value: string;
  disabled?: boolean;
  onChange: (resolution: string) => void;
}

function useDevicePixelRatioRefresh() {
  const [, setRevision] = useState(0);

  useEffect(() => {
    if (typeof window.matchMedia !== "function") return;
    let query: MediaQueryList | null = null;
    let listener: (() => void) | null = null;

    const clear = () => {
      if (query && listener) query.removeEventListener("change", listener);
      query = null;
      listener = null;
    };
    const watch = () => {
      clear();
      const ratio = window.devicePixelRatio > 0 ? window.devicePixelRatio : 1;
      const nextQuery = window.matchMedia(`(resolution: ${ratio}dppx)`);
      const onChange = () => {
        clear();
        setRevision((revision) => revision + 1);
        watch();
      };
      query = nextQuery;
      listener = onChange;
      nextQuery.addEventListener("change", onChange, { once: true });
    };

    watch();
    return clear;
  }, []);
}

export function ResolutionSelect({ id, label, value, disabled = false, onChange }: ResolutionSelectProps) {
  useDevicePixelRatioRefresh();
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
