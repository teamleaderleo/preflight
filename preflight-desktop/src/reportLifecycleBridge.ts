import { invoke } from "@tauri-apps/api/core";
import { browserPreviewScenario, getReportIntakeStatus, isDesktopHost } from "./bridge";
import type { DiagnosticsExport } from "./types";

export type ReportLifecycleState = "accepted" | "cleanup-confirmed" | "remote-outcome-unknown";

export interface SupportReportReceipt {
  caseId: string;
  bytes: number;
  sha256: string;
  productVersion: string;
  receivedAt: string;
  retentionDeadline: string;
}

export interface ReportTransactionResult {
  state: ReportLifecycleState;
  caseId: string | null;
  receipt: SupportReportReceipt | null;
  detail: string | null;
}

export interface ReportIntakeStatus {
  configured: boolean;
  origin: string | null;
  reason: string | null;
  reportCase: ReportTransactionResult | null;
}

export interface ReportUploadStateEvent {
  state:
    | "starting"
    | "uploading"
    | "finalizing"
    | "cancelling"
    | "cleanup-confirmed"
    | "remote-outcome-unknown"
    | "finished";
  uploadId: number;
  uploadedBytes: number;
  totalBytes: number;
  caseId: string | null;
  receipt: SupportReportReceipt | null;
  detail: string | null;
}

export async function getReportLifecycleStatus(): Promise<ReportIntakeStatus> {
  if (!isDesktopHost()) {
    const status = await getReportIntakeStatus();
    return { ...status, reportCase: null };
  }
  return invoke<ReportIntakeStatus>("get_report_intake_status");
}

export async function sendReportTransaction(report: DiagnosticsExport): Promise<ReportTransactionResult> {
  if (!isDesktopHost()) {
    if (browserPreviewScenario() === "report-error") {
      return {
        state: "cleanup-confirmed",
        caseId: null,
        receipt: null,
        detail: "The preview report service rejected the case before upload. The support file is still on this computer.",
      };
    }
    await new Promise((resolve) => window.setTimeout(resolve, 500));
    const caseId = "ed6ca0c8-0417-45e5-864f-557680b00590";
    return {
      state: "accepted",
      caseId,
      receipt: {
        caseId,
        bytes: report.bytes,
        sha256: report.sha256,
        productVersion: "preview",
        receivedAt: "2026-08-07T06:30:00.000Z",
        retentionDeadline: "2026-08-22T06:30:00.000Z",
      },
      detail: null,
    };
  }
  return invoke<ReportTransactionResult>("send_run_report", {
    report: { output: report.output, bytes: report.bytes, sha256: report.sha256 },
  });
}

export async function deleteReportCase(caseId: string): Promise<boolean> {
  if (!isDesktopHost()) {
    await new Promise((resolve) => window.setTimeout(resolve, 200));
    return true;
  }
  return invoke<boolean>("delete_run_report", { caseId });
}
