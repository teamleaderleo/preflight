import { act, renderHook } from "@testing-library/react";
import * as bridge from "./bridge";
import { useAutomaticMaintenance } from "./useAutomaticMaintenance";

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
