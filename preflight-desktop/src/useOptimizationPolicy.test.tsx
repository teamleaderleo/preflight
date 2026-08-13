import { act, renderHook, waitFor } from "@testing-library/react";
import {
  DISABLED_OPTIMIZATION_DOMAINS_STORAGE_KEY,
  OPTIMIZATION_PRESET_STORAGE_KEY,
  useOptimizationPolicy,
} from "./useOptimizationPolicy";

beforeEach(() => {
  window.localStorage.clear();
});

test("defaults to the recommended policy with every prepared domain enabled", async () => {
  const { result } = renderHook(() => useOptimizationPolicy());

  expect(result.current.optimizationPreset).toBe("recommended");
  expect(result.current.disabledOptimizationDomains).toEqual([]);
  await waitFor(() => {
    expect(window.localStorage.getItem(OPTIMIZATION_PRESET_STORAGE_KEY)).toBe("recommended");
    expect(window.localStorage.getItem(DISABLED_OPTIMIZATION_DOMAINS_STORAGE_KEY)).toBe("[]");
  });
});

test("restores only the closed domain contract and canonicalizes persisted state", async () => {
  window.localStorage.setItem(OPTIMIZATION_PRESET_STORAGE_KEY, "conservative");
  window.localStorage.setItem(
    DISABLED_OPTIMIZATION_DOMAINS_STORAGE_KEY,
    '["prepared-textures","unknown","prepared-textures","prepared-audio"]',
  );

  const { result } = renderHook(() => useOptimizationPolicy());

  expect(result.current.optimizationPreset).toBe("conservative");
  expect(result.current.disabledOptimizationDomains).toEqual(["prepared-textures", "prepared-audio"]);
  await waitFor(() => expect(window.localStorage.getItem(DISABLED_OPTIMIZATION_DOMAINS_STORAGE_KEY))
    .toBe('["prepared-textures","prepared-audio"]'));
});

test("domain updates are idempotent and persist with preset changes", async () => {
  const { result } = renderHook(() => useOptimizationPolicy());

  act(() => {
    result.current.setOptimizationDomainEnabled("prepared-audio", false);
    result.current.setOptimizationDomainEnabled("prepared-audio", false);
    result.current.setOptimizationPreset("off");
  });
  expect(result.current.disabledOptimizationDomains).toEqual(["prepared-audio"]);
  expect(result.current.optimizationPreset).toBe("off");

  act(() => result.current.setOptimizationDomainEnabled("prepared-audio", true));
  expect(result.current.disabledOptimizationDomains).toEqual([]);
  await waitFor(() => {
    expect(window.localStorage.getItem(OPTIMIZATION_PRESET_STORAGE_KEY)).toBe("off");
    expect(window.localStorage.getItem(DISABLED_OPTIMIZATION_DOMAINS_STORAGE_KEY)).toBe("[]");
  });
});

test("invalid persisted values fall back to the safe product defaults", () => {
  window.localStorage.setItem(OPTIMIZATION_PRESET_STORAGE_KEY, "custom");
  window.localStorage.setItem(DISABLED_OPTIMIZATION_DOMAINS_STORAGE_KEY, "not json");

  const { result } = renderHook(() => useOptimizationPolicy());

  expect(result.current.optimizationPreset).toBe("recommended");
  expect(result.current.disabledOptimizationDomains).toEqual([]);
});
