import { invoke } from "@tauri-apps/api/core";
import { browserPreviewScenario, isDesktopHost } from "./bridge";
import type { DiagnosticsExport } from "./types";

export type ReportUploadKind = "manual" | "automatic";
export type ReportCaseState = "accepted" | "pending";
export type ReportCaseAction = "delete" | "dismiss" | "clear-all" | "cancel-background";

export interface LegacyReportDeletion {
  method: "DELETE";
  url: string;
  token: string;
}

export interface LegacyReportReceipt {
  protocolVersion: number;
  caseId: string;
  objectKey: string;
  bytes: number;
  sha256: string;
  productVersion: string;
  receivedAt: string;
  retentionDeadline: string;
  deletion: LegacyReportDeletion;
  signature: string;
}

export interface ReportCaseView {
  state: ReportCaseState;
  caseId: string;
  bytes: number;
  sha256: string;
  productVersion: string | null;
  receivedAt: string | null;
  retentionDeadline: string | null;
}

export interface ReportState {
  configured: boolean;
  origin: string | null;
  reason: string | null;
  reports: ReportCaseView[];
  automaticClaimed: boolean | null;
  legacyReceiptImported: boolean;
  backgroundUploadId: number | null;
  backgroundUploadTotalBytes: number | null;
}

export interface ReportUploadStateEvent {
  state: "starting" | "uploading" | "finalizing" | "cancelling" | "cancelled" | "finished" | "failed";
  uploadId: number;
  uploadedBytes: number;
  totalBytes: number;
  caseId: string | null;
  receipt: ReportCaseView | null;
  detail: string | null;
  kind?: ReportUploadKind;
}

let previewCaseSequence = 0;
let previewReports: ReportCaseView[] = [];
let previewAutomaticClaims = new Set<string>();

export async function getReportState(
  legacyReceipt: LegacyReportReceipt | null = null,
  automaticRunIdentity: string | null = null,
): Promise<ReportState> {
  if (!isDesktopHost()) {
    let legacyReceiptImported = false;
    if (legacyReceipt) {
      const view: ReportCaseView = {
        state: "accepted",
        caseId: legacyReceipt.caseId,
        bytes: legacyReceipt.bytes,
        sha256: legacyReceipt.sha256,
        productVersion: legacyReceipt.productVersion,
        receivedAt: legacyReceipt.receivedAt,
        retentionDeadline: legacyReceipt.retentionDeadline,
      };
      previewReports = [view, ...previewReports.filter((report) => report.caseId !== view.caseId)];
      legacyReceiptImported = true;
    }
    let automaticClaimed: boolean | null = null;
    if (automaticRunIdentity !== null) {
      automaticClaimed = !previewAutomaticClaims.has(automaticRunIdentity);
      if (automaticClaimed) previewAutomaticClaims.add(automaticRunIdentity);
    }
    return {
      configured: true,
      origin: "https://reports.preview.invalid",
      reason: null,
      reports: previewReports,
      automaticClaimed,
      legacyReceiptImported,
      backgroundUploadId: null,
      backgroundUploadTotalBytes: null,
    };
  }
  return invoke<ReportState>("get_report_intake_status", { legacyReceipt, automaticRunIdentity });
}

export async function sendRunReport(
  report: DiagnosticsExport,
  kind: ReportUploadKind,
): Promise<ReportCaseView> {
  if (!isDesktopHost()) {
    if (browserPreviewScenario() === "report-error") {
      throw new Error("The preview report service is temporarily unavailable.");
    }
    await new Promise((resolve) => window.setTimeout(resolve, 500));
    previewCaseSequence += 1;
    const caseId = `ed6ca0c8-0417-45e5-864f-${String(previewCaseSequence).padStart(12, "0")}`;
    const receipt: ReportCaseView = {
      state: "accepted",
      caseId,
      bytes: report.bytes,
      sha256: report.sha256,
      productVersion: "preview",
      receivedAt: "2026-08-18T10:30:00.000Z",
      retentionDeadline: "2026-09-02T10:30:00.000Z",
    };
    previewReports = [receipt, ...previewReports];
    return receipt;
  }
  return invoke<ReportCaseView>("send_run_report", {
    report: { output: report.output, bytes: report.bytes, sha256: report.sha256 },
    kind,
  });
}

export async function cancelManualRunReport(): Promise<boolean> {
  if (!isDesktopHost()) return true;
  return invoke<boolean>("cancel_run_report");
}

export async function mutateRunReport(caseId: string, action: ReportCaseAction): Promise<boolean> {
  if (!isDesktopHost()) {
    if (action === "clear-all") {
      previewReports = [];
      previewAutomaticClaims = new Set();
      return true;
    }
    if (action === "cancel-background") return true;
    previewReports = previewReports.filter((report) => report.caseId !== caseId);
    return true;
  }
  return invoke<boolean>("delete_run_report", { caseId, action });
}

export function resetPreviewReportStateForTests(): void {
  previewCaseSequence = 0;
  previewReports = [];
  previewAutomaticClaims = new Set();
}
