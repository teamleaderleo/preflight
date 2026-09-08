import { useEffect, useRef, useState } from "react";
import { save as saveFile } from "@tauri-apps/plugin-dialog";
import {
  cancelRunReport,
  exportDiagnostics,
  isDesktopHost,
} from "./bridge";
import {
  deleteReportCase,
  getReportLifecycleStatus,
  sendReportTransaction,
} from "./reportLifecycleBridge";
import type {
  ReportIntakeStatus,
  ReportTransactionResult,
  ReportUploadStateEvent,
  SupportReportReceipt,
} from "./reportLifecycleBridge";
import { nativeCommandError } from "./nativeErrors";
import { REPORT_RECEIPT_STORAGE_KEY } from "./desktopStorage";
import { supportSafeReportReceipt } from "./supportReceipt";
import type {
  DiagnosticsExport,
  Announce,
} from "./types";
import { listenWhileMounted } from "./tauriEvents";
import { startOperationReconciliation } from "./operationReconciliation";
import { errorMessage, localDateStamp } from "./uiFormat";

export const REPORT_INTAKE_NAVIGATION_IDLE_MS = 180;

function savedSupportReceipt(): SupportReportReceipt | null {
  try {
    const raw = window.localStorage.getItem(REPORT_RECEIPT_STORAGE_KEY);
    if (!raw) return null;
    const value = JSON.parse(raw) as Partial<SupportReportReceipt>;
    const deadline = typeof value.retentionDeadline === "string"
      ? Date.parse(value.retentionDeadline)
      : Number.NaN;
    if (
      typeof value.caseId === "string"
      && value.caseId.length > 0
      && typeof value.bytes === "number"
      && Number.isSafeInteger(value.bytes)
      && value.bytes > 0
      && typeof value.sha256 === "string"
      && /^[0-9a-f]{64}$/.test(value.sha256)
      && typeof value.productVersion === "string"
      && typeof value.receivedAt === "string"
      && Number.isFinite(Date.parse(value.receivedAt))
      && Number.isFinite(deadline)
      && deadline > Date.now()
    ) {
      return {
        caseId: value.caseId,
        bytes: value.bytes,
        sha256: value.sha256,
        productVersion: value.productVersion,
        receivedAt: value.receivedAt,
        retentionDeadline: value.retentionDeadline as string,
      };
    }
    window.localStorage.removeItem(REPORT_RECEIPT_STORAGE_KEY);
  } catch {
    try {
      window.localStorage.removeItem(REPORT_RECEIPT_STORAGE_KEY);
    } catch {
      // Native private case recovery remains authoritative when renderer storage is unavailable.
    }
  }
  return null;
}

function unavailableFromUnknown(
  current: ReportIntakeStatus | null,
  transaction: ReportTransactionResult,
): ReportIntakeStatus {
  const detail = transaction.detail
    ?? "The remote report outcome is unknown. Preflight saved native recovery data and will reconcile it before another report is created.";
  return {
    configured: false,
    origin: current?.origin ?? null,
    reason: detail,
    reportCase: transaction,
  };
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
  const [reportReceipt, setReportReceipt] = useState<SupportReportReceipt | null>(savedSupportReceipt);
  const [reportError, setReportError] = useState("");
  const [reportDeleting, setReportDeleting] = useState(false);
  const diagnosticsBusyRef = useRef(false);
  const reportUploadingRef = useRef(false);

  useEffect(() => {
    try {
      if (reportReceipt) {
        // Renderer persistence is a support-safe display cache only. Native private storage keeps
        // every bearer credential and owns deletion authority across restart.
        window.localStorage.setItem(
          REPORT_RECEIPT_STORAGE_KEY,
          JSON.stringify(supportSafeReportReceipt(reportReceipt)),
        );
      } else {
        window.localStorage.removeItem(REPORT_RECEIPT_STORAGE_KEY);
      }
    } catch {
      // A locked-down webview can deny storage; native case recovery remains authoritative.
    }
  }, [reportReceipt]);

  const applyTransaction = (transaction: ReportTransactionResult, source: "command" | "status" | "event") => {
    if (transaction.state === "accepted" && transaction.receipt) {
      setReportReceipt(transaction.receipt);
      setReportReview(false);
      setReportError("");
      if (source !== "status") announce(`Support file sent. Case ${transaction.receipt.caseId}.`, "success");
      return;
    }
    if (transaction.state === "cleanup-confirmed") {
      const detail = transaction.detail ?? "Remote cleanup was confirmed. The support file on this computer is unchanged.";
      setReportError(detail);
      if (source !== "status") announce(detail, "warning");
      return;
    }
    const detail = transaction.detail
      ?? "The remote report outcome is unknown. Preflight saved native recovery data and will reconcile it before another report is created.";
    setReportReview(false);
    setReportError("");
    setReportIntake((current) => unavailableFromUnknown(current, transaction));
    if (source !== "status") announce(detail, "warning");
  };

  useEffect(() => {
    if (!active || reportIntake !== null) return;
    let cancelled = false;
    // Opening Help or Settings is navigation, not a request to contact the report service. Let the
    // retained page paint first, then fill this optional status in once and keep it for the session.
    const timer = window.setTimeout(() => {
      void getReportLifecycleStatus()
        .then((status) => {
          if (cancelled) return;
          setReportIntake(status);
          if (status.reportCase) applyTransaction(status.reportCase, "status");
        })
        .catch((error) => {
          if (!cancelled) {
            setReportIntake({
              configured: false,
              origin: null,
              reason: errorMessage(error),
              reportCase: null,
            });
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
        announce("Upload received. Finishing…");
        return;
      }
      if (payload.state === "cancelling") {
        setReportCancelling(true);
        announce(payload.detail ?? "Stopping the report upload…");
        return;
      }
      if (payload.state === "cleanup-confirmed") {
        reportUploadingRef.current = false;
        setReportUploading(false);
        setReportFinalizing(false);
        setReportCancelling(false);
        applyTransaction({
          state: "cleanup-confirmed",
          caseId: payload.caseId,
          receipt: null,
          detail: payload.detail,
        }, "event");
        return;
      }
      if (payload.state === "remote-outcome-unknown") {
        reportUploadingRef.current = false;
        setReportUploading(false);
        setReportFinalizing(false);
        setReportCancelling(false);
        applyTransaction({
          state: "remote-outcome-unknown",
          caseId: payload.caseId,
          receipt: null,
          detail: payload.detail,
        }, "event");
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
      announce(`Preflight lost the live upload status: ${error}. Checking native report recovery…`, "warning");
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
            void getReportLifecycleStatus()
              .then((status) => {
                setReportIntake(status);
                if (status.reportCase) applyTransaction(status.reportCase, "status");
              })
              .catch((statusError) => {
                const detail = `Preflight could not reconcile the completed upload operation: ${errorMessage(statusError)}`;
                setReportIntake({ configured: false, origin: null, reason: detail, reportCase: null });
                announce(detail, "warning");
              });
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
      announce("Creating the support file…");
      const result = await exportDiagnostics(destination);
      setDiagnosticsExport(result);
      setReportReview(false);
      setReportError("");
      setReportUploadedBytes(0);
      announce(`Support file saved with ${result.files} files. Review the ZIP before sharing it.`, "success");
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
    announce("Starting upload…");
    try {
      const transaction = await sendReportTransaction(diagnosticsExport);
      if (transaction.state === "accepted") setReportUploadedBytes(diagnosticsExport.bytes);
      applyTransaction(transaction, "command");
    } catch (error) {
      const nativeError = nativeCommandError(error);
      const detail = nativeError?.message ?? errorMessage(error);
      setReportError(detail);
      announce(`Preflight could not start or reconcile the report transaction. ${detail}`, "error");
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
      announce("Case details copied. Deletion access stayed in native private storage.");
    } catch (error) {
      announce(`Could not copy the case details: ${errorMessage(error)}`, "error");
    }
  };

  const dismissRunReportReceipt = () => {
    setReportReceipt(null);
    announce("Case hidden. Native deletion access stays saved on this computer.");
  };

  const clearReportReceipt = () => {
    setReportReceipt(null);
  };

  const recheckReportIntake = () => {
    if (reportUploadingRef.current || diagnosticsBusyRef.current) return;
    // Clearing the session status re-arms the same idle fetch that fills it on navigation. Native
    // recovery runs inside that status read, so an unknown remote outcome gets reconciled again.
    setReportIntake(null);
  };

  const removeRunReport = async () => {
    if (!reportReceipt || reportDeleting) return;
    setReportDeleting(true);
    try {
      await deleteReportCase(reportReceipt.caseId);
      const caseId = reportReceipt.caseId;
      setReportReceipt(null);
      setReportIntake((current) => current
        ? { ...current, configured: true, reason: null, reportCase: null }
        : current);
      announce(`Uploaded file ${caseId} was deleted. The support file on this computer is unchanged.`, "success");
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
    recheckReportIntake,
    removeRunReport,
    saveDiagnostics,
    setReportReview,
    stopRunReport,
    submitRunReport,
  };
}
