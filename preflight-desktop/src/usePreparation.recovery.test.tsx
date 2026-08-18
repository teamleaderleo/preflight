import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as bridge from "./bridge";
import * as tauriEvents from "./tauriEvents";
import type { PreparationStateEvent } from "./types";
import { usePreparation } from "./usePreparation";

afterEach(() => {
  vi.restoreAllMocks();
});

function activePreparation() {
  return {
    format: "preflight-operation-state-v1" as const,
    gamePid: null,
    gameRecovered: false,
    desktopSmokePid: null,
    desktopSmokeRunDirectory: null,
    preparationPid: 77,
    reportUploadId: null,
    reportUploadTotalBytes: null,
    diagnosticsExporting: false,
    updateChecking: false,
    updateInstalling: false,
  };
}

test("restart reconnects active preparation without inventing progress or auto-launching", async () => {
  let preparationStateListener: ((event: { payload: PreparationStateEvent }) => void) | null = null;
  vi.spyOn(bridge, "isDesktopHost").mockReturnValue(true);
  vi.spyOn(bridge, "getOperationState").mockResolvedValue(activePreparation());
  vi.spyOn(bridge, "getCacheInspection").mockResolvedValue({
    format: "starsector-preflight-cache-inspection-v1",
    cache: null,
    health: null,
  } as never);
  vi.spyOn(tauriEvents, "listenWhileMounted").mockImplementation(((channel: string, onEvent: (event: unknown) => void) => {
    if (channel === "prepare-state") {
      preparationStateListener = onEvent as (event: { payload: PreparationStateEvent }) => void;
    }
    return () => undefined;
  }) as typeof tauriEvents.listenWhileMounted);
  const announce = vi.fn();
  const launch = vi.fn().mockResolvedValue(undefined);

  const { result, unmount } = renderHook(() => usePreparation(
    "/Applications/Starsector",
    false,
    "off",
    launch,
    announce,
  ));

  await waitFor(() => expect(result.current.preparing).toBe(true));
  expect(result.current.preparationPercent).toBeNull();
  expect(announce).toHaveBeenCalledWith(expect.stringContaining("Reconnected to profile preparation"));

  await act(async () => {
    preparationStateListener?.({
      payload: {
        state: "finished",
        pid: 77,
        success: true,
        detail: undefined,
      },
    });
  });

  await waitFor(() => expect(result.current.preparing).toBe(false));
  expect(launch).not.toHaveBeenCalled();
  expect(announce).toHaveBeenCalledWith("Preparation is complete. The current profile is ready.", "success");
  unmount();
});

test("reconnected preparation keeps cooperative stop available", async () => {
  vi.spyOn(bridge, "isDesktopHost").mockReturnValue(true);
  vi.spyOn(bridge, "getOperationState").mockResolvedValue(activePreparation());
  vi.spyOn(bridge, "getCacheInspection").mockResolvedValue({
    format: "starsector-preflight-cache-inspection-v1",
    cache: null,
    health: null,
  } as never);
  const cancel = vi.spyOn(bridge, "cancelPreparation").mockResolvedValue(true);
  vi.spyOn(tauriEvents, "listenWhileMounted").mockImplementation((() => () => undefined) as typeof tauriEvents.listenWhileMounted);
  const announce = vi.fn();

  const { result, unmount } = renderHook(() => usePreparation(
    "/Applications/Starsector",
    false,
    "off",
    vi.fn().mockResolvedValue(undefined),
    announce,
  ));

  await waitFor(() => expect(result.current.preparing).toBe(true));
  await act(async () => result.current.stopPreparation());

  expect(cancel).toHaveBeenCalledOnce();
  expect(result.current.preparationCancelling).toBe(true);
  expect(announce).toHaveBeenCalledWith("Stopping preparation safely…");
  unmount();
});
