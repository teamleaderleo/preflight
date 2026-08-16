import { describe, expect, test, vi } from "vitest";
import {
  AFTER_LAUNCH_BEHAVIOR_STORAGE_KEY,
  AUTOMATIC_UPDATE_CHECK_STORAGE_KEY,
  clearPreflightLocalStorage,
  DISABLED_OPTIMIZATION_DOMAINS_STORAGE_KEY,
  OPTIMIZATION_PRESET_STORAGE_KEY,
  PREFLIGHT_LOCAL_STORAGE_KEYS,
  REPORT_RECEIPT_STORAGE_KEY,
  SPEED_RECORD_STORAGE_KEY,
  THEME_STORAGE_KEY,
} from "./desktopStorage";

describe("desktop storage ownership", () => {
  test("inventory contains every current renderer persistence key exactly once", () => {
    expect(PREFLIGHT_LOCAL_STORAGE_KEYS).toEqual([
      THEME_STORAGE_KEY,
      OPTIMIZATION_PRESET_STORAGE_KEY,
      DISABLED_OPTIMIZATION_DOMAINS_STORAGE_KEY,
      REPORT_RECEIPT_STORAGE_KEY,
      SPEED_RECORD_STORAGE_KEY,
      AFTER_LAUNCH_BEHAVIOR_STORAGE_KEY,
      AUTOMATIC_UPDATE_CHECK_STORAGE_KEY,
    ]);
    expect(new Set(PREFLIGHT_LOCAL_STORAGE_KEYS).size).toBe(PREFLIGHT_LOCAL_STORAGE_KEYS.length);
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
