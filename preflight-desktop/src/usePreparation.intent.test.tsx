import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as bridge from "./bridge";
import * as tauriEvents from "./tauriEvents";
import type { OperationSnapshot, RunStarted } from "./types";
import { usePreparation } from "./usePreparation";

afterEach(() => {
  vi.restoreAllMocks();
});

function idleOperation(): OperationSnapshot {
  return {
    format: "preflight-operation-state-v1",
    gamePid: null,
    gameRecovered: false,
    desktopSmokePid: null,
    desktopSmokeRunDirectory: null,
    preparationPid: null,
    reportUploadId: null,
    reportUploadTotalBytes: null,
    diagnosticsExporting: false,
    updateChecking: false,
    updateInstalling: false,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((finish) => {
    resolve = finish;
  });
  return { promise, resolve };
}

function arrangeHost(preparationPid: number | null = null) {
  vi.spyOn(bridge, "isDesktopHost").mockReturnValue(true);
  vi.spyOn(bridge, "getOperationState").mockResolvedValue({ ...idleOperation(), preparationPid });
  vi.spyOn(bridge, "getCacheInspection").mockResolvedValue({
    format: "starsector-preflight-cache-inspection-v1",
    cache: null,
    health: null,
  } as never);
  vi.spyOn(tauriEvents, "listenWhileMounted")
    .mockImplementation((() => () => undefined) as typeof tauriEvents.listenWhileMounted);
}

test.each([
  [true, "launch"],
  [false, "stay-closed"],
] as const)("local preparation exposes its completion intent (%s)", async (launchWhenReady, expectedIntent) => {
  arrangeHost();
  const start = deferred<RunStarted>();
  vi.spyOn(bridge, "startPreparation").mockReturnValue(start.promise);
  const { result, unmount } = renderHook(() => usePreparation(
    "/Applications/Starsector",
    false,
    "recommended",
    vi.fn().mockResolvedValue(undefined),
    vi.fn(),
  ));

  await act(async () => {
    void result.current.prepare(launchWhenReady, "minimal");
    await Promise.resolve();
  });

  await waitFor(() => expect(result.current.preparing).toBe(true));
  expect(result.current.preparationCompletionIntent).toBe(expectedIntent);
  start.resolve({ pid: 88 });
  unmount();
});

test("recovered preparation exposes the safe stay-closed intent", async () => {
  arrangeHost(77);
  const { result, unmount } = renderHook(() => usePreparation(
    "/Applications/Starsector",
    false,
    "recommended",
    vi.fn().mockResolvedValue(undefined),
    vi.fn(),
  ));

  await waitFor(() => expect(result.current.preparing).toBe(true));
  expect(result.current.preparationCompletionIntent).toBe("stay-closed");
  expect(result.current.preparationPercent).toBeNull();
  unmount();
});
