export const THEME_STORAGE_KEY = "preflight.theme";
export const OPTIMIZATION_PRESET_STORAGE_KEY = "preflight.optimizationPreset";
export const DISABLED_OPTIMIZATION_DOMAINS_STORAGE_KEY = "preflight.disabledOptimizationDomains";
export const REPORT_RECEIPT_STORAGE_KEY = "preflight.reportReceipt";
export const SPEED_RECORD_STORAGE_KEY = "preflight.speedRecord";
export const AFTER_LAUNCH_BEHAVIOR_STORAGE_KEY = "preflight.afterLaunchBehavior";

/** Every Preflight-owned value persisted in the desktop renderer/WebView localStorage. */
export const PREFLIGHT_LOCAL_STORAGE_KEYS = Object.freeze([
  THEME_STORAGE_KEY,
  OPTIMIZATION_PRESET_STORAGE_KEY,
  DISABLED_OPTIMIZATION_DOMAINS_STORAGE_KEY,
  REPORT_RECEIPT_STORAGE_KEY,
  SPEED_RECORD_STORAGE_KEY,
  AFTER_LAUNCH_BEHAVIOR_STORAGE_KEY,
] as const);

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
