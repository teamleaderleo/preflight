import { useState } from "react";
import { AFTER_LAUNCH_BEHAVIOR_STORAGE_KEY } from "./desktopStorage";

export type AfterLaunchBehavior = "minimize" | "keep" | "quit";

function savedBehavior(): AfterLaunchBehavior {
  try {
    const saved = window.localStorage.getItem(AFTER_LAUNCH_BEHAVIOR_STORAGE_KEY);
    return saved === "keep" || saved === "quit" || saved === "minimize" ? saved : "minimize";
  } catch {
    return "minimize";
  }
}

/** What Preflight does only after it has verified the actual Starsector JVM is alive. */
export function useAfterLaunchBehavior() {
  const [afterLaunchBehavior, setState] = useState<AfterLaunchBehavior>(savedBehavior);
  const setAfterLaunchBehavior = (next: AfterLaunchBehavior) => {
    setState(next);
    try {
      window.localStorage.setItem(AFTER_LAUNCH_BEHAVIOR_STORAGE_KEY, next);
    } catch {
      // The choice still applies for this session when WebView storage is unavailable.
    }
  };
  return { afterLaunchBehavior, setAfterLaunchBehavior };
}
