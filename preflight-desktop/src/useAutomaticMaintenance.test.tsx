import { act, renderHook } from "@testing-library/react";
import * as bridge from "./bridge";
import type { CacheCleanupPlan } from "./types";
import {
  AUTOMATIC_CACHE_LIMIT_BYTES,
  AUTOMATIC_DISCARDABLE_LIMIT_BYTES,
  AUTOMATIC_DISCARDABLE_LIMIT_FILES,
  AUTOMATIC_MAINTENANCE_SETTLE_MS,
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
  await act(async () => vi.advanceTimersByTimeAsync(AUTOMATIC_MAINTENANCE_SETTLE_MS - 1));
  expect(apply).not.toHaveBeenCalled();
  await act(async () => vi.advanceTimersByTimeAsync(1));
  expect(apply).toHaveBeenCalledTimes(1);

  rerender({ enabled: false, epoch: 0 });
  rerender({ enabled: true, epoch: 0 });
  await act(async () => vi.advanceTimersByTimeAsync(AUTOMATIC_MAINTENANCE_SETTLE_MS));
  expect(apply).toHaveBeenCalledTimes(1);

  rerender({ enabled: true, epoch: 1 });
  await act(async () => vi.advanceTimersByTimeAsync(AUTOMATIC_MAINTENANCE_SETTLE_MS));
  expect(apply).toHaveBeenCalledTimes(2);
});

test("maintenance failure stays silent and does not retry in a loop", async () => {
  vi.spyOn(bridge, "isDesktopHost").mockReturnValue(true);
  const apply = vi.spyOn(bridge, "applyEvidenceCleanup").mockRejectedValue(new Error("busy"));
  const { rerender } = renderHook(({ enabled, epoch }) => useAutomaticMaintenance(enabled, epoch), {
    initialProps: { enabled: true, epoch: 0 },
  });

  await act(async () => vi.advanceTimersByTimeAsync(AUTOMATIC_MAINTENANCE_SETTLE_MS));
  rerender({ enabled: false, epoch: 0 });
  rerender({ enabled: true, epoch: 0 });
  await act(async () => vi.advanceTimersByTimeAsync(AUTOMATIC_MAINTENANCE_SETTLE_MS));
  expect(apply).toHaveBeenCalledTimes(1);

  rerender({ enabled: true, epoch: 1 });
  await act(async () => vi.advanceTimersByTimeAsync(AUTOMATIC_MAINTENANCE_SETTLE_MS));
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
    reachableClasspathArchiveIndexes: 2,
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

  await act(async () => vi.advanceTimersByTimeAsync(AUTOMATIC_MAINTENANCE_SETTLE_MS));
  expect(plan).not.toHaveBeenCalled();
  expect(apply).not.toHaveBeenCalled();

  rerender({ cacheBytes: AUTOMATIC_CACHE_LIMIT_BYTES + 1 });
  await act(async () => vi.advanceTimersByTimeAsync(AUTOMATIC_MAINTENANCE_SETTLE_MS));
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
    reachableClasspathArchiveIndexes: 0,
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
  await act(async () => vi.advanceTimersByTimeAsync(AUTOMATIC_MAINTENANCE_SETTLE_MS));

  expect(apply).not.toHaveBeenCalled();
});

test.each([
  { discardableBytes: AUTOMATIC_DISCARDABLE_LIMIT_BYTES + 1, discardableFiles: 0 },
  { discardableBytes: 0, discardableFiles: AUTOMATIC_DISCARDABLE_LIMIT_FILES + 1 },
])("replaced cache data is pruned before the whole cache reaches 12 GB", async (discardable) => {
  vi.spyOn(bridge, "isDesktopHost").mockReturnValue(true);
  vi.spyOn(bridge, "applyEvidenceCleanup").mockRejectedValue(new Error("irrelevant"));
  const cachePlan: CacheCleanupPlan = {
    format: "starsector-preflight-cache-prune-v1",
    safe: true,
    applied: false,
    currentProfileFingerprint: "a".repeat(64),
    survivingProfileFingerprints: ["a".repeat(64)],
    bytes: 1024,
    files: 1,
    reachableTextureBlobs: 0,
    reachablePreparedAudioBlobs: 0,
    reachableClasspathArchiveIndexes: 0,
    refusals: [],
    groups: [{ reason: "replaced cache artifact", bytes: 1024, files: 1 }],
    removals: [],
    removalsTruncated: false,
  };
  const plan = vi.spyOn(bridge, "getCacheCleanup");
  const apply = vi.spyOn(bridge, "applyDiscardableCacheCleanup").mockResolvedValue({
    ...cachePlan,
    applied: true,
  });

  renderHook(() => useAutomaticMaintenance(true, 0, {
    game: "/Applications/Starsector",
    cacheBytes: 1024,
    ...discardable,
  }));
  await act(async () => vi.advanceTimersByTimeAsync(AUTOMATIC_MAINTENANCE_SETTLE_MS));

  expect(plan).not.toHaveBeenCalled();
  expect(apply).toHaveBeenCalledTimes(1);
});

test("replaced cache thresholds do not trigger cleanup at the boundary", async () => {
  vi.spyOn(bridge, "isDesktopHost").mockReturnValue(true);
  vi.spyOn(bridge, "applyEvidenceCleanup").mockRejectedValue(new Error("irrelevant"));
  const plan = vi.spyOn(bridge, "getCacheCleanup");

  renderHook(() => useAutomaticMaintenance(true, 0, {
    game: "/Applications/Starsector",
    cacheBytes: 1024,
    discardableBytes: AUTOMATIC_DISCARDABLE_LIMIT_BYTES,
    discardableFiles: AUTOMATIC_DISCARDABLE_LIMIT_FILES,
  }));
  await act(async () => vi.advanceTimersByTimeAsync(AUTOMATIC_MAINTENANCE_SETTLE_MS));

  expect(plan).not.toHaveBeenCalled();
});
