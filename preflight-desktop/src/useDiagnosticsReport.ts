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
import { nativeCommandError } from "./nativeErrors";
import { REPORT_RECEIPT_STORAGE_KEY } from "./desktopStorage";
import { supportSafeReportReceipt } from "./supportReceipt";
import type {
  DiagnosticsExport,
  ReportIntakeStatus,
  ReportReceipt,
  ReportUploadStateEvent,
  Announce,
} from "./types";
import { listenWhileMounted } from "./tauriEvents";
import { startOperationReconciliation } from "./operationReconciliation";
import { errorMessage, localDateStamp } from "./uiFormat";

export const REPORT_INTAKE_NAVIGATION_IDLE_MS = 180;

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

export function useDiagnosticsReport(active: boolean, announce: Announce) {
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
    // Opening Help or Settings is navigation, not a request to contact the report service. Let the
    // retained page paint first, then fill this optional status in once and keep it for the session.
    const timer = window.setTimeout(() => {
      void getReportIntakeStatus()
        .then((status) => {
          if (!cancelled) setReportIntake(status);
        })
        .catch((error) => {
          if (!cancelled) {
            setReportIntake({ configured: false, origin: null, reason: errorMessage(error) });
          }
        });
    }, REPORT_INTAKE_NAVIGATION_IDLE_MS);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [active, reportIntake]);

  useEffect(() => {
    if (!isDesktopHost()) return;
    let stopReconciliation: () => void = () => undefined;
    const stopListening = listenWhileMounted<ReportUploadStateEvent>("report-upload-state", ({ payload }) => {
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
          const detail = payload.detail ?? "The report could not be sent.";
          setReportError(detail);
          announce(`Report wasn’t sent. The diagnostics ZIP is still on this computer. ${detail}`, "error");
          return;
        }
        announce(payload.detail ?? "The local diagnostics ZIP is unchanged.", "warning");
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
    }, (error) => {
      announce(`Live report-upload updates were interrupted: ${error}. Preflight is checking native state directly.`, "warning");
      let previousUpload: number | null | undefined;
      stopReconciliation();
      stopReconciliation = startOperationReconciliation({
        apply: (operation) => {
          if (operation.reportUploadId !== null) {
            previousUpload = operation.reportUploadId;
            reportUploadingRef.current = true;
            setReportUploading(true);
            return;
          }
          if (previousUpload !== null && previousUpload !== undefined) {
            previousUpload = null;
            reportUploadingRef.current = false;
            setReportUploading(false);
            setReportFinalizing(false);
            setReportCancelling(false);
            const detail = "Live completion details were unavailable. The diagnostics ZIP is still on this computer; check for a receipt before retrying.";
            setReportError(detail);
            announce(detail, "warning");
          } else {
            previousUpload = null;
          }
        },
        isActive: () => true,
        onError: (pollError) => announce(`Could not refresh native report-upload state: ${pollError}`, "error"),
      });
    });
    return () => {
      stopListening();
      stopReconciliation();
    };
  }, [announce]);

  const saveDiagnostics = async () => {
    if (diagnosticsBusyRef.current || reportUploadingRef.current) return;
    diagnosticsBusyRef.current = true;
    setDiagnosticsBusy(true);
    try {
      const stamp = localDateStamp();
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
      announce(`Saved ${result.files} disclosed files. Inspect the ZIP before sharing it.`, "success");
    } catch (error) {
      announce(errorMessage(error), "error");
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
      announce(`Run report ${receipt.caseId} was accepted. Keep the receipt for support or deletion.`, "success");
    } catch (error) {
      const nativeError = nativeCommandError(error);
      const detail = nativeError?.message ?? errorMessage(error);
      if (nativeError?.code === "report-upload-cancelled") {
        announce("Report upload stopped. The diagnostics ZIP is still on this computer.", "warning");
      } else {
        setReportError(detail);
        announce(`Report wasn’t sent. The diagnostics ZIP is still on this computer. ${detail}`, "error");
      }
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
      announce(errorMessage(error), "error");
    }
  };

  const copyRunReportReceipt = async () => {
    if (!reportReceipt) return;
    try {
      await navigator.clipboard.writeText(JSON.stringify(supportSafeReportReceipt(reportReceipt), null, 2));
      announce("Support-safe run-report receipt copied. Deletion authorization stayed on this computer.");
    } catch (error) {
      announce(`Could not copy the receipt: ${errorMessage(error)}`, "error");
    }
  };

  const dismissRunReportReceipt = () => {
    setReportReceipt(null);
    announce("Receipt dismissed. Its local deletion authorization was removed.");
  };

  const clearReportReceipt = () => {
    setReportReceipt(null);
  };

  const removeRunReport = async () => {
    if (!reportReceipt || reportDeleting) return;
    setReportDeleting(true);
    try {
      await deleteRunReport(reportReceipt.deletion);
      const caseId = reportReceipt.caseId;
      setReportReceipt(null);
      announce(`Run report ${caseId} was deleted. Your local diagnostics ZIP is unchanged.`, "success");
    } catch (error) {
      announce(errorMessage(error), "error");
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
    clearReportReceipt,
    copyRunReportReceipt,
    dismissRunReportReceipt,
    removeRunReport,
    saveDiagnostics,
    setReportReview,
    stopRunReport,
    submitRunReport,
  };
}
