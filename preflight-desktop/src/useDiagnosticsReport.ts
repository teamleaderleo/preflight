import { useEffect, useRef, useState } from "react";
import { save as saveFile } from "@tauri-apps/plugin-dialog";
import {
  cancelRunReport,
  deleteRunReport,
  exportDiagnostics,
  getReportIntakeStatus,
  isDesktopHost,
  sendRunReport,
} from "./bridge";
import type {
  DiagnosticsExport,
  ReportIntakeStatus,
  ReportReceipt,
  ReportUploadStateEvent,
} from "./types";
import { listenWhileMounted } from "./tauriEvents";

const REPORT_RECEIPT_STORAGE_KEY = "preflight.reportReceipt";

function savedRunReportReceipt(): ReportReceipt | null {
  try {
    const raw = window.localStorage.getItem(REPORT_RECEIPT_STORAGE_KEY);
    if (!raw) return null;
    const receipt = JSON.parse(raw) as Partial<ReportReceipt>;
    const deadline = typeof receipt.retentionDeadline === "string"
      ? Date.parse(receipt.retentionDeadline)
      : Number.NaN;
    const valid = receipt.protocolVersion === 1
      && typeof receipt.caseId === "string"
      && receipt.caseId.length > 0
      && receipt.objectKey === `accepted/${receipt.caseId}.zip`
      && typeof receipt.bytes === "number"
      && Number.isSafeInteger(receipt.bytes)
      && receipt.bytes > 0
      && typeof receipt.sha256 === "string"
      && /^[0-9a-f]{64}$/.test(receipt.sha256)
      && typeof receipt.productVersion === "string"
      && typeof receipt.receivedAt === "string"
      && Number.isFinite(Date.parse(receipt.receivedAt))
      && Number.isFinite(deadline)
      && deadline > Date.now()
      && receipt.deletion?.method === "DELETE"
      && typeof receipt.deletion.url === "string"
      && typeof receipt.deletion.token === "string"
      && receipt.deletion.token.length > 0
      && typeof receipt.signature === "string"
      && receipt.signature.length > 0;
    if (valid) return receipt as ReportReceipt;
    window.localStorage.removeItem(REPORT_RECEIPT_STORAGE_KEY);
  } catch {
    // A malformed or inaccessible local receipt never becomes a deletion request.
  }
  return null;
}

export function useDiagnosticsReport(active: boolean, announce: (message: string) => void) {
  const [diagnosticsBusy, setDiagnosticsBusy] = useState(false);
  const [diagnosticsExport, setDiagnosticsExport] = useState<DiagnosticsExport | null>(null);
  const [reportIntake, setReportIntake] = useState<ReportIntakeStatus | null>(null);
  const [reportReview, setReportReview] = useState(false);
  const [reportUploading, setReportUploading] = useState(false);
  const [reportFinalizing, setReportFinalizing] = useState(false);
  const [reportCancelling, setReportCancelling] = useState(false);
  const [reportUploadedBytes, setReportUploadedBytes] = useState(0);
  const [reportReceipt, setReportReceipt] = useState<ReportReceipt | null>(savedRunReportReceipt);
  const [reportError, setReportError] = useState("");
  const [reportDeleting, setReportDeleting] = useState(false);
  const diagnosticsBusyRef = useRef(false);
  const reportUploadingRef = useRef(false);

  useEffect(() => {
    try {
      if (reportReceipt) {
        window.localStorage.setItem(REPORT_RECEIPT_STORAGE_KEY, JSON.stringify(reportReceipt));
      } else {
        window.localStorage.removeItem(REPORT_RECEIPT_STORAGE_KEY);
      }
    } catch {
      // Receipt copying remains available if a locked-down webview denies local storage.
    }
  }, [reportReceipt]);

  useEffect(() => {
    if (!active || reportIntake !== null) return;
    let cancelled = false;
    void getReportIntakeStatus()
      .then((status) => {
        if (!cancelled) setReportIntake(status);
      })
      .catch((error) => {
        if (!cancelled) {
          setReportIntake({ configured: false, origin: null, reason: String(error) });
        }
      });
    return () => {
      cancelled = true;
    };
  }, [active, reportIntake]);

  useEffect(() => {
    if (!isDesktopHost()) return;
    return listenWhileMounted<ReportUploadStateEvent>("report-upload-state", ({ payload }) => {
      setReportUploadedBytes(payload.uploadedBytes);
      if (payload.state === "starting" || payload.state === "uploading") {
        setReportFinalizing(false);
      }
      if (payload.state === "finalizing") {
        setReportFinalizing(true);
        setReportCancelling(false);
        announce("The archive was accepted. Finishing its signed receipt…");
        return;
      }
      if (payload.state === "cancelling") {
        setReportCancelling(true);
        announce(payload.detail ?? "Stopping the report upload…");
        return;
      }
      if (payload.state === "cancelled" || payload.state === "failed") {
        reportUploadingRef.current = false;
        setReportUploading(false);
        setReportFinalizing(false);
        setReportCancelling(false);
        if (payload.state === "failed") {
          setReportError(payload.detail ?? "The report could not be sent.");
        }
        announce(payload.detail ?? "The local diagnostics ZIP is unchanged.");
        return;
      }
      if (payload.state === "finished" && payload.receipt) {
        reportUploadingRef.current = false;
        setReportUploading(false);
        setReportFinalizing(false);
        setReportCancelling(false);
        setReportReview(false);
        setReportReceipt(payload.receipt);
      }
    }, (error) => announce(`Could not observe report upload state: ${error}`));
  }, [announce]);

  const saveDiagnostics = async () => {
    if (diagnosticsBusyRef.current || reportUploadingRef.current) return;
    diagnosticsBusyRef.current = true;
    setDiagnosticsBusy(true);
    try {
      const stamp = new Date().toISOString().slice(0, 10);
      const destination = isDesktopHost()
        ? await saveFile({
          title: "Save Preflight diagnostics",
          defaultPath: `preflight-diagnostics-${stamp}.zip`,
          filters: [{ name: "ZIP archive", extensions: ["zip"] }],
        })
        : `/Users/captain/Desktop/preflight-diagnostics-${stamp}.zip`;
      if (!destination) return;
      announce("Collecting a small, disclosed support bundle…");
      const result = await exportDiagnostics(destination);
      setDiagnosticsExport(result);
      setReportReview(false);
      setReportError("");
      setReportUploadedBytes(0);
      announce(`Saved ${result.files} disclosed files. Inspect the ZIP before sharing it.`);
    } catch (error) {
      announce(String(error));
    } finally {
      diagnosticsBusyRef.current = false;
      setDiagnosticsBusy(false);
    }
  };

  const submitRunReport = async () => {
    if (!diagnosticsExport || !reportIntake?.configured || diagnosticsBusyRef.current || reportUploadingRef.current) return;
    reportUploadingRef.current = true;
    setReportUploading(true);
    setReportFinalizing(false);
    setReportCancelling(false);
    setReportUploadedBytes(0);
    setReportError("");
    announce("Creating a short-lived case for this exact diagnostics ZIP…");
    try {
      const receipt = await sendRunReport(diagnosticsExport);
      setReportReceipt(receipt);
      setReportReview(false);
      setReportUploadedBytes(diagnosticsExport.bytes);
      announce(`Run report ${receipt.caseId} was accepted. Keep the receipt for support or deletion.`);
    } catch (error) {
      const detail = String(error);
      if (!detail.toLowerCase().includes("cancel")) setReportError(detail);
      announce(detail);
    } finally {
      reportUploadingRef.current = false;
      setReportUploading(false);
      setReportFinalizing(false);
      setReportCancelling(false);
    }
  };

  const stopRunReport = async () => {
    if (!reportUploadingRef.current || reportCancelling) return;
    setReportCancelling(true);
    announce("Stopping the report upload…");
    try {
      const requested = await cancelRunReport();
      if (!requested) {
        reportUploadingRef.current = false;
        setReportUploading(false);
        setReportCancelling(false);
        announce("The report upload had already stopped.");
      }
    } catch (error) {
      setReportCancelling(false);
      announce(String(error));
    }
  };

  const copyRunReportReceipt = async () => {
    if (!reportReceipt) return;
    try {
      await navigator.clipboard.writeText(JSON.stringify(reportReceipt, null, 2));
      announce("Run-report receipt copied. It includes the deletion authorization.");
    } catch (error) {
      announce(`Could not copy the receipt: ${error}`);
    }
  };

  const dismissRunReportReceipt = () => {
    setReportReceipt(null);
    announce("Receipt dismissed. Its local deletion authorization was removed.");
  };

  const removeRunReport = async () => {
    if (!reportReceipt || reportDeleting) return;
    setReportDeleting(true);
    try {
      await deleteRunReport(reportReceipt.deletion);
      const caseId = reportReceipt.caseId;
      setReportReceipt(null);
      announce(`Run report ${caseId} was deleted. Your local diagnostics ZIP is unchanged.`);
    } catch (error) {
      announce(String(error));
    } finally {
      setReportDeleting(false);
    }
  };

  return {
    diagnosticsBusy,
    diagnosticsExport,
    reportCancelling,
    reportDeleting,
    reportError,
    reportFinalizing,
    reportIntake,
    reportReceipt,
    reportReview,
    reportUploadedBytes,
    reportUploading,
    copyRunReportReceipt,
    dismissRunReportReceipt,
    removeRunReport,
    saveDiagnostics,
    setReportReview,
    stopRunReport,
    submitRunReport,
  };
}
