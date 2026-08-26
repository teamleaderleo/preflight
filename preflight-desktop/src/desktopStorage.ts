export const THEME_STORAGE_KEY = "preflight.theme";
export const PALETTE_STORAGE_KEY = "preflight.palette";
export const OPTIMIZATION_PRESET_STORAGE_KEY = "preflight.optimizationPreset";
export const DISABLED_OPTIMIZATION_DOMAINS_STORAGE_KEY = "preflight.disabledOptimizationDomains";
export const REPORT_RECEIPT_STORAGE_KEY = "preflight.reportReceipt";
export const SPEED_RECORD_STORAGE_KEY = "preflight.speedRecord";
export const AFTER_LAUNCH_BEHAVIOR_STORAGE_KEY = "preflight.afterLaunchBehavior";
export const AUTOMATIC_UPDATE_CHECK_STORAGE_KEY = "preflight.automaticUpdateChecks";
// Retained so full removal clears preferences written by pre-beta development builds.
export const AUTOMATIC_RUN_REPORTS_STORAGE_KEY = "preflight.automaticRunReports";
export const AUTOMATIC_RUN_REPORT_HISTORY_STORAGE_KEY = "preflight.automaticRunReportHistory.v1";
export const INSTRUMENT_HULL_STORAGE_KEY = "preflight.instrumentHull";
export const INSTRUMENT_HULL_TUNING_STORAGE_KEY = "preflight.instrumentHullTuning.v1";
export const INSTRUMENT_HULL_MOTION_STORAGE_KEY = "preflight.instrumentHullMotion.v1";
export const INSTRUMENT_HULL_ROSTER_STORAGE_KEY = "preflight.instrumentHullRoster.v1";
export const INSTRUMENT_HULL_VIEW_STORAGE_KEY = "preflight.instrumentHullView.v1";
export const HOME_PRESENTATION_STORAGE_KEY = "preflight.homePresentation.v1";
export const PROFILE_SEARCH_STORAGE_KEY = "preflight.profileSearch.v1";
export const LAST_INSTALL_ROOT_STORAGE_KEY = "preflight.lastInstallRoot";
export const SIDEBAR_STORAGE_KEY = "preflight.sidebar";
export const HOME_OPTIONS_STORAGE_KEY = "preflight.homeOptions";

/** Every Preflight-owned value persisted in the desktop renderer/WebView localStorage. */
export const PREFLIGHT_LOCAL_STORAGE_KEYS = Object.freeze([
  THEME_STORAGE_KEY,
  PALETTE_STORAGE_KEY,
  OPTIMIZATION_PRESET_STORAGE_KEY,
  DISABLED_OPTIMIZATION_DOMAINS_STORAGE_KEY,
  REPORT_RECEIPT_STORAGE_KEY,
  SPEED_RECORD_STORAGE_KEY,
  AFTER_LAUNCH_BEHAVIOR_STORAGE_KEY,
  AUTOMATIC_UPDATE_CHECK_STORAGE_KEY,
  AUTOMATIC_RUN_REPORTS_STORAGE_KEY,
  AUTOMATIC_RUN_REPORT_HISTORY_STORAGE_KEY,
  INSTRUMENT_HULL_STORAGE_KEY,
  INSTRUMENT_HULL_TUNING_STORAGE_KEY,
  INSTRUMENT_HULL_MOTION_STORAGE_KEY,
  INSTRUMENT_HULL_ROSTER_STORAGE_KEY,
  INSTRUMENT_HULL_VIEW_STORAGE_KEY,
  HOME_PRESENTATION_STORAGE_KEY,
  PROFILE_SEARCH_STORAGE_KEY,
  LAST_INSTALL_ROOT_STORAGE_KEY,
  SIDEBAR_STORAGE_KEY,
  HOME_OPTIONS_STORAGE_KEY,
] as const);

const MAX_INSTALL_ROOT_CHARACTERS = 32_768;

export function readLastInstallRoot(storage: Pick<Storage, "getItem"> = window.localStorage): string | null {
  try {
    const value = storage.getItem(LAST_INSTALL_ROOT_STORAGE_KEY);
    return value && value.length <= MAX_INSTALL_ROOT_CHARACTERS ? value : null;
  } catch {
    return null;
  }
}

export function rememberLastInstallRoot(
  installRoot: string | null,
  storage: Pick<Storage, "setItem" | "removeItem"> = window.localStorage,
): void {
  try {
    if (installRoot && installRoot.length <= MAX_INSTALL_ROOT_CHARACTERS) {
      storage.setItem(LAST_INSTALL_ROOT_STORAGE_KEY, installRoot);
    } else {
      storage.removeItem(LAST_INSTALL_ROOT_STORAGE_KEY);
    }
  } catch {
    // A denied WebView store only costs the next launch an automatic discovery pass.
  }
}

export interface LocalStorageRemoval {
  removeItem(key: string): void;
}

/** Best-effort cleanup: one denied key must not leave the rest of Preflight's state behind. */
export function clearPreflightLocalStorage(storage: LocalStorageRemoval = window.localStorage): string[] {
  const failures: string[] = [];
  for (const key of PREFLIGHT_LOCAL_STORAGE_KEYS) {
    try {
      storage.removeItem(key);
    } catch {
      failures.push(key);
    }
  }
  return failures;
}
