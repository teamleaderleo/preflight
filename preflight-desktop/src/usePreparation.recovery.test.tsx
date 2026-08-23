import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as bridge from "./bridge";
import * as tauriEvents from "./tauriEvents";
import type {
  OperationSnapshot,
  PreparationProgressEvent,
  PreparationStateEvent,
  RunStarted,
} from "./types";
import { usePreparation } from "./usePreparation";

afterEach(() => {
  vi.restoreAllMocks();
});

function activePreparation(pid = 77): OperationSnapshot {
  return {
    format: "preflight-operation-state-v1" as const,
    gamePid: null,
    gameRecovered: false,
    desktopSmokePid: null,
    desktopSmokeRunDirectory: null,
    preparationPid: pid,
    reportUploadId: null,
    reportUploadTotalBytes: null,
    diagnosticsExporting: false,
    updateChecking: false,
    updateInstalling: false,
  };
}

function idleOperation(): OperationSnapshot {
  return { ...activePreparation(), preparationPid: null };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function mockCacheInspection() {
  vi.spyOn(bridge, "getCacheInspection").mockResolvedValue({
    format: "starsector-preflight-cache-inspection-v1",
    cache: null,
    health: null,
  } as never);
}

function mockBalancedCacheReadyForCompact() {
  const profile = "a".repeat(64);
  vi.spyOn(bridge, "getCacheInspection").mockResolvedValue({
    format: "starsector-preflight-cache-inspection-v1",
    cache: {
      format: "starsector-preflight-cache-v1",
      root: "/cache",
      present: true,
      total: { bytes: 2_000_000_000, files: 20_000 },
      groups: [],
      uncategorizedBytes: 0,
      currentProfileFingerprint: profile,
      profiles: [{
        fingerprint: profile,
        current: true,
        bytes: 2_000_000_000,
        indexBytes: 10_000_000,
        manifestBytes: 5_000_000,
        lastModifiedMillis: 1,
      }],
    },
    health: {
      format: "starsector-preflight-cache-health-v1",
      status: "ready",
      profileFingerprint: profile,
      preparedTextures: true,
      textureStorage: "balanced",
      textureScope: "full",
      compactAvailable: true,
      issues: [],
      repairBytes: 0,
      repairFiles: 0,
    },
  });
}

function capturePreparationListeners() {
  let state: ((event: { payload: PreparationStateEvent }) => void) | null = null;
  let progress: ((event: { payload: PreparationProgressEvent }) => void) | null = null;
  vi.spyOn(tauriEvents, "listenWhileMounted").mockImplementation(((channel: string, onEvent: (event: unknown) => void) => {
    if (channel === "prepare-state") {
      state = onEvent as (event: { payload: PreparationStateEvent }) => void;
    }
    if (channel === "prepare-progress") {
      progress = onEvent as (event: { payload: PreparationProgressEvent }) => void;
    }
    return () => undefined;
  }) as typeof tauriEvents.listenWhileMounted);
  return {
    state: () => state,
    progress: () => progress,
  };
}

test("restart reconnects active preparation without inventing progress or auto-launching", async () => {
  vi.spyOn(bridge, "isDesktopHost").mockReturnValue(true);
  vi.spyOn(bridge, "getOperationState").mockResolvedValue(activePreparation());
  mockCacheInspection();
  const listeners = capturePreparationListeners();
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
    listeners.state()?.({
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

test("a completed run graduates learned Balanced data with a fresh Compact plan", async () => {
  vi.spyOn(bridge, "isDesktopHost").mockReturnValue(true);
  vi.spyOn(bridge, "getOperationState").mockResolvedValue(idleOperation());
  mockBalancedCacheReadyForCompact();
  vi.spyOn(tauriEvents, "listenWhileMounted")
    .mockImplementation((() => () => undefined) as typeof tauriEvents.listenWhileMounted);
  const basePlan = await bridge.getPreparationPlan("preview", "compact", 4);
  const plan = vi.spyOn(bridge, "getPreparationPlan").mockResolvedValue({
    ...basePlan,
    profileFingerprint: "a".repeat(64),
  });
  const start = vi.spyOn(bridge, "startPreparation").mockResolvedValue({ pid: 88 });
  const announce = vi.fn();

  const { unmount } = renderHook(() => usePreparation(
    "/Applications/Starsector",
    false,
    "recommended",
    vi.fn().mockResolvedValue(undefined),
    announce,
    1,
  ));

  await waitFor(() => expect(start).toHaveBeenCalledWith(
    "/Applications/Starsector",
    "compact",
    4,
    256,
  ));
  expect(plan).toHaveBeenCalledWith("/Applications/Starsector", "compact", 4);
  expect(announce).toHaveBeenCalledWith("Trimming prepared data for faster launches with less disk…");
  unmount();
});

test("reconnected preparation keeps cooperative stop available", async () => {
  vi.spyOn(bridge, "isDesktopHost").mockReturnValue(true);
  vi.spyOn(bridge, "getOperationState").mockResolvedValue(activePreparation());
  mockCacheInspection();
  const cancel = vi.spyOn(bridge, "cancelPreparation").mockResolvedValue(true);
  vi.spyOn(tauriEvents, "listenWhileMounted").mockImplementation((() => () => undefined) as typeof tauriEvents.listenWhileMounted);
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
  await act(async () => result.current.stopPreparation());

  expect(cancel).toHaveBeenCalledOnce();
  expect(result.current.preparationCancelling).toBe(true);
  expect(announce).toHaveBeenCalledWith("Stopping preparation safely…");
  unmount();
});

test("recovered completion cannot consume an unresolved local start or its launch intent", async () => {
  vi.spyOn(bridge, "isDesktopHost").mockReturnValue(true);
  const operation = deferred<OperationSnapshot>();
  let operationReads = 0;
  vi.spyOn(bridge, "getOperationState").mockImplementation(() => {
    operationReads += 1;
    return operationReads === 1 ? operation.promise : new Promise<OperationSnapshot>(() => undefined);
  });
  mockCacheInspection();
  const start = deferred<RunStarted>();
  vi.spyOn(bridge, "startPreparation").mockReturnValue(start.promise);
  const listeners = capturePreparationListeners();
  const announce = vi.fn();
  const launch = vi.fn().mockResolvedValue(undefined);

  const { result, unmount } = renderHook(() => usePreparation(
    "/Applications/Starsector",
    false,
    "off",
    launch,
    announce,
  ));

  let preparePromise!: Promise<void>;
  await act(async () => {
    preparePromise = result.current.prepare(true, "minimal");
    await Promise.resolve();
  });
  await waitFor(() => expect(bridge.startPreparation).toHaveBeenCalledOnce());

  await act(async () => operation.resolve(activePreparation(77)));
  await waitFor(() => expect(result.current.preparationPercent).toBeNull());

  await act(async () => {
    listeners.state()?.({
      payload: { state: "finished", pid: 77, success: true },
    });
  });
  expect(result.current.preparing).toBe(true);
  expect(launch).not.toHaveBeenCalled();

  await act(async () => {
    listeners.state()?.({
      payload: { state: "started", pid: 88 },
    });
    start.resolve({ pid: 88 });
    await preparePromise;
  });

  expect(result.current.preparing).toBe(true);
  expect(result.current.preparationPercent).toBe(0);

  await act(async () => {
    listeners.progress()?.({
      payload: {
        pid: 77,
        format: "preflight-preparation-progress-v1",
        phase: "rules",
        state: "completed",
        totalPhases: 2,
        metrics: {},
      },
    });
    listeners.state()?.({
      payload: { state: "finished", pid: 77, success: true },
    });
  });
  expect(result.current.preparationPercent).toBe(0);
  expect(result.current.preparing).toBe(true);
  expect(launch).not.toHaveBeenCalled();

  await act(async () => {
    listeners.progress()?.({
      payload: {
        pid: 88,
        format: "preflight-preparation-progress-v1",
        phase: "rules",
        state: "completed",
        totalPhases: 2,
        metrics: {},
      },
    });
  });
  expect(result.current.preparationPercent).toBe(50);

  await act(async () => {
    listeners.state()?.({
      payload: { state: "finished", pid: 88, success: true },
    });
  });
  await waitFor(() => expect(result.current.preparing).toBe(false));
  await waitFor(() => expect(launch).toHaveBeenCalledOnce());
  unmount();
});

test("exact local terminal is buffered until the start promise returns the same PID", async () => {
  vi.spyOn(bridge, "isDesktopHost").mockReturnValue(true);
  vi.spyOn(bridge, "getOperationState").mockResolvedValue(idleOperation());
  mockCacheInspection();
  const start = deferred<RunStarted>();
  vi.spyOn(bridge, "startPreparation").mockReturnValue(start.promise);
  const listeners = capturePreparationListeners();
  const announce = vi.fn();
  const launch = vi.fn().mockResolvedValue(undefined);

  const { result, unmount } = renderHook(() => usePreparation(
    "/Applications/Starsector",
    false,
    "off",
    launch,
    announce,
  ));

  let preparePromise!: Promise<void>;
  await act(async () => {
    preparePromise = result.current.prepare(true, "minimal");
    await Promise.resolve();
  });
  await waitFor(() => expect(bridge.startPreparation).toHaveBeenCalledOnce());

  await act(async () => {
    listeners.state()?.({ payload: { state: "started", pid: 88 } });
    listeners.state()?.({ payload: { state: "finished", pid: 88, success: true } });
  });
  expect(result.current.preparing).toBe(true);
  expect(launch).not.toHaveBeenCalled();

  await act(async () => {
    start.resolve({ pid: 88 });
    await preparePromise;
  });

  await waitFor(() => expect(result.current.preparing).toBe(false));
  await waitFor(() => expect(launch).toHaveBeenCalledOnce());
  expect(announce).toHaveBeenCalledWith("Preparation is complete. Opening Starsector…", "success");
  unmount();
});
