import { useRef, useState } from "react";
import { applyRemoval, getRemovalPlan } from "./bridge";
import { clearPreflightLocalStorage } from "./desktopStorage";
import { resetHomePresentation } from "./useHomePresentation";
import { resetInstrumentMotion } from "./useInstrumentMotion";
import { resetInstrumentView } from "./useInstrumentView";
import { resetProfileSearch } from "./useProfileSearch";
import type { Announce, DesktopSnapshot, RemovalPlan, RemovalScope } from "./types";
import { errorMessage } from "./uiFormat";

export function useRemoval(
  platform: DesktopSnapshot["platform"] | undefined,
  announce: Announce,
  clearCache: () => void,
  clearProfiles: () => void,
  clearReportReceipt: () => void = () => undefined,
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
      if (!next.safe) {
        announce("Removal can’t continue. Review the reason below.", "warning");
      } else if (next.refusals.length > 0) {
        announce("Removal is ready to review. Some items could not be included.", "warning");
      } else {
        announce(next.targets.length === 0
          ? "There’s nothing in that removal scope."
          : "Removal is ready to review. Nothing has been removed.");
      }
    } catch (error) {
      if (currentRequest === request.current) announce(errorMessage(error), "error");
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
      let localStorageFailures: string[] = [];
      if (scope === "all-data") {
        localStorageFailures = clearPreflightLocalStorage();
        resetHomePresentation();
        resetInstrumentMotion();
        resetInstrumentView();
        resetProfileSearch();
        clearCache();
        clearProfiles();
        clearReportReceipt();
      }
      setPlan(null);
      const platformStep = platform === "windows"
        ? "Use Windows Installed apps to remove this desktop app."
        : platform === "linux"
          ? "Remove this desktop package with the package manager that installed it."
          : "Move this desktop app to the Trash when you’re ready.";
      if (localStorageFailures.length > 0) {
        announce(
          `${result.files.toLocaleString()} Preflight-owned files were removed, but the desktop could not clear ${localStorageFailures.length} local preference value${localStorageFailures.length === 1 ? "" : "s"}. ${platformStep}`,
          "warning",
        );
      } else {
        announce(`${result.files.toLocaleString()} Preflight-owned files removed. ${platformStep}`, "success");
      }
    } catch (error) {
      if (currentRequest === request.current) announce(errorMessage(error), "error");
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
