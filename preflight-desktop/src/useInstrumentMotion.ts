import { useEffect, useState } from "react";
import { INSTRUMENT_HULL_MOTION_STORAGE_KEY } from "./desktopStorage";

export type InstrumentMotionMode = "rotate" | "still";
export type InstrumentRotationDirection = "clockwise" | "counter-clockwise";

export interface InstrumentMotionPreference {
  motion: InstrumentMotionMode;
  direction: InstrumentRotationDirection;
}

export const DEFAULT_INSTRUMENT_MOTION: Readonly<InstrumentMotionPreference> = Object.freeze({
  motion: "rotate",
  direction: "clockwise",
});

const INSTRUMENT_MOTION_EVENT = "preflight:instrument-motion";

export function validateInstrumentMotion(value: unknown): InstrumentMotionPreference {
  if (value === "rotate" || value === "still") {
    return { motion: value, direction: "clockwise" };
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return { ...DEFAULT_INSTRUMENT_MOTION };
  }
  const candidate = value as Record<string, unknown>;
  const motion = candidate.motion;
  const direction = candidate.direction;
  if ((motion !== "rotate" && motion !== "still")
      || (direction !== "clockwise" && direction !== "counter-clockwise")) {
    return { ...DEFAULT_INSTRUMENT_MOTION };
  }
  return { motion, direction };
}

function decodeInstrumentMotion(raw: string | null): InstrumentMotionPreference {
  if (raw === "rotate" || raw === "still") return validateInstrumentMotion(raw);
  if (!raw) return { ...DEFAULT_INSTRUMENT_MOTION };
  try {
    return validateInstrumentMotion(JSON.parse(raw));
  } catch {
    return { ...DEFAULT_INSTRUMENT_MOTION };
  }
}

export function readInstrumentMotion(
  storage: Pick<Storage, "getItem"> = window.localStorage,
): InstrumentMotionPreference {
  try {
    return decodeInstrumentMotion(storage.getItem(INSTRUMENT_HULL_MOTION_STORAGE_KEY));
  } catch {
    return { ...DEFAULT_INSTRUMENT_MOTION };
  }
}

function persistInstrumentMotion(
  preference: InstrumentMotionPreference,
  storage: Pick<Storage, "setItem"> = window.localStorage,
): void {
  try {
    storage.setItem(INSTRUMENT_HULL_MOTION_STORAGE_KEY, JSON.stringify(preference));
  } catch {
    // Cosmetic motion still changes for this session when WebView storage is unavailable.
  }
}

function broadcastInstrumentMotion(preference: InstrumentMotionPreference): void {
  window.dispatchEvent(new CustomEvent<InstrumentMotionPreference>(
    INSTRUMENT_MOTION_EVENT,
    { detail: preference },
  ));
}

/** Resets mounted renderer consumers after all-data cleanup without recreating cleared storage. */
export function resetInstrumentMotion(): void {
  broadcastInstrumentMotion({ ...DEFAULT_INSTRUMENT_MOTION });
}

/** One renderer-wide owner for decorative hull motion on Home, Speed, and Hangar. */
export function useInstrumentMotion() {
  const [preference, setPreference] = useState<InstrumentMotionPreference>(readInstrumentMotion);

  useEffect(() => {
    const syncLocal = (event: Event) => {
      const next = (event as CustomEvent<unknown>).detail;
      setPreference(validateInstrumentMotion(next));
    };
    const syncStorage = (event: StorageEvent) => {
      if (event.key === INSTRUMENT_HULL_MOTION_STORAGE_KEY) {
        setPreference(decodeInstrumentMotion(event.newValue));
      }
    };
    window.addEventListener(INSTRUMENT_MOTION_EVENT, syncLocal);
    window.addEventListener("storage", syncStorage);
    return () => {
      window.removeEventListener(INSTRUMENT_MOTION_EVENT, syncLocal);
      window.removeEventListener("storage", syncStorage);
    };
  }, []);

  const publish = (next: InstrumentMotionPreference) => {
    setPreference(next);
    persistInstrumentMotion(next);
    broadcastInstrumentMotion(next);
  };
  const setMotion = (motion: InstrumentMotionMode) => publish({ ...preference, motion });
  const setDirection = (direction: InstrumentRotationDirection) => publish({ ...preference, direction });

  return {
    motion: preference.motion,
    direction: preference.direction,
    setMotion,
    setDirection,
  };
}
