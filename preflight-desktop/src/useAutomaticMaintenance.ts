import { useEffect, useRef } from "react";
import { applyEvidenceCleanup, isDesktopHost } from "./bridge";

/** Keeps routine local evidence bounded without turning maintenance into another errand. */
export function useAutomaticMaintenance(enabled: boolean) {
  const attempted = useRef(false);

  useEffect(() => {
    if (!enabled || attempted.current || !isDesktopHost()) return;
    const timer = window.setTimeout(() => {
      attempted.current = true;
      void applyEvidenceCleanup().catch(() => {
        // Maintenance is optional. The explicit cleanup review is the recovery path.
      });
    }, 1_500);
    return () => window.clearTimeout(timer);
  }, [enabled]);
}
