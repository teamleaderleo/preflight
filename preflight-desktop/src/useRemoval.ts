import { useRef, useState } from "react";
import { applyRemoval, getRemovalPlan } from "./bridge";
import type { Announce, DesktopSnapshot, RemovalPlan, RemovalScope } from "./types";
import {
  DISABLED_OPTIMIZATION_DOMAINS_STORAGE_KEY,
  OPTIMIZATION_PRESET_STORAGE_KEY,
} from "./useOptimizationPolicy";

export function useRemoval(
  platform: DesktopSnapshot["platform"] | undefined,
  announce: Announce,
  clearCache: () => void,
  clearProfiles: () => void,
) {
  const [plan, setPlan] = useState<RemovalPlan | null>(null);
  const [busy, setBusy] = useState(false);
  const request = useRef(0);
  const busyRef = useRef(false);

  const review = async (scope: RemovalScope) => {
    if (busyRef.current) return;
    const currentRequest = ++request.current;
    busyRef.current = true;
    setBusy(true);
    try {
      const next = await getRemovalPlan(scope);
      if (currentRequest !== request.current) return;
      setPlan(next);
      announce(next.targets.length === 0
        ? "There’s nothing in that removal scope."
        : "Removal is ready to review. Nothing has been removed.");
    } catch (error) {
      if (currentRequest === request.current) announce(String(error), "error");
    } finally {
      if (currentRequest === request.current) {
        busyRef.current = false;
        setBusy(false);
      }
    }
  };

  const remove = async () => {
    const reviewedPlan = plan;
    if (!reviewedPlan?.safe || reviewedPlan.targets.length === 0 || busyRef.current) return;
    const currentRequest = ++request.current;
    const scope = reviewedPlan.scope;
    busyRef.current = true;
    setBusy(true);
    try {
      const result = await applyRemoval(scope);
      if (currentRequest !== request.current) return;
      if (scope === "all-data") {
        try { window.localStorage.removeItem(OPTIMIZATION_PRESET_STORAGE_KEY); } catch { /* already removed on disk */ }
        try { window.localStorage.removeItem(DISABLED_OPTIMIZATION_DOMAINS_STORAGE_KEY); } catch { /* already removed on disk */ }
        clearCache();
        clearProfiles();
      }
      setPlan(null);
      const platformStep = platform === "windows"
        ? "Use Windows Installed apps to remove this desktop app."
        : platform === "linux"
          ? "Remove this desktop package with the package manager that installed it."
          : "Move this desktop app to the Trash when you’re ready.";
      announce(`${result.files.toLocaleString()} Preflight-owned files removed. ${platformStep}`, "success");
    } catch (error) {
      if (currentRequest === request.current) announce(String(error), "error");
    } finally {
      if (currentRequest === request.current) {
        busyRef.current = false;
        setBusy(false);
      }
    }
  };

  const dismiss = () => {
    request.current += 1;
    busyRef.current = false;
    setBusy(false);
    setPlan(null);
  };

  return { plan, busy, review, remove, dismiss };
}
