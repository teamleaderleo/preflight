import { act, renderHook } from "@testing-library/react";
import { SPEED_RECORD_STORAGE_KEY } from "./desktopStorage";
import { readSpeedRecord, useSpeedRecord } from "./useSpeedRecord";
import type { DesktopBenchmarkComparison } from "./types";

const PROFILE = "12".repeat(32);
const BENCHMARK_IDENTITY = "34".repeat(32);

beforeEach(() => {
  window.localStorage.clear();
});

function comparison(
  measurementOnly: number,
  optimized: number,
  profileFingerprint = PROFILE,
  benchmarkIdentitySha256 = BENCHMARK_IDENTITY,
): DesktopBenchmarkComparison {
  return {
    available: true,
    identity: { profileFingerprint, benchmarkIdentitySha256 },
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

test("controlled benchmark history survives the app being closed", () => {
  const first = renderHook(() => useSpeedRecord());
  act(() => first.result.current.rememberBenchmark(comparison(88_130, 15_880)));
  first.unmount();

  const second = renderHook(() => useSpeedRecord());
  expect(second.result.current.record?.personalBest.optimizedMs).toBe(15_880);
  expect(second.result.current.record?.latest.optimizedMs).toBe(15_880);
  expect(second.result.current.record?.version).toBe(3);
});

test("a disappointing rerun becomes latest evidence without erasing the personal best", () => {
  const { result } = renderHook(() => useSpeedRecord());

  act(() => result.current.rememberBenchmark(comparison(100_000, 20_000)));
  const bestRecordedAt = result.current.record?.personalBest.recordedAt;
  act(() => result.current.rememberBenchmark(comparison(100_000, 80_000, "56".repeat(32), "78".repeat(32))));

  expect(result.current.record?.personalBest.optimizedMs).toBe(20_000);
  expect(result.current.record?.personalBest.recordedAt).toBe(bestRecordedAt);
  expect(result.current.record?.latest.optimizedMs).toBe(80_000);
  expect(result.current.record?.latest.profileFingerprint).toBe("56".repeat(32));
});

test("a better measured improvement becomes the new personal best", () => {
  const { result } = renderHook(() => useSpeedRecord());

  act(() => result.current.rememberBenchmark(comparison(100_000, 40_000)));
  act(() => result.current.rememberBenchmark(comparison(80_000, 16_000, "56".repeat(32), "78".repeat(32))));

  expect(result.current.record?.personalBest.optimizedMs).toBe(16_000);
  expect(result.current.record?.latest.optimizedMs).toBe(16_000);
});

test("ordinary profile-only launches do not accumulate benchmark-derived savings", () => {
  const { result } = renderHook(() => useSpeedRecord());
  act(() => result.current.rememberBenchmark(comparison(88_130, 15_880)));
  const before = result.current.record;

  act(() => result.current.countFastLaunch(PROFILE));
  act(() => result.current.countFastLaunch(PROFILE));

  expect(result.current.record).toEqual(before);
});

test("a version 2 record migrates to trophy and latest history without legacy launch totals", () => {
  window.localStorage.setItem(SPEED_RECORD_STORAGE_KEY, JSON.stringify({
    version: 2,
    profileFingerprint: PROFILE,
    benchmarkIdentitySha256: BENCHMARK_IDENTITY,
    measurementOnlyMs: 88_130,
    optimizedMs: 15_880,
    recordedAt: "2026-07-02T10:00:00.000Z",
    fastLaunches: 40,
    bankedLaunches: 12,
    bankedSavedMs: 999_999,
  }));

  const record = readSpeedRecord();
  expect(record).toEqual({
    version: 3,
    personalBest: {
      profileFingerprint: PROFILE,
      benchmarkIdentitySha256: BENCHMARK_IDENTITY,
      measurementOnlyMs: 88_130,
      optimizedMs: 15_880,
      recordedAt: "2026-07-02T10:00:00.000Z",
    },
    latest: {
      profileFingerprint: PROFILE,
      benchmarkIdentitySha256: BENCHMARK_IDENTITY,
      measurementOnlyMs: 88_130,
      optimizedMs: 15_880,
      recordedAt: "2026-07-02T10:00:00.000Z",
    },
  });
});

test("an unavailable or zero-duration comparison is not recorded", () => {
  const { result } = renderHook(() => useSpeedRecord());

  act(() => result.current.rememberBenchmark(null));
  act(() => result.current.rememberBenchmark({ available: false, metrics: {}, context: {} } as DesktopBenchmarkComparison));
  act(() => result.current.rememberBenchmark(comparison(88_130, 0)));
  expect(result.current.record).toBeNull();
});

test("a corrupted stored record is discarded", () => {
  window.localStorage.setItem(SPEED_RECORD_STORAGE_KEY, JSON.stringify({
    version: 3,
    personalBest: {
      profileFingerprint: PROFILE,
      benchmarkIdentitySha256: BENCHMARK_IDENTITY,
      measurementOnlyMs: 88_130,
      optimizedMs: 0,
      recordedAt: "2026-07-02T10:00:00.000Z",
    },
    latest: {},
  }));

  expect(readSpeedRecord()).toBeNull();
  expect(window.localStorage.getItem(SPEED_RECORD_STORAGE_KEY)).toBeNull();
});
