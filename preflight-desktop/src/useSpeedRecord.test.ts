import { act, renderHook } from "@testing-library/react";
import { SPEED_RECORD_STORAGE_KEY } from "./desktopStorage";
import { readSpeedRecord, useSpeedRecord } from "./useSpeedRecord";
import type { DesktopBenchmarkComparison } from "./types";

beforeEach(() => {
  window.localStorage.clear();
});

function comparison(measurementOnly: number, optimized: number): DesktopBenchmarkComparison {
  return {
    available: true,
    metrics: {
      processToMainMenuMs: {
        measurementOnly,
        optimized,
        delta: optimized - measurementOnly,
        improvementPercent: (1 - optimized / measurementOnly) * 100,
      },
    },
    context: {},
  };
}

test("a measurement survives the app being closed", () => {
  const first = renderHook(() => useSpeedRecord());
  act(() => first.result.current.rememberBenchmark(comparison(88_130, 15_880)));
  first.unmount();

  const second = renderHook(() => useSpeedRecord());
  expect(second.result.current.multiplier).toBeCloseTo(5.55, 2);
  expect(second.result.current.savedPerLaunchMs).toBe(72_250);
});

test("launches only count toward the total while optimizations were measured", () => {
  const { result } = renderHook(() => useSpeedRecord());

  // Counting before anything was measured has no figure to multiply, and inventing one would put
  // a number on screen that no measurement supports.
  act(() => result.current.countFastLaunch());
  expect(result.current.record).toBeNull();
  expect(result.current.totalSavedMs).toBe(0);

  act(() => result.current.rememberBenchmark(comparison(88_130, 15_880)));
  act(() => result.current.countFastLaunch());
  act(() => result.current.countFastLaunch());
  expect(result.current.totalSavedMs).toBe(144_500);
});

/*
 * Those launches really were that much faster at the time. Recomputing the whole history against
 * a newer multiplier would move a number the user has already read, in either direction, for a
 * reason that has nothing to do with what they did.
 */
test("a newer measurement banks the total earned under the old one", () => {
  const { result } = renderHook(() => useSpeedRecord());

  act(() => result.current.rememberBenchmark(comparison(88_130, 15_880)));
  act(() => result.current.countFastLaunch());
  act(() => result.current.countFastLaunch());

  act(() => result.current.rememberBenchmark(comparison(40_000, 20_000)));
  expect(result.current.record?.bankedSavedMs).toBe(144_500);
  expect(result.current.record?.fastLaunches).toBe(0);
  expect(result.current.totalSavedMs).toBe(144_500);

  act(() => result.current.countFastLaunch());
  expect(result.current.totalSavedMs).toBe(164_500);
});

test("an unavailable or zero-duration comparison is not recorded", () => {
  const { result } = renderHook(() => useSpeedRecord());

  act(() => result.current.rememberBenchmark(null));
  act(() => result.current.rememberBenchmark({ available: false, metrics: {}, context: {} } as DesktopBenchmarkComparison));
  act(() => result.current.rememberBenchmark(comparison(88_130, 0)));
  expect(result.current.record).toBeNull();
});

test("a corrupted stored record is discarded rather than divided by", () => {
  window.localStorage.setItem(SPEED_RECORD_STORAGE_KEY, JSON.stringify({
    version: 1,
    measurementOnlyMs: 88_130,
    optimizedMs: 0,
    recordedAt: "2026-07-02T10:00:00.000Z",
    fastLaunches: 4,
    bankedSavedMs: 0,
  }));

  expect(readSpeedRecord()).toBeNull();
  expect(window.localStorage.getItem(SPEED_RECORD_STORAGE_KEY)).toBeNull();
});
