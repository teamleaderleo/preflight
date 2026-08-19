import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import * as bridge from "./bridge";
import {
  AUTOMATIC_RUN_REPORT_HISTORY_STORAGE_KEY,
  AUTOMATIC_RUN_REPORTS_STORAGE_KEY,
  REPORT_RECEIPT_STORAGE_KEY,
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

test("a stale automatic-report preference is cleared when the beta intake is unconfigured", async () => {
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

  expect(result.current.automaticRunReports).toBe(false);
  await waitFor(() => expect(result.current.reportIntake).toMatchObject({ configured: false }));
  await waitFor(() => expect(window.localStorage.getItem(AUTOMATIC_RUN_REPORTS_STORAGE_KEY)).toBeNull());
  await act(async () => result.current.submitAutomaticFailedRunReport({
    game: "/Applications/Starsector",
    wrapperPid: 4242,
  }));

  expect(intake).toHaveBeenCalledOnce();
  expect(result.current.automaticRunReports).toBe(false);
  expect(snapshot).not.toHaveBeenCalled();
  expect(exportAutomatic).not.toHaveBeenCalled();
  expect(send).not.toHaveBeenCalled();
  expect(window.localStorage.getItem(AUTOMATIC_RUN_REPORT_HISTORY_STORAGE_KEY)).toBeNull();
});

test("an inactive local-only beta clears a stale remote deletion receipt before it can be used", async () => {
  const now = Date.now();
  window.localStorage.setItem(REPORT_RECEIPT_STORAGE_KEY, JSON.stringify({
    protocolVersion: 1,
    caseId: "case-local-only-upgrade",
    objectKey: "accepted/case-local-only-upgrade.zip",
    bytes: 123,
    sha256: "a".repeat(64),
    productVersion: "0.1.0",
    receivedAt: new Date(now - 1_000).toISOString(),
    retentionDeadline: new Date(now + 86_400_000).toISOString(),
    deletion: {
      method: "DELETE",
      url: "https://reports.invalid/delete/case-local-only-upgrade",
      token: "stale-deletion-bearer",
    },
    signature: "stale-signature",
  }));
  const intake = vi.spyOn(bridge, "getReportIntakeStatus").mockResolvedValue({
    configured: false,
    origin: null,
    reason: "Remote reporting is disabled in this beta.",
  });
  const remove = vi.spyOn(bridge, "deleteRunReport");
  const { result } = renderHook(() => useDiagnosticsReport(false, vi.fn()));

  expect(result.current.reportReceipt).toMatchObject({ caseId: "case-local-only-upgrade" });
  await waitFor(() => expect(result.current.reportIntake).toMatchObject({ configured: false }));
  await waitFor(() => expect(result.current.reportReceipt).toBeNull());
  expect(window.localStorage.getItem(REPORT_RECEIPT_STORAGE_KEY)).toBeNull();

  await act(async () => result.current.removeRunReport());
  expect(intake).toHaveBeenCalledOnce();
  expect(remove).not.toHaveBeenCalled();
});

test("enabling automatic reports after local-only intake is known remains off", async () => {
  vi.spyOn(bridge, "getReportIntakeStatus").mockResolvedValue({
    configured: false,
    origin: null,
    reason: "Remote reporting is disabled in this beta.",
  });
  const announce = vi.fn();
  const { result } = renderHook(() => useDiagnosticsReport(true, announce));

  await waitFor(() => expect(result.current.reportIntake).toMatchObject({ configured: false }));
  act(() => result.current.setAutomaticRunReports(true));

  expect(result.current.automaticRunReports).toBe(false);
  expect(window.localStorage.getItem(AUTOMATIC_RUN_REPORTS_STORAGE_KEY)).toBeNull();
  expect(announce).toHaveBeenCalledWith(
    "Remote reporting is disabled in this beta. Local diagnostics ZIP export is still available.",
  );
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
