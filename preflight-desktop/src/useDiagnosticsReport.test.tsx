import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import * as reportLifecycle from "./reportLifecycleBridge";
import {
  AUTOMATIC_RUN_REPORTS_STORAGE_KEY,
  REPORT_RECEIPT_STORAGE_KEY,
} from "./desktopStorage";
import { useDiagnosticsReport } from "./useDiagnosticsReport";

beforeEach(() => window.localStorage.clear());
afterEach(() => vi.restoreAllMocks());

test("a legacy automatic-report preference cannot reactivate beta reporting", () => {
  window.localStorage.setItem(AUTOMATIC_RUN_REPORTS_STORAGE_KEY, JSON.stringify({
    protocolVersion: 1,
    disclosureVersion: 1,
    enabled: true,
    decidedAt: "2026-08-17T01:02:03Z",
  }));
  const status = vi.spyOn(reportLifecycle, "getReportLifecycleStatus");
  const { result } = renderHook(() => useDiagnosticsReport(false, vi.fn()));

  expect(result.current).not.toHaveProperty("automaticRunReports");
  expect(result.current).not.toHaveProperty("submitAutomaticFailedRunReport");
  expect(status).not.toHaveBeenCalled();
});

test("opening Help discovers the native report lifecycle and retires renderer receipt storage", async () => {
  window.localStorage.setItem(REPORT_RECEIPT_STORAGE_KEY, JSON.stringify({
    deletion: { token: "legacy-renderer-bearer" },
  }));
  const status = vi.spyOn(reportLifecycle, "getReportLifecycleStatus").mockResolvedValue({
    configured: true,
    origin: "https://reports.example",
    reason: null,
    reportCase: null,
  });
  const { result } = renderHook(() => useDiagnosticsReport(true, vi.fn()));

  await waitFor(() => expect(result.current.reportIntake).toMatchObject({ configured: true }));
  expect(status).toHaveBeenCalledOnce();
  expect(window.localStorage.getItem(REPORT_RECEIPT_STORAGE_KEY)).toBeNull();
});

test("an accepted native case is recovered after restart and deletion passes only its opaque case id", async () => {
  const receipt = {
    caseId: "3961d5f3-cd4c-4b62-b915-e9cc5a68d5db",
    bytes: 38_165,
    sha256: "a".repeat(64),
    productVersion: "0.1.0",
    receivedAt: "2026-09-07T12:00:00Z",
    retentionDeadline: "2026-09-22T12:00:00Z",
  };
  vi.spyOn(reportLifecycle, "getReportLifecycleStatus").mockResolvedValue({
    configured: true,
    origin: "https://reports.example",
    reason: null,
    reportCase: {
      state: "accepted",
      caseId: receipt.caseId,
      receipt,
      detail: null,
    },
  });
  const deletion = vi.spyOn(reportLifecycle, "deleteReportCase").mockResolvedValue(true);
  const { result } = renderHook(() => useDiagnosticsReport(true, vi.fn()));

  await waitFor(() => expect(result.current.reportReceipt?.caseId).toBe(receipt.caseId));
  await act(async () => {
    await result.current.removeRunReport();
  });

  expect(deletion).toHaveBeenCalledOnce();
  expect(deletion).toHaveBeenCalledWith(receipt.caseId);
  expect(result.current.reportReceipt).toBeNull();
  expect(window.localStorage.getItem(REPORT_RECEIPT_STORAGE_KEY)).toBeNull();
});

test("remote-outcome-unknown pauses new sends without claiming the report was absent", async () => {
  const detail = "Remote outcome unknown for case 3961d5f3-cd4c-4b62-b915-e9cc5a68d5db. Native recovery data is saved.";
  vi.spyOn(reportLifecycle, "getReportLifecycleStatus").mockResolvedValue({
    configured: false,
    origin: "https://reports.example",
    reason: detail,
    reportCase: {
      state: "remote-outcome-unknown",
      caseId: "3961d5f3-cd4c-4b62-b915-e9cc5a68d5db",
      receipt: null,
      detail,
    },
  });
  const announce = vi.fn();
  const { result } = renderHook(() => useDiagnosticsReport(true, announce));

  await waitFor(() => expect(result.current.reportIntake?.configured).toBe(false));
  expect(result.current.reportIntake?.reason).toContain("Remote outcome unknown");
  expect(result.current.reportError).toBe("");
  expect(announce.mock.calls.flat().join(" ")).not.toContain("wasn’t sent");
  expect(announce.mock.calls.flat().join(" ")).not.toContain("wasn't sent");
});
