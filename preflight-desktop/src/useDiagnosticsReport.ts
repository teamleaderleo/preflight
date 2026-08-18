import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { save as saveFile } from "@tauri-apps/plugin-dialog";
import {
  exportAutomaticDiagnostics,
  exportDiagnostics,
  getSnapshot,
  isDesktopHost,
} from "./bridge";
import { nativeCommandError } from "./nativeErrors";
import {
  AUTOMATIC_RUN_REPORTS_STORAGE_KEY,
  REPORT_RECEIPT_STORAGE_KEY,
} from "./desktopStorage";
import {
  cancelManualRunReport,
  getReportState,
  mutateRunReport,
  sendRunReport,
  type LegacyReportReceipt,
  type ReportCaseView,
  type ReportState,
  type ReportUploadStateEvent,
} from "./reportBridge";
import type { Announce, DiagnosticsExport } from "./types";
import { listenWhileMounted } from "./tauriEvents";
import { startOperationReconciliation } from "./operationReconciliation";
import { errorMessage, localDateStamp } from "./uiFormat";

function savedAutomaticRunReports(): boolean {
  try {
    const raw = window.localStorage.getItem(AUTOMATIC_RUN_REPORTS_STORAGE_KEY);
    if (!raw) return false;
    const consent = JSON.parse(raw) as Record<string, unknown>;
    return consent.protocolVersion === 1
      && consent.disclosureVersion === 1
      && consent.enabled === true
      && typeof consent.decidedAt === "string"
      && Number.isFinite(Date.parse(consent.decidedAt));
  } catch {
    return false;
  }
}

function persistAutomaticRunReports(enabled: boolean): boolean {
  try {
    window.localStorage.setItem(AUTOMATIC_RUN_REPORTS_STORAGE_KEY, JSON.stringify({
      protocolVersion: 1,
      disclosureVersion: 1,
      enabled,
      decidedAt: new Date().toISOString(),
    }));
    return true;
  } catch {
    return false;
  }
}

function failedRunIdentity(
  run: Awaited<ReturnType<typeof getSnapshot>>["lastRun"],
  wrapperPid: number,
): { key: string; fileId: string } | null {
  if (!run
    || run.wrapperPid !== wrapperPid
    || !run.started
    || !run.wrapperStartedAt
    || !run.outcome
    || run.outcome === "RUNNING"
    || run.outcome === "COMPLETED") return null;
  const normalized = run.directory.replaceAll("\\", "/");
  const fileId = normalized.split("/").filter(Boolean).at(-1) ?? "";
  if (!/^[A-Za-z0-9_-]{1,160}$/.test(fileId)) return null;
  return {
    key: `${fileId}\n${run.wrapperPid}\n${run.wrapperStartedAt}\n${run.started}\n${run.outcome}`,
    fileId,
  };
}

function savedLegacyRunReportReceipt(): LegacyReportReceipt | null {
  try {
    const raw = window.localStorage.getItem(REPORT_RECEIPT_STORAGE_KEY);
    if (!raw) return null;
    const receipt = JSON.parse(raw) as Partial<LegacyReportReceipt>;
    const valid = receipt.protocolVersion === 1
      && typeof receipt.caseId === "string"
      && receipt.objectKey === `accepted/${receipt.caseId}.zip`
      && typeof receipt.bytes === "number"
      && Number.isSafeInteger(receipt.bytes)
      && receipt.bytes > 0
      && typeof receipt.sha256 === "string"
      && /^[0-9a-f]{64}$/.test(receipt.sha256)
      && typeof receipt.productVersion === "string"
      && typeof receipt.receivedAt === "string"
      && Number.isFinite(Date.parse(receipt.receivedAt))
      && typeof receipt.retentionDeadline === "string"
      && Number.isFinite(Date.parse(receipt.retentionDeadline))
      && receipt.deletion?.method === "DELETE"
      && typeof receipt.deletion.url === "string"
      && typeof receipt.deletion.token === "string"
      && receipt.deletion.token.length > 0
      && typeof receipt.signature === "string"
      && receipt.signature.length > 0;
    return valid ? receipt as LegacyReportReceipt : null;
  } catch {
    return null;
  }
}

function mergeReportCase(cases: ReportCaseView[], report: ReportCaseView): ReportCaseView[] {
  return [report, ...cases.filter((candidate) => candidate.caseId !== report.caseId)];
}

function supportSafeCase(report: ReportCaseView): Record<string, unknown> {
  return {
    caseId: report.caseId,
    state: report.state,
    bytes: report.bytes,
    sha256: report.sha256,
    ...(report.productVersion ? { productVersion: report.productVersion } : {}),
    ...(report.receivedAt ? { receivedAt: report.receivedAt } : {}),
    ...(report.retentionDeadline ? { retentionDeadline: report.retentionDeadline } : {}),
  };
}

export function useDiagnosticsReport(active: boolean, announce: Announce) {
  const [diagnosticsBusy, setDiagnosticsBusy] = useState(false);
  const [diagnosticsExport, setDiagnosticsExport] = useState<DiagnosticsExport | null>(null);
  const [reportIntake, setReportIntake] = useState<ReportState | null>(null);
  const [reportCases, setReportCases] = useState<ReportCaseView[]>([]);
  const [reportReview, setReportReview] = useState(false);
  const [reportUploading, setReportUploading] = useState(false);
  const [backgroundReportUploading, setBackgroundReportUploading] = useState(false);
  const [reportFinalizing, setReportFinalizing] = useState(false);
  const [reportCancelling, setReportCancelling] = useState(false);
  const [reportUploadedBytes, setReportUploadedBytes] = useState(0);
  const [reportError, setReportError] = useState("");
  const [reportDeletingCaseId, setReportDeletingCaseId] = useState<string | null>(null);
  const [automaticRunReports, setAutomaticRunReports] = useState(savedAutomaticRunReports);
  const diagnosticsBusyRef = useRef(false);
  const reportUploadingRef = useRef(false);
  const backgroundReportUploadingRef = useRef(false);
  const automaticReportRef = useRef(false);
  const automaticRunReportsRef = useRef(automaticRunReports);
  const reportIntakeRef = useRef(reportIntake);
  const legacyReceiptRef = useRef<LegacyReportReceipt | null>(savedLegacyRunReportReceipt());

  const reportReceipt = useMemo(
    () => reportCases.find((report) => report.state === "accepted") ?? reportCases[0] ?? null,
    [reportCases],
  );

  useEffect(() => {
    automaticRunReportsRef.current = automaticRunReports;
  }, [automaticRunReports]);

  useEffect(() => {
    reportIntakeRef.current = reportIntake;
  }, [reportIntake]);

  const applyReportState = useCallback((status: ReportState) => {
    setReportIntake(status);
    const retained = status.reports.filter((report) => {
      const expired = report.state === "accepted"
        && report.retentionDeadline !== null
        && Number.isFinite(Date.parse(report.retentionDeadline))
        && Date.parse(report.retentionDeadline) <= Date.now();
      if (expired) void mutateRunReport(report.caseId, "dismiss").catch(() => undefined);
      return !expired;
    });
    setReportCases(retained);
    const background = status.backgroundUploadId !== null;
    backgroundReportUploadingRef.current = background;
    setBackgroundReportUploading(background);
    if (status.legacyReceiptImported && legacyReceiptRef.current) {
      try {
        window.localStorage.removeItem(REPORT_RECEIPT_STORAGE_KEY);
        legacyReceiptRef.current = null;
      } catch {
        // Native authority already owns the credential. Leaving the legacy copy is harmless and
        // gives a future launch another chance to remove it from webview storage.
      }
    }
  }, []);

  const refreshReportState = useCallback(async (automaticRunIdentity: string | null = null) => {
    const status = await getReportState(legacyReceiptRef.current, automaticRunIdentity);
    applyReportState(status);
    return status;
  }, [applyReportState]);

  useEffect(() => {
    if ((!active && !automaticRunReports) || reportIntake !== null) return;
    let cancelled = false;
    void refreshReportState()
      .catch((error) => {
        if (!cancelled) {
          setReportIntake({
            configured: false,
            origin: null,
            reason: errorMessage(error),
            reports: [],
            automaticClaimed: null,
            legacyReceiptImported: false,
            backgroundUploadId: null,
            backgroundUploadTotalBytes: null,
          });
        }
      });
    return () => {
      cancelled = true;
    };
  }, [active, automaticRunReports, refreshReportState, reportIntake]);

  useEffect(() => {
    if (!backgroundReportUploading) return;
    const timer = window.setInterval(() => {
      void refreshReportState().catch(() => undefined);
    }, 1_000);
    return () => window.clearInterval(timer);
  }, [backgroundReportUploading, refreshReportState]);

  useEffect(() => {
    if (!isDesktopHost()) return;
    let stopReconciliation: () => void = () => undefined;
    const stopListening = listenWhileMounted<ReportUploadStateEvent>("report-upload-state", ({ payload }) => {
      const kind = payload.kind ?? "manual";
      if (kind === "automatic") {
        const running = payload.state === "starting" || payload.state === "uploading" || payload.state === "finalizing";
        backgroundReportUploadingRef.current = running;
        setBackgroundReportUploading(running);
        if (payload.receipt) {
          setReportCases((current) => mergeReportCase(current, payload.receipt!));
        }
        if (payload.state === "finished" || payload.state === "failed" || payload.state === "cancelled") {
          void refreshReportState().catch(() => undefined);
        }
        return;
      }

      setReportUploadedBytes(payload.uploadedBytes);
      if (payload.state === "starting" || payload.state === "uploading") {
        setReportFinalizing(false);
      }
      if (payload.state === "finalizing") {
        setReportFinalizing(true);
        setReportCancelling(false);
        announce("The archive was accepted. Finishing its receipt…");
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
        } else {
          announce(payload.detail ?? "The local diagnostics ZIP is unchanged.", "warning");
        }
        void refreshReportState().catch(() => undefined);
        return;
      }
      if (payload.state === "finished" && payload.receipt) {
        reportUploadingRef.current = false;
        setReportUploading(false);
        setReportFinalizing(false);
        setReportCancelling(false);
        setReportReview(false);
        setReportCases((current) => mergeReportCase(current, payload.receipt!));
        setReportError("");
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
            void refreshReportState().catch((refreshError) => {
              const detail = `Live completion details were unavailable. The diagnostics ZIP is still on this computer; ${errorMessage(refreshError)}`;
              setReportError(detail);
              announce(detail, "warning");
            });
          } else {
            previousUpload = null;
            void refreshReportState().catch(() => undefined);
          }
        },
        isActive: () => true,
        onError: (pollError) => announce(`Could not refresh native report-upload state: ${pollError}`, "error"),
      });
    });
    const stopRunListening = listenWhileMounted<{ state: string }>("run-state", ({ payload }) => {
      if (payload.state === "started" && backgroundReportUploadingRef.current) {
        void mutateRunReport("", "cancel-background").catch(() => undefined);
      }
    });
    return () => {
      stopListening();
      stopRunListening();
      stopReconciliation();
    };
  }, [announce, refreshReportState]);

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
      const receipt = await sendRunReport(diagnosticsExport, "manual");
      setReportCases((current) => mergeReportCase(current, receipt));
      setReportReview(false);
      setReportUploadedBytes(diagnosticsExport.bytes);
      announce(`Run report ${receipt.caseId} was accepted. Preflight saved its deletion authority on this computer.`, "success");
    } catch (error) {
      const nativeError = nativeCommandError(error);
      const detail = nativeError?.message ?? errorMessage(error);
      if (nativeError?.code === "report-upload-cancelled") {
        announce("Report upload stopped. The diagnostics ZIP is still on this computer.", "warning");
      } else {
        setReportError(detail);
        announce(`Report wasn’t sent. The diagnostics ZIP is still on this computer. ${detail}`, "error");
      }
      void refreshReportState().catch(() => undefined);
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
      const requested = await cancelManualRunReport();
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

  const copyRunReportReceipt = async (caseId?: string) => {
    const receipt = caseId
      ? reportCases.find((candidate) => candidate.caseId === caseId)
      : reportReceipt;
    if (!receipt) return;
    try {
      await navigator.clipboard.writeText(JSON.stringify(supportSafeCase(receipt), null, 2));
      announce("Support-safe run-report receipt copied. Deletion authorization stayed in native Preflight storage.");
    } catch (error) {
      announce(`Could not copy the receipt: ${errorMessage(error)}`, "error");
    }
  };

  const dismissRunReportReceipt = (caseId?: string) => {
    const target = caseId ?? reportReceipt?.caseId;
    if (!target) return;
    void mutateRunReport(target, "dismiss")
      .then(() => {
        setReportCases((current) => current.filter((report) => report.caseId !== target));
        announce(`Case ${target} was dismissed. Its local deletion authorization was removed.`);
      })
      .catch((error) => announce(errorMessage(error), "error"));
  };

  const clearReportReceipt = () => {
    setReportCases([]);
    void mutateRunReport("", "clear-all").catch((error) => announce(errorMessage(error), "error"));
  };

  const removeRunReport = async (caseId?: string) => {
    const target = caseId ?? reportReceipt?.caseId;
    if (!target || reportDeletingCaseId) return;
    setReportDeletingCaseId(target);
    try {
      await mutateRunReport(target, "delete");
      setReportCases((current) => current.filter((report) => report.caseId !== target));
      announce(`Run report ${target} was deleted. Your local diagnostics ZIP is unchanged.`, "success");
    } catch (error) {
      announce(errorMessage(error), "error");
    } finally {
      setReportDeletingCaseId(null);
    }
  };

  const setAutomaticRunReportsSafely = useCallback((enabled: boolean) => {
    if (!persistAutomaticRunReports(enabled)) {
      announce("Preflight couldn’t save that choice, so automatic reports stay off.", "warning");
      setAutomaticRunReports(false);
      automaticRunReportsRef.current = false;
      return;
    }
    automaticRunReportsRef.current = enabled;
    setAutomaticRunReports(enabled);
  }, [announce]);

  const submitAutomaticFailedRunReport = useCallback(async ({
    game,
    wrapperPid,
  }: {
    game: string;
    wrapperPid: number;
  }) => {
    if (!automaticRunReportsRef.current || automaticReportRef.current) return;
    automaticReportRef.current = true;
    let localZip = false;
    try {
      let intake = reportIntakeRef.current;
      if (intake === null) {
        intake = await refreshReportState();
      }
      if (!intake.configured) return;
      const latest = await getSnapshot(game).catch(() => null);
      const identity = failedRunIdentity(latest?.lastRun ?? null, wrapperPid);
      if (!identity) return;

      // Native create-if-absent claim is the authority. Claim before export, so a replay or second
      // desktop process cannot create a second hidden ZIP while racing for the same failed run.
      const claim = await refreshReportState(identity.key);
      if (claim.automaticClaimed !== true) return;

      const exported = await exportAutomaticDiagnostics(identity.fileId);
      localZip = true;
      setDiagnosticsExport(exported);
      backgroundReportUploadingRef.current = true;
      setBackgroundReportUploading(true);
      const receipt = await sendRunReport(exported, "automatic");
      setReportCases((current) => mergeReportCase(current, receipt));
      announce(`A failed-run support report was sent automatically as case ${receipt.caseId}. Its deletion authority is saved in Help.`);
    } catch (error) {
      setReportError(errorMessage(error));
      announce(localZip
        ? "Could not automatically send the failed-run report. The exact local diagnostics ZIP was kept for manual review or retry."
        : "Could not prepare the failed-run report. Nothing was sent.", "warning");
    } finally {
      automaticReportRef.current = false;
      void refreshReportState().catch(() => {
        backgroundReportUploadingRef.current = false;
        setBackgroundReportUploading(false);
      });
    }
  }, [announce, refreshReportState]);

  return {
    automaticRunReports,
    backgroundReportUploading,
    diagnosticsBusy,
    diagnosticsExport,
    reportCancelling,
    reportCases,
    reportDeleting: reportDeletingCaseId !== null,
    reportDeletingCaseId,
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
    setAutomaticRunReports: setAutomaticRunReportsSafely,
    setReportReview,
    stopRunReport,
    submitAutomaticFailedRunReport,
    submitRunReport,
  };
}
