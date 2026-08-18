import { useEffect, useState } from "react";
import { INSTRUMENT_HULL_MOTION_STORAGE_KEY } from "./desktopStorage";

export type InstrumentMotionPreference = "rotate" | "still";

const INSTRUMENT_MOTION_EVENT = "preflight:instrument-motion";

export function validateInstrumentMotion(value: unknown): InstrumentMotionPreference {
  return value === "still" ? "still" : "rotate";
}

export function readInstrumentMotion(
  storage: Pick<Storage, "getItem"> = window.localStorage,
): InstrumentMotionPreference {
  try {
    return validateInstrumentMotion(storage.getItem(INSTRUMENT_HULL_MOTION_STORAGE_KEY));
  } catch {
    return "rotate";
  }
}

function persistInstrumentMotion(
  motion: InstrumentMotionPreference,
  storage: Pick<Storage, "setItem"> = window.localStorage,
): void {
  try {
    storage.setItem(INSTRUMENT_HULL_MOTION_STORAGE_KEY, motion);
  } catch {
    // Cosmetic motion still changes for this session when WebView storage is unavailable.
  }
}

/** One renderer-wide owner for decorative hull motion on Home, Speed, and Hangar. */
export function useInstrumentMotion() {
  const [motion, setMotionState] = useState<InstrumentMotionPreference>(readInstrumentMotion);

  useEffect(() => {
    const syncLocal = (event: Event) => {
      const next = (event as CustomEvent<unknown>).detail;
      setMotionState(validateInstrumentMotion(next));
    };
    const syncStorage = (event: StorageEvent) => {
      if (event.key === INSTRUMENT_HULL_MOTION_STORAGE_KEY) {
        setMotionState(validateInstrumentMotion(event.newValue));
      }
    };
    window.addEventListener(INSTRUMENT_MOTION_EVENT, syncLocal);
    window.addEventListener("storage", syncStorage);
    return () => {
      window.removeEventListener(INSTRUMENT_MOTION_EVENT, syncLocal);
      window.removeEventListener("storage", syncStorage);
    };
  }, []);

  const setMotion = (next: InstrumentMotionPreference) => {
    setMotionState(next);
    persistInstrumentMotion(next);
    window.dispatchEvent(new CustomEvent(INSTRUMENT_MOTION_EVENT, { detail: next }));
  };

  return { motion, setMotion };
}
