import { renderHook, waitFor } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as bridge from "./bridge";
import * as tauriEvents from "./tauriEvents";
import { blockingWorkflow } from "./workflowPolicy";
import { useSignedUpdates } from "./useSignedUpdates";

afterEach(() => {
  vi.restoreAllMocks();
});

test("restart restores native update installation as the Settings-owned blocking workflow", async () => {
  vi.spyOn(bridge, "isDesktopHost").mockReturnValue(true);
  vi.spyOn(bridge, "getOperationState").mockResolvedValue({
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
    updateInstalling: true,
  });
  vi.spyOn(tauriEvents, "listenWhileMounted").mockImplementation((() => () => undefined) as typeof tauriEvents.listenWhileMounted);
  const announce = vi.fn();

  const { result, unmount } = renderHook(() => useSignedUpdates(false, false, announce));

  await waitFor(() => expect(result.current.updateInstalling).toBe(true));
  expect(announce).toHaveBeenCalledWith(
    "Reconnected to the verified Preflight update installation. Other actions stay paused until it finishes.",
  );
  expect(blockingWorkflow({
    preparing: false,
    preparationPercent: 0,
    cacheRepairing: false,
    choosingInstall: false,
    restoringOperation: false,
    desktopSmokeRunning: false,
    desktopSmokeCancelling: false,
    status: "ready",
    cleanupBusy: false,
    launcherSaving: false,
    profileBusy: false,
    diagnosticsBusy: false,
    reportUploading: false,
    reportFinalizing: false,
    reportCancelling: false,
    removalBusy: false,
    updateInstalling: result.current.updateInstalling,
  })).toEqual({
    owner: "settings",
    reason: "Installing the verified Preflight update",
  });

  unmount();
});
