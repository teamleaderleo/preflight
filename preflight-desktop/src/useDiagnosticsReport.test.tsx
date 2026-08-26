import { renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import * as bridge from "./bridge";
import { AUTOMATIC_RUN_REPORTS_STORAGE_KEY } from "./desktopStorage";
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
  const status = vi.spyOn(bridge, "getReportIntakeStatus");
  const { result } = renderHook(() => useDiagnosticsReport(false, vi.fn()));

  expect(result.current).not.toHaveProperty("automaticRunReports");
  expect(result.current).not.toHaveProperty("submitAutomaticFailedRunReport");
  expect(status).not.toHaveBeenCalled();
});

test("opening Help still discovers the manual report intake", async () => {
  const status = vi.spyOn(bridge, "getReportIntakeStatus").mockResolvedValue({
    configured: true,
    origin: "https://reports.example",
    reason: null,
  });
  const { result } = renderHook(() => useDiagnosticsReport(true, vi.fn()));

  await waitFor(() => expect(result.current.reportIntake).toMatchObject({ configured: true }));
  expect(status).toHaveBeenCalledOnce();
});
