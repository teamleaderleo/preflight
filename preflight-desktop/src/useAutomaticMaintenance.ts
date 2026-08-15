import { useEffect, useRef } from "react";
import { applyEvidenceCleanup, isDesktopHost } from "./bridge";

/** Keeps routine local evidence bounded without turning maintenance into another errand. */
export function useAutomaticMaintenance(enabled: boolean, epoch: number) {
  const lastAttemptedEpoch = useRef<number | null>(null);

  useEffect(() => {
    if (!enabled || lastAttemptedEpoch.current === epoch || !isDesktopHost()) return;
    const timer = window.setTimeout(() => {
      lastAttemptedEpoch.current = epoch;
      void applyEvidenceCleanup().catch(() => {
        // Maintenance is optional. The explicit cleanup review is the recovery path.
      });
    }, 1_500);
    return () => window.clearTimeout(timer);
  }, [enabled, epoch]);
}
