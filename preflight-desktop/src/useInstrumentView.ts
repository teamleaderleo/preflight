import { useEffect, useState } from "react";
import { INSTRUMENT_HULL_VIEW_STORAGE_KEY } from "./desktopStorage";

export interface InstrumentView {
  yaw: number;
  pitch: number;
  zoom: number;
}

export const MIN_INSTRUMENT_PITCH = 0.08;
export const MAX_INSTRUMENT_PITCH = 1.46;
export const MIN_INSTRUMENT_ZOOM = 0.55;
export const MAX_INSTRUMENT_ZOOM = 1.45;
export const DEFAULT_INSTRUMENT_VIEW: Readonly<InstrumentView> = Object.freeze({
  yaw: 0.52,
  pitch: 0.62,
  zoom: 1,
});

const INSTRUMENT_VIEW_EVENT = "preflight:instrument-view";

function bounded(value: unknown, minimum: number, maximum: number, fallback: number): number {
  return typeof value === "number" && Number.isFinite(value)
    ? Math.min(maximum, Math.max(minimum, value))
    : fallback;
}

export function validateInstrumentView(value: unknown): InstrumentView {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return { ...DEFAULT_INSTRUMENT_VIEW };
  }
  const candidate = value as Record<string, unknown>;
  return {
    yaw: bounded(candidate.yaw, -Math.PI, Math.PI, DEFAULT_INSTRUMENT_VIEW.yaw),
    pitch: bounded(candidate.pitch, MIN_INSTRUMENT_PITCH, MAX_INSTRUMENT_PITCH, DEFAULT_INSTRUMENT_VIEW.pitch),
    zoom: bounded(candidate.zoom, MIN_INSTRUMENT_ZOOM, MAX_INSTRUMENT_ZOOM, DEFAULT_INSTRUMENT_VIEW.zoom),
  };
}

function readStoredView(): InstrumentView {
  try {
    const raw = window.localStorage.getItem(INSTRUMENT_HULL_VIEW_STORAGE_KEY);
    return raw ? validateInstrumentView(JSON.parse(raw)) : { ...DEFAULT_INSTRUMENT_VIEW };
  } catch {
    return { ...DEFAULT_INSTRUMENT_VIEW };
  }
}

function publish(view: InstrumentView): void {
  try {
    window.localStorage.setItem(INSTRUMENT_HULL_VIEW_STORAGE_KEY, JSON.stringify(view));
  } catch {
    // The view remains usable for this session when WebView storage is denied.
  }
  window.dispatchEvent(new CustomEvent<InstrumentView>(INSTRUMENT_VIEW_EVENT, { detail: view }));
}

export function resetInstrumentView(): void {
  window.dispatchEvent(new CustomEvent<InstrumentView>(
    INSTRUMENT_VIEW_EVENT,
    { detail: { ...DEFAULT_INSTRUMENT_VIEW } },
  ));
}

/** Restores the player's saved camera while keeping all-data removal free of recreated state. */
export function restoreInstrumentView(): void {
  publish({ ...DEFAULT_INSTRUMENT_VIEW });
}

export function useInstrumentView() {
  const [view, setLocalView] = useState<InstrumentView>(readStoredView);

  useEffect(() => {
    const sync = (event: Event) => setLocalView(validateInstrumentView((event as CustomEvent<unknown>).detail));
    const syncStorage = (event: StorageEvent) => {
      if (event.key !== INSTRUMENT_HULL_VIEW_STORAGE_KEY) return;
      try {
        setLocalView(event.newValue ? validateInstrumentView(JSON.parse(event.newValue)) : { ...DEFAULT_INSTRUMENT_VIEW });
      } catch {
        setLocalView({ ...DEFAULT_INSTRUMENT_VIEW });
      }
    };
    window.addEventListener(INSTRUMENT_VIEW_EVENT, sync);
    window.addEventListener("storage", syncStorage);
    return () => {
      window.removeEventListener(INSTRUMENT_VIEW_EVENT, sync);
      window.removeEventListener("storage", syncStorage);
    };
  }, []);

  const setView = (next: InstrumentView) => {
    const valid = validateInstrumentView(next);
    setLocalView(valid);
    publish(valid);
  };

  return { ...view, setView };
}
