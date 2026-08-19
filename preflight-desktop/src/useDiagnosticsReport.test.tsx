import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import * as bridge from "./bridge";
import * as reportBridge from "./reportBridge";
import {
  AUTOMATIC_RUN_REPORTS_STORAGE_KEY,
  REPORT_RECEIPT_STORAGE_KEY,
} from "./desktopStorage";
import { useDiagnosticsReport } from "./useDiagnosticsReport";

beforeEach(() => {
  window.localStorage.clear();
  reportBridge.resetPreviewReportStateForTests();
});
afterEach(() => vi.restoreAllMocks());

function failedRun(wrapperPid = 4242) {
  return {
    directory: "/Users/captain/.starsector-preflight/runs/20260817-010203-abcd1234",
    modifiedAt: "2026-08-17T01:02:03Z",
    adapterHealth: null,
    started: "2026-08-17T01:02:03Z",
    ended: "2026-08-17T01:02:04Z",
    wrapperPid,
    wrapperStartedAt: "2026-08-17T01:02:02Z",
    startupMillis: null,
    outcome: "LAUNCHER_EXIT_NONZERO",
    exitCode: 1,
  };
}

function acceptedCase(caseId = "ed6ca0c8-0417-45e5-864f-000000000001"): reportBridge.ReportCaseView {
  return {
    state: "accepted",
    caseId,
    bytes: 1024,
    sha256: "ab".repeat(32),
    productVersion: "0.1.0",
    receivedAt: "2026-08-18T10:00:00Z",
    retentionDeadline: "2026-09-02T10:00:00Z",
  };
}

test("automatic failed-run reporting uses the native atomic claim and sends an exact run once", async () => {
  const baseline = await bridge.getSnapshot();
  vi.spyOn(bridge, "getSnapshot").mockResolvedValue({ ...baseline, lastRun: failedRun() });
  const exported = await bridge.exportDiagnostics("/tmp/baseline.zip");
  const exportAutomatic = vi.spyOn(bridge, "exportAutomaticDiagnostics").mockResolvedValue(exported);
  const send = vi.spyOn(reportBridge, "sendRunReport").mockResolvedValue(acceptedCase());
  const claimed = new Set<string>();
  vi.spyOn(reportBridge, "getReportState").mockImplementation(async (_legacyReceipt, automaticRunIdentity) => {
    let automaticClaimed: boolean | null = null;
    if (typeof automaticRunIdentity === "string") {
      automaticClaimed = !claimed.has(automaticRunIdentity);
      claimed.add(automaticRunIdentity);
    }
    return {
      configured: true,
      origin: "https://reports.preview.invalid",
      reason: null,
      reports: send.mock.calls.length > 0 ? [acceptedCase()] : [],
      automaticClaimed,
      legacyReceiptImported: false,
      backgroundUploadId: null,
      backgroundUploadTotalBytes: null,
    };
  });
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
  expect(send).toHaveBeenCalledWith(exported, "automatic");
  expect(result.current.reportCases).toContainEqual(acceptedCase());
});

test("automatic upload stays outside the foreground report workflow", async () => {
  const baseline = await bridge.getSnapshot();
  vi.spyOn(bridge, "getSnapshot").mockResolvedValue({ ...baseline, lastRun: failedRun() });
  const exported = await bridge.exportDiagnostics("/tmp/background.zip");
  vi.spyOn(bridge, "exportAutomaticDiagnostics").mockResolvedValue(exported);
  let finish!: (value: reportBridge.ReportCaseView) => void;
  vi.spyOn(reportBridge, "sendRunReport").mockImplementation(
    () => new Promise<reportBridge.ReportCaseView>((resolve) => { finish = resolve; }),
  );
  const { result } = renderHook(() => useDiagnosticsReport(false, vi.fn()));
  act(() => result.current.setAutomaticRunReports(true));

  let sending!: Promise<void>;
  act(() => {
    sending = result.current.submitAutomaticFailedRunReport({
      game: "/Applications/Starsector",
      wrapperPid: 4242,
    });
  });
  await waitFor(() => expect(result.current.backgroundReportUploading).toBe(true));
  expect(result.current.reportUploading).toBe(false);

  await act(async () => {
    finish(acceptedCase());
    await sending;
  });
});

test("an unrelated or successful latest run is never uploaded for a failed process event", async () => {
  const baseline = await bridge.getSnapshot();
  vi.spyOn(bridge, "getSnapshot").mockResolvedValue({
    ...baseline,
    lastRun: { ...failedRun(9999), outcome: "COMPLETED", exitCode: 0 },
  });
  const exportAutomatic = vi.spyOn(bridge, "exportAutomaticDiagnostics");
  const send = vi.spyOn(reportBridge, "sendRunReport");
  const { result } = renderHook(() => useDiagnosticsReport(false, vi.fn()));
  act(() => result.current.setAutomaticRunReports(true));

  await act(async () => result.current.submitAutomaticFailedRunReport({
    game: "/Applications/Starsector",
    wrapperPid: 4242,
  }));

  expect(exportAutomatic).not.toHaveBeenCalled();
  expect(send).not.toHaveBeenCalled();
});

test("legacy renderer deletion credentials migrate to native report authority before being removed", async () => {
  const receipt = {
    protocolVersion: 1,
    caseId: "ed6ca0c8-0417-45e5-864f-000000000009",
    objectKey: "accepted/ed6ca0c8-0417-45e5-864f-000000000009.zip",
    bytes: 1024,
    sha256: "ab".repeat(32),
    productVersion: "preview",
    receivedAt: "2026-08-18T10:00:00Z",
    retentionDeadline: "2026-09-02T10:00:00Z",
    deletion: {
      method: "DELETE",
      url: "https://reports.preview.invalid/v1/cases/ed6ca0c8-0417-45e5-864f-000000000009",
      token: "secret.token",
    },
    signature: "receipt-signature",
  } as const;
  window.localStorage.setItem(REPORT_RECEIPT_STORAGE_KEY, JSON.stringify(receipt));

  const { result } = renderHook(() => useDiagnosticsReport(true, vi.fn()));

  await waitFor(() => expect(result.current.reportCases).toHaveLength(1));
  expect(result.current.reportCases[0]?.caseId).toBe(receipt.caseId);
  expect(result.current.reportCases[0]).not.toHaveProperty("deletion");
  expect(result.current.reportCases[0]).not.toHaveProperty("signature");
  expect(window.localStorage.getItem(REPORT_RECEIPT_STORAGE_KEY)).toBeNull();
});
