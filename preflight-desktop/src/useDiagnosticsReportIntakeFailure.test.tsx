import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import * as bridge from "./bridge";
import {
  AUTOMATIC_RUN_REPORTS_STORAGE_KEY,
  REPORT_RECEIPT_STORAGE_KEY,
} from "./desktopStorage";
import { useDiagnosticsReport } from "./useDiagnosticsReport";

beforeEach(() => window.localStorage.clear());
afterEach(() => vi.restoreAllMocks());

test("transient intake lookup failure preserves deletion authority while remote actions fail closed", async () => {
  window.localStorage.setItem(AUTOMATIC_RUN_REPORTS_STORAGE_KEY, JSON.stringify({
    protocolVersion: 1,
    disclosureVersion: 1,
    enabled: true,
    decidedAt: "2026-08-18T00:00:00Z",
  }));
  const now = Date.now();
  window.localStorage.setItem(REPORT_RECEIPT_STORAGE_KEY, JSON.stringify({
    protocolVersion: 1,
    caseId: "case-preserve-through-status-error",
    objectKey: "accepted/case-preserve-through-status-error.zip",
    bytes: 123,
    sha256: "a".repeat(64),
    productVersion: "0.1.0",
    receivedAt: new Date(now - 1_000).toISOString(),
    retentionDeadline: new Date(now + 86_400_000).toISOString(),
    deletion: {
      method: "DELETE",
      url: "https://reports.invalid/delete/case-preserve-through-status-error",
      token: "retained-deletion-bearer",
    },
    signature: "retained-signature",
  }));

  const intake = vi.spyOn(bridge, "getReportIntakeStatus")
    .mockRejectedValue(new Error("intake status temporarily unavailable"));
  const remove = vi.spyOn(bridge, "deleteRunReport");
  const announce = vi.fn();
  const { result } = renderHook(() => useDiagnosticsReport(false, announce));

  expect(result.current.reportReceipt).toMatchObject({
    caseId: "case-preserve-through-status-error",
  });
  await waitFor(() => expect(result.current.reportIntake).toMatchObject({ configured: false }));
  await waitFor(() => expect(result.current.automaticRunReports).toBe(false));

  expect(intake).toHaveBeenCalledOnce();
  expect(result.current.reportReceipt).toMatchObject({
    caseId: "case-preserve-through-status-error",
  });
  expect(window.localStorage.getItem(REPORT_RECEIPT_STORAGE_KEY)).not.toBeNull();
  expect(window.localStorage.getItem(AUTOMATIC_RUN_REPORTS_STORAGE_KEY)).not.toBeNull();

  await act(async () => result.current.removeRunReport());
  expect(remove).not.toHaveBeenCalled();

  act(() => result.current.setAutomaticRunReports(true));
  expect(result.current.automaticRunReports).toBe(false);
  expect(result.current.reportReceipt).toMatchObject({
    caseId: "case-preserve-through-status-error",
  });
  expect(window.localStorage.getItem(REPORT_RECEIPT_STORAGE_KEY)).not.toBeNull();
  expect(window.localStorage.getItem(AUTOMATIC_RUN_REPORTS_STORAGE_KEY)).not.toBeNull();
  expect(announce).toHaveBeenCalledWith(
    "Report intake could not be verified. Automatic reporting remains off for this session.",
    "warning",
  );
});
