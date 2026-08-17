import { describe, expect, test, vi } from "vitest";
import {
  AFTER_LAUNCH_BEHAVIOR_STORAGE_KEY,
  AUTOMATIC_UPDATE_CHECK_STORAGE_KEY,
  clearPreflightLocalStorage,
  DISABLED_OPTIMIZATION_DOMAINS_STORAGE_KEY,
  OPTIMIZATION_PRESET_STORAGE_KEY,
  PREFLIGHT_LOCAL_STORAGE_KEYS,
  INSTRUMENT_HULL_STORAGE_KEY,
  INSTRUMENT_HULL_TUNING_STORAGE_KEY,
  LAST_INSTALL_ROOT_STORAGE_KEY,
  PALETTE_STORAGE_KEY,
  readLastInstallRoot,
  rememberLastInstallRoot,
  REPORT_RECEIPT_STORAGE_KEY,
  SPEED_RECORD_STORAGE_KEY,
  THEME_STORAGE_KEY,
} from "./desktopStorage";

describe("desktop storage ownership", () => {
  test("inventory contains every current renderer persistence key exactly once", () => {
    expect(PREFLIGHT_LOCAL_STORAGE_KEYS).toEqual([
      THEME_STORAGE_KEY,
      PALETTE_STORAGE_KEY,
      OPTIMIZATION_PRESET_STORAGE_KEY,
      DISABLED_OPTIMIZATION_DOMAINS_STORAGE_KEY,
      REPORT_RECEIPT_STORAGE_KEY,
      SPEED_RECORD_STORAGE_KEY,
      AFTER_LAUNCH_BEHAVIOR_STORAGE_KEY,
      AUTOMATIC_UPDATE_CHECK_STORAGE_KEY,
      INSTRUMENT_HULL_STORAGE_KEY,
      INSTRUMENT_HULL_TUNING_STORAGE_KEY,
      LAST_INSTALL_ROOT_STORAGE_KEY,
    ]);
    expect(new Set(PREFLIGHT_LOCAL_STORAGE_KEYS).size).toBe(PREFLIGHT_LOCAL_STORAGE_KEYS.length);
  });

  test("the last installation is a disposable startup hint", () => {
    const values = new Map<string, string>();
    const storage = {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key),
    };

    rememberLastInstallRoot("/Applications/Starsector.app", storage);
    expect(readLastInstallRoot(storage)).toBe("/Applications/Starsector.app");

    rememberLastInstallRoot(null, storage);
    expect(readLastInstallRoot(storage)).toBeNull();
    values.set(LAST_INSTALL_ROOT_STORAGE_KEY, "x".repeat(32_769));
    expect(readLastInstallRoot(storage)).toBeNull();
  });

  test("all-data cleanup removes every owned key", () => {
    const removeItem = vi.fn();

    expect(clearPreflightLocalStorage({ removeItem })).toEqual([]);
    expect(removeItem.mock.calls.map(([key]) => key)).toEqual(PREFLIGHT_LOCAL_STORAGE_KEYS);
  });

  test("one denied removal does not prevent the remaining keys from being attempted", () => {
    const removeItem = vi.fn((key: string) => {
      if (key === REPORT_RECEIPT_STORAGE_KEY) throw new Error("denied");
    });

    expect(clearPreflightLocalStorage({ removeItem })).toEqual([REPORT_RECEIPT_STORAGE_KEY]);
    expect(removeItem).toHaveBeenCalledTimes(PREFLIGHT_LOCAL_STORAGE_KEYS.length);
  });
});
