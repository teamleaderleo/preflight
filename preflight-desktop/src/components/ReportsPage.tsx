import { openDesktopAccessibilitySettings } from "../bridge";
import { CheckIcon, FolderIcon, ShieldIcon } from "../icons";
import type { useDesktopAutomation } from "../useDesktopAutomation";
import type { useDiagnosticsReport } from "../useDiagnosticsReport";
import { NoticeBanner } from "./NoticeBanner";
import { formatBytes, shortPath } from "../uiFormat";
import type { Announce, AppStatus, DesktopSnapshot, NoticeTone } from "../types";

type AutomationState = ReturnType<typeof useDesktopAutomation>;
type DiagnosticsState = ReturnType<typeof useDiagnosticsReport>;

interface ReportsPageProps {
  message: string;
  messageTone: NoticeTone;
  status: AppStatus;
  platform: DesktopSnapshot["platform"] | null;
  preparing: boolean;
  automation: AutomationState;
  diagnostics: DiagnosticsState;
  onMessage: Announce;
}

export function ReportsPage({
  message,
  messageTone,
  status,
  platform,
  preparing,
  automation,
  diagnostics,
  onMessage,
}: ReportsPageProps) {
  const {
    desktopSmokeProbe,
    desktopSmokeProbeBusy,
    desktopSmokeReview,
    desktopSmokeRunDirectory,
    desktopSmokeCancelling,
    desktopSmokeRunning,
    checkDesktopAutomation,
    runDesktopAutomation,
    setDesktopSmokeReview,
    stopDesktopAutomation,
  } = automation;
  const {
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
  } = diagnostics;
  const operationBlocked = preparing || status === "launching" || status === "running";

  return (
    <div className="settings-page">
      <NoticeBanner message={message} tone={messageTone} />
      <div className="settings-overview">
        <section className="card diagnostics-action">
          <div>
            <strong>{diagnosticsExport ? "Diagnostics ready" : "Diagnostics"}</strong>
            <span>{diagnosticsExport ? `${formatBytes(diagnosticsExport.bytes)} · ${shortPath(diagnosticsExport.output)}` : "Paths are redacted. Review contents before saving or sending."}</span>
          </div>
          <div className="report-actions">
            <button className={`button ${diagnosticsExport ? "button--quiet" : "button--primary"}`} type="button" onClick={() => void saveDiagnostics()} disabled={diagnosticsBusy || reportUploading}>
              <FolderIcon />{diagnosticsBusy ? "Saving…" : diagnosticsExport ? "Save another ZIP" : "Save diagnostics"}
            </button>
            {diagnosticsExport ? <button className="button button--primary" type="button" onClick={() => setReportReview(true)} disabled={!reportIntake?.configured || reportUploading || reportReceipt !== null}>{reportReceipt ? "Receipt below" : "Review send"}</button> : null}
          </div>
        </section>
      </div>

      {desktopSmokeReview ? (
        <section className="card automation-review" aria-label="Automated game test review">
          <div className="activation-review__heading">
            <div><p className="eyebrow">Test review</p><h2>Run the checked campaign test?</h2></div>
            <button className="text-button" type="button" onClick={() => setDesktopSmokeReview(false)} disabled={desktopSmokeRunning}>Cancel</button>
          </div>
          <p>Preflight will open the current installation, continue the latest save, move forward for three seconds, collect a screenshot and timing evidence, then close that game process. Leave the game window unobstructed; the interaction sequence stops after four minutes.</p>
          <div className="activation-review__footer">
            <span><ShieldIcon /> The driver doesn’t edit game, mod, or save files; it only sends the actions listed here.</span>
            <button className="button button--primary" type="button" onClick={() => void runDesktopAutomation()} disabled={desktopSmokeProbeBusy || desktopSmokeRunning || operationBlocked}>{desktopSmokeRunning ? "Test running…" : "Start automated test"}</button>
          </div>
        </section>
      ) : null}

      <details className="card settings-disclosure">
        <summary><span><strong>Diagnostic contents</strong><small>Included and excluded data</small></span></summary>
        <div className="settings-grid settings-disclosure__body">
          <section className="diagnostics-card">
            <div className="card__heading"><div><p className="eyebrow">Included</p><h2>Useful metadata only</h2></div><CheckIcon className="settings-check" /></div>
            <ul>
              <li>Run outcome, runtime, adapter health and timing summaries</li>
              <li>Enabled-mod and resource names, counts, sizes and content hashes</li>
              <li>Benchmark identity, settings and result metadata</li>
              <li>A manifest with every included or skipped file</li>
            </ul>
          </section>
          <section className="diagnostics-card diagnostics-card--excluded">
            <div className="card__heading"><div><p className="eyebrow">Excluded</p><h2>Game and personal data</h2></div><ShieldIcon className="settings-check" /></div>
            <ul>
              <li>Game, mod, save, texture, audio or bytecode contents</li>
              <li>Acceleration caches, console logs and crash dumps</li>
              <li>JFR recordings, screenshots, audio or unknown files</li>
              <li>Symlinks or any source file larger than 512 KiB</li>
            </ul>
          </section>
        </div>
      </details>

      {diagnosticsExport && reportIntake && !reportIntake.configured ? <p className="report-unavailable"><ShieldIcon /> {reportIntake.reason ?? "Run-report sending isn't configured in this build."} The ZIP remains available to inspect and share manually.</p> : null}

      {reportReview && diagnosticsExport ? (
        <section className="card report-review" aria-label="Run report consent">
          <div className="activation-review__heading">
            <div><p className="eyebrow">Send review</p><h2>Send this exact ZIP?</h2></div>
            <button className="text-button" type="button" onClick={() => setReportReview(false)} disabled={reportUploading}>Cancel</button>
          </div>
          <p>Preflight will send the ZIP shown below to {reportIntake?.origin}. The service also receives ordinary network metadata such as your IP address for delivery and rate limiting. There are no automatic or background uploads.</p>
          <div className="report-facts">
            <div><span>File</span><strong>{shortPath(diagnosticsExport.output)}</strong></div>
            <div><span>Size</span><strong>{formatBytes(diagnosticsExport.bytes)} ({diagnosticsExport.bytes.toLocaleString()} bytes)</strong></div>
            <div className="report-facts__digest"><span>SHA-256</span><code>{diagnosticsExport.sha256}</code></div>
            <div><span>Retention</span><strong>Automatic deletion starts after 14 days; receipt deadline is 15 days</strong></div>
          </div>
          <div className="report-contents">
            <strong>Included entries ({diagnosticsExport.included.length})</strong>
            {diagnosticsExport.included.length > 0 ? <ul>{diagnosticsExport.included.map((entry) => <li key={entry.entry}><span>{entry.entry}</span><small>{formatBytes(entry.bytes)}</small></li>)}</ul> : <p>No run or benchmark evidence is present; the ZIP contains only its disclosure and manifest.</p>}
          </div>
          <p>Game and mod files, saves, logs and crash dumps, caches, JFR, screenshots, audio, unknown files, binary content, and symlinks stay excluded. Home-directory paths are replaced with <code>&lt;home&gt;</code>.</p>
          {diagnosticsExport.skipped.length > 0 ? <p>{diagnosticsExport.skipped.length} present source file{diagnosticsExport.skipped.length === 1 ? " was" : "s were"} skipped under the disclosed limits.</p> : null}
          {reportError ? <p className="activation-warning">{reportError}</p> : null}
          {reportUploading ? (
            <div className="report-progress" role="progressbar" aria-label="Run report upload" aria-valuemin={0} aria-valuemax={diagnosticsExport.bytes} aria-valuenow={reportUploadedBytes}>
              <span style={{ width: `${Math.min(100, diagnosticsExport.bytes > 0 ? reportUploadedBytes / diagnosticsExport.bytes * 100 : 0)}%` }} />
              <strong>{reportFinalizing ? "Archive accepted · finishing receipt…" : reportCancelling ? "Stopping…" : `${formatBytes(reportUploadedBytes)} of ${formatBytes(diagnosticsExport.bytes)}`}</strong>
            </div>
          ) : null}
          <div className="activation-review__footer">
            <span><ShieldIcon /> The native host rechecks the file, size, and SHA-256 immediately before upload.</span>
            {reportUploading
              ? <button className="button button--quiet" type="button" onClick={() => void stopRunReport()} disabled={reportCancelling || reportFinalizing}>{reportFinalizing ? "Finishing receipt…" : reportCancelling ? "Stopping…" : "Cancel upload"}</button>
              : <button className="button button--primary" type="button" onClick={() => void submitRunReport()} disabled={!reportIntake?.configured || diagnosticsBusy}>Send this exact ZIP</button>}
          </div>
        </section>
      ) : null}

      {reportReceipt ? (
        <section className="card report-receipt" aria-label="Run report receipt">
          <div className="card__heading"><div><p className="eyebrow">Accepted</p><h2>Run report {reportReceipt.caseId}</h2></div><CheckIcon className="settings-check" /></div>
          <p>The intake accepted {formatBytes(reportReceipt.bytes)} with the same SHA-256. Preflight keeps this deletion receipt on this computer until you delete the report, dismiss the receipt, or its deadline passes.</p>
          <div className="report-facts">
            <div><span>Received</span><strong>{new Date(reportReceipt.receivedAt).toLocaleString()}</strong></div>
            <div><span>Retention deadline</span><strong>{new Date(reportReceipt.retentionDeadline).toLocaleString()}</strong></div>
            <div className="report-facts__digest"><span>SHA-256</span><code>{reportReceipt.sha256}</code></div>
          </div>
          <div className="update-actions">
            <button className="button button--quiet button--compact" type="button" onClick={() => void copyRunReportReceipt()}>Copy receipt</button>
            <button className="button button--quiet button--compact" type="button" onClick={dismissRunReportReceipt}>I saved this receipt</button>
            <button className="button button--danger button--compact" type="button" onClick={() => void removeRunReport()} disabled={reportDeleting}>{reportDeleting ? "Deleting…" : "Delete uploaded report"}</button>
          </div>
        </section>
      ) : null}

      <details className="card settings-disclosure automation-card">
        <summary><span><strong>Automated game test</strong><small>{desktopSmokeProbe?.probe.ready ? "Ready" : "Optional compatibility check"}</small></span></summary>
        <div className="settings-disclosure__body">
          <p>{desktopSmokeProbe === null
            ? "Check whether Preflight can control one exact game process, collect bounded evidence, and close it after the test. Nothing launches during this check."
            : desktopSmokeProbe.probe.ready
              ? `Ready through ${desktopSmokeProbe.probe.driver?.id ?? "the platform driver"}. The test remains opt-in and hasn’t started a game.`
              : desktopSmokeProbe.probe.diagnostics[0] ?? "Automated testing isn’t available on this system yet."}</p>
          {desktopSmokeProbe?.probe.ready ? <small>{desktopSmokeProbe.probe.driver?.capabilities.join(" · ")}</small> : null}
          <div className="update-actions">
            <button className="button button--quiet button--compact" type="button" onClick={() => void checkDesktopAutomation()} disabled={desktopSmokeProbeBusy || operationBlocked}>{desktopSmokeProbeBusy ? "Checking…" : desktopSmokeProbe ? "Check again" : "Check readiness"}</button>
            {desktopSmokeProbe?.probe.ready && !desktopSmokeRunning ? <button className="button button--primary button--compact" type="button" onClick={() => setDesktopSmokeReview(true)} disabled={desktopSmokeProbeBusy || operationBlocked}>Review test</button> : null}
            {desktopSmokeRunning ? <button className="button button--quiet button--compact" type="button" onClick={() => void stopDesktopAutomation()} disabled={desktopSmokeCancelling}>{desktopSmokeCancelling ? "Stopping test…" : "Stop test safely"}</button> : null}
            {desktopSmokeProbe && !desktopSmokeProbe.probe.ready && platform === "mac" ? <button className="button button--quiet button--compact" type="button" onClick={() => void openDesktopAccessibilitySettings().catch((error) => onMessage(String(error)))}>Open Accessibility settings</button> : null}
          </div>
          {desktopSmokeRunDirectory ? <small>Latest evidence: {shortPath(desktopSmokeRunDirectory)}</small> : null}
        </div>
      </details>
    </div>
  );
}
