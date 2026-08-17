import { act, renderHook } from "@testing-library/react";
import * as bridge from "./bridge";
import type { CacheCleanupPlan } from "./types";
import {
  AUTOMATIC_CACHE_LIMIT_BYTES,
  useAutomaticMaintenance,
} from "./useAutomaticMaintenance";

beforeEach(() => vi.useFakeTimers());

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

test("routine evidence is pruned after startup and each completed launch", async () => {
  vi.spyOn(bridge, "isDesktopHost").mockReturnValue(true);
  const apply = vi.spyOn(bridge, "applyEvidenceCleanup").mockResolvedValue({
    format: "starsector-preflight-evidence-prune-v1",
    applied: true,
    keepRuns: 10,
    keepBenchmarks: 5,
    bytes: 0,
    files: 0,
    removedBytes: 0,
    sessions: [],
  });
  const { rerender } = renderHook(({ enabled, epoch }) => useAutomaticMaintenance(enabled, epoch), {
    initialProps: { enabled: false, epoch: 0 },
  });

  rerender({ enabled: true, epoch: 0 });
  await act(async () => vi.advanceTimersByTimeAsync(1_500));
  expect(apply).toHaveBeenCalledTimes(1);

  rerender({ enabled: false, epoch: 0 });
  rerender({ enabled: true, epoch: 0 });
  await act(async () => vi.advanceTimersByTimeAsync(1_500));
  expect(apply).toHaveBeenCalledTimes(1);

  rerender({ enabled: true, epoch: 1 });
  await act(async () => vi.advanceTimersByTimeAsync(1_500));
  expect(apply).toHaveBeenCalledTimes(2);
});

test("maintenance failure stays silent and does not retry in a loop", async () => {
  vi.spyOn(bridge, "isDesktopHost").mockReturnValue(true);
  const apply = vi.spyOn(bridge, "applyEvidenceCleanup").mockRejectedValue(new Error("busy"));
  const { rerender } = renderHook(({ enabled, epoch }) => useAutomaticMaintenance(enabled, epoch), {
    initialProps: { enabled: true, epoch: 0 },
  });

  await act(async () => vi.advanceTimersByTimeAsync(1_500));
  rerender({ enabled: false, epoch: 0 });
  rerender({ enabled: true, epoch: 0 });
  await act(async () => vi.advanceTimersByTimeAsync(1_500));
  expect(apply).toHaveBeenCalledTimes(1);

  rerender({ enabled: true, epoch: 1 });
  await act(async () => vi.advanceTimersByTimeAsync(1_500));
  expect(apply).toHaveBeenCalledTimes(2);
});

test("old prepared profiles are pruned only above the automatic storage limit", async () => {
  vi.spyOn(bridge, "isDesktopHost").mockReturnValue(true);
  vi.spyOn(bridge, "applyEvidenceCleanup").mockResolvedValue({
    format: "starsector-preflight-evidence-prune-v1",
    applied: true,
    keepRuns: 10,
    keepBenchmarks: 5,
    bytes: 0,
    files: 0,
    removedBytes: 0,
    sessions: [],
  });
  const cachePlan: CacheCleanupPlan = {
    format: "starsector-preflight-cache-prune-v1",
    safe: true,
    applied: false,
    currentProfileFingerprint: "a".repeat(64),
    survivingProfileFingerprints: ["a".repeat(64)],
    bytes: 3 * 1024 * 1024 * 1024,
    files: 12,
    reachableTextureBlobs: 40,
    reachablePreparedAudioBlobs: 4,
    refusals: [],
    groups: [],
    removals: [],
    removalsTruncated: false,
  };
  const plan = vi.spyOn(bridge, "getCacheCleanup").mockResolvedValue(cachePlan);
  const apply = vi.spyOn(bridge, "applyCacheCleanup").mockResolvedValue({
    ...cachePlan,
    applied: true,
  });
  const cleaned = vi.fn();
  const { rerender } = renderHook(({ cacheBytes }) => useAutomaticMaintenance(true, 0, {
    game: "/Applications/Starsector",
    cacheBytes,
    onCacheCleaned: cleaned,
  }), { initialProps: { cacheBytes: AUTOMATIC_CACHE_LIMIT_BYTES } });

  await act(async () => vi.advanceTimersByTimeAsync(1_500));
  expect(plan).not.toHaveBeenCalled();
  expect(apply).not.toHaveBeenCalled();

  rerender({ cacheBytes: AUTOMATIC_CACHE_LIMIT_BYTES + 1 });
  await act(async () => vi.advanceTimersByTimeAsync(1_500));
  expect(plan).toHaveBeenCalledWith("/Applications/Starsector");
  expect(apply).toHaveBeenCalledWith("/Applications/Starsector");
  expect(cleaned).toHaveBeenCalledTimes(1);
});

test("an unsafe automatic cache plan removes nothing", async () => {
  vi.spyOn(bridge, "isDesktopHost").mockReturnValue(true);
  vi.spyOn(bridge, "applyEvidenceCleanup").mockRejectedValue(new Error("irrelevant"));
  vi.spyOn(bridge, "getCacheCleanup").mockResolvedValue({
    format: "starsector-preflight-cache-prune-v1",
    safe: false,
    applied: false,
    currentProfileFingerprint: null,
    survivingProfileFingerprints: [],
    bytes: 1024,
    files: 1,
    reachableTextureBlobs: 0,
    reachablePreparedAudioBlobs: 0,
    refusals: ["survivor manifest unreadable"],
    groups: [],
    removals: [],
    removalsTruncated: false,
  });
  const apply = vi.spyOn(bridge, "applyCacheCleanup");

  renderHook(() => useAutomaticMaintenance(true, 0, {
    game: "/Applications/Starsector",
    cacheBytes: AUTOMATIC_CACHE_LIMIT_BYTES + 1,
  }));
  await act(async () => vi.advanceTimersByTimeAsync(1_500));

  expect(apply).not.toHaveBeenCalled();
});
