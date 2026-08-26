import { useState } from "react";
import { FRAME_PACING_STORAGE_KEY, SMOOTH_FRAME_PACING_STORAGE_KEY } from "./desktopStorage";

function savedPreference(key: string): boolean {
  try {
    return window.localStorage.getItem(key) === "on";
  } catch {
    return false;
  }
}

/** Whether ordinary optimized launches retain a bounded local frame-pacing summary. */
export function useFramePacingPreference() {
  const [recordFramePacing, setRecordState] = useState(
    () => savedPreference(FRAME_PACING_STORAGE_KEY),
  );
  const [smoothFramePacing, setSmoothState] = useState(
    () => savedPreference(SMOOTH_FRAME_PACING_STORAGE_KEY),
  );
  const setRecordFramePacing = (next: boolean) => {
    setRecordState(next);
    try {
      window.localStorage.setItem(FRAME_PACING_STORAGE_KEY, next ? "on" : "off");
    } catch {
      // The choice still applies for this session when WebView storage is unavailable.
    }
  };
  const setSmoothFramePacing = (next: boolean) => {
    setSmoothState(next);
    try {
      window.localStorage.setItem(SMOOTH_FRAME_PACING_STORAGE_KEY, next ? "on" : "off");
    } catch {
      // The choice still applies for this session when WebView storage is unavailable.
    }
  };
  return {
    recordFramePacing,
    setRecordFramePacing,
    smoothFramePacing,
    setSmoothFramePacing,
  };
}
