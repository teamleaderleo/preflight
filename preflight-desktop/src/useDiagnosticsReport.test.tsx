import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import * as bridge from "./bridge";
import {
  AUTOMATIC_RUN_REPORT_HISTORY_STORAGE_KEY,
  AUTOMATIC_RUN_REPORTS_STORAGE_KEY,
} from "./desktopStorage";
import { useDiagnosticsReport } from "./useDiagnosticsReport";

beforeEach(() => window.localStorage.clear());
afterEach(() => vi.restoreAllMocks());

test("automatic failed-run reporting requires durable versioned consent and deduplicates an exact run", async () => {
  const baseline = await bridge.getSnapshot();
  const run = {
    directory: "/Users/captain/.starsector-preflight/runs/20260817-010203-abcd1234",
    modifiedAt: "2026-08-17T01:02:03Z",
    adapterHealth: null,
    started: "2026-08-17T01:02:03Z",
    ended: "2026-08-17T01:02:04Z",
    wrapperPid: 4242,
    wrapperStartedAt: "2026-08-17T01:02:02Z",
    startupMillis: null,
    outcome: "LAUNCHER_EXIT_NONZERO",
    exitCode: 1,
  };
  vi.spyOn(bridge, "getSnapshot").mockResolvedValue({ ...baseline, lastRun: run });
  const exported = await bridge.exportDiagnostics("/tmp/baseline.zip");
  const exportAutomatic = vi.spyOn(bridge, "exportAutomaticDiagnostics").mockResolvedValue(exported);
  const send = vi.spyOn(bridge, "sendRunReport");
  const announce = vi.fn();
  const { result } = renderHook(() => useDiagnosticsReport(false, announce));

  expect(result.current.automaticRunReports).toBe(false);
  act(() => result.current.setAutomaticRunReports(true));
  expect(JSON.parse(window.localStorage.getItem(AUTOMATIC_RUN_REPORTS_STORAGE_KEY) ?? "{}"))
    .toMatchObject({ protocolVersion: 1, disclosureVersion: 1, enabled: true });

  await act(async () => result.current.submitAutomaticFailedRunReport({
    game: "/Applications/Starsector",
    wrapperPid: 4242,
  }));
  await act(async () => result.current.submitAutomaticFailedRunReport({
    game: "/Applications/Starsector",
    wrapperPid: 4242,
  }));

  expect(exportAutomatic).toHaveBeenCalledOnce();
  expect(exportAutomatic).toHaveBeenCalledWith("20260817-010203-abcd1234");
  expect(send).toHaveBeenCalledOnce();
  expect(JSON.parse(window.localStorage.getItem(AUTOMATIC_RUN_REPORT_HISTORY_STORAGE_KEY) ?? "{}"))
    .toMatchObject({ protocolVersion: 1, runIdentities: [expect.stringContaining("4242")] });
});

test("a stale automatic-report preference cannot bypass an unconfigured local-only intake", async () => {
  window.localStorage.setItem(AUTOMATIC_RUN_REPORTS_STORAGE_KEY, JSON.stringify({
    protocolVersion: 1,
    disclosureVersion: 1,
    enabled: true,
    decidedAt: "2026-08-18T00:00:00Z",
  }));
  const intake = vi.spyOn(bridge, "getReportIntakeStatus").mockResolvedValue({
    configured: false,
    origin: null,
    reason: "Remote reporting is disabled in this beta.",
  });
  const exportAutomatic = vi.spyOn(bridge, "exportAutomaticDiagnostics");
  const send = vi.spyOn(bridge, "sendRunReport");
  const snapshot = vi.spyOn(bridge, "getSnapshot");
  const { result } = renderHook(() => useDiagnosticsReport(false, vi.fn()));

  expect(result.current.automaticRunReports).toBe(true);
  await act(async () => result.current.submitAutomaticFailedRunReport({
    game: "/Applications/Starsector",
    wrapperPid: 4242,
  }));

  expect(intake).toHaveBeenCalledOnce();
  expect(snapshot).not.toHaveBeenCalled();
  expect(exportAutomatic).not.toHaveBeenCalled();
  expect(send).not.toHaveBeenCalled();
  expect(window.localStorage.getItem(AUTOMATIC_RUN_REPORT_HISTORY_STORAGE_KEY)).toBeNull();
});

test("an unrelated or successful latest run is never uploaded for a failed process event", async () => {
  const baseline = await bridge.getSnapshot();
  vi.spyOn(bridge, "getSnapshot").mockResolvedValue({
    ...baseline,
    lastRun: {
      directory: "/Users/captain/.starsector-preflight/runs/20260817-010203-abcd1234",
      modifiedAt: "2026-08-17T01:02:03Z",
      adapterHealth: null,
      started: "2026-08-17T01:02:03Z",
      ended: "2026-08-17T01:02:04Z",
      wrapperPid: 9999,
      wrapperStartedAt: "2026-08-17T01:02:02Z",
      outcome: "COMPLETED",
      exitCode: 0,
    },
  });
  const exportAutomatic = vi.spyOn(bridge, "exportAutomaticDiagnostics");
  const { result } = renderHook(() => useDiagnosticsReport(false, vi.fn()));
  act(() => result.current.setAutomaticRunReports(true));

  await act(async () => result.current.submitAutomaticFailedRunReport({
    game: "/Applications/Starsector",
    wrapperPid: 4242,
  }));

  expect(exportAutomatic).not.toHaveBeenCalled();
  expect(window.localStorage.getItem(AUTOMATIC_RUN_REPORT_HISTORY_STORAGE_KEY)).toBeNull();
});