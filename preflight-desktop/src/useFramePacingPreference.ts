import { useState } from "react";
import { FRAME_PACING_STORAGE_KEY } from "./desktopStorage";

function savedPreference(): boolean {
  try {
    return window.localStorage.getItem(FRAME_PACING_STORAGE_KEY) === "on";
  } catch {
    return false;
  }
}

/** Whether ordinary optimized launches retain a bounded local frame-pacing summary. */
export function useFramePacingPreference() {
  const [recordFramePacing, setState] = useState(savedPreference);
  const setRecordFramePacing = (next: boolean) => {
    setState(next);
    try {
      window.localStorage.setItem(FRAME_PACING_STORAGE_KEY, next ? "on" : "off");
    } catch {
      // The choice still applies for this session when WebView storage is unavailable.
    }
  };
  return { recordFramePacing, setRecordFramePacing };
}
