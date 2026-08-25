import { ArrowIcon, CheckIcon, CopyIcon, FolderIcon, ShieldIcon } from "../icons";
import type { Page } from "./DesktopShell";
import type { useDiagnosticsReport } from "../useDiagnosticsReport";
import { useCopySetup } from "../useCopySetup";
import { NoticeBanner } from "./NoticeBanner";
import { openProjectLink } from "../bridge";
import { formatBytes, shortPath } from "../uiFormat";
import type { NoticeTone, OptimizationPreset } from "../types";

type DiagnosticsState = ReturnType<typeof useDiagnosticsReport>;

interface HelpPageProps {
  message: string;
  messageTone: NoticeTone;
  diagnostics: DiagnosticsState;
  operationBlocked: boolean;
  optimizationPreset: OptimizationPreset;
  onTurnOffOptimizations: () => void;
  onChooseInstall: () => void;
  onNavigate: (page: Page) => void;
}

export function HelpPage({
  message,
  messageTone,
  diagnostics,
  operationBlocked,
  optimizationPreset,
  onTurnOffOptimizations,
  onChooseInstall,
  onNavigate,
}: HelpPageProps) {
  const setupCopy = useCopySetup(optimizationPreset);
  const setupSummaryBusy = setupCopy.copyState === "copying" || setupCopy.saveState === "saving";
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

  return (
    <div className="settings-page help-page">
      <NoticeBanner message={reportError && message.includes(reportError) ? "" : message} tone={messageTone} />

      <section className="card fixes-card">
        <h2>Common fixes</h2>
        <ul className="fixes-list">
          <li>
            <div>
              <strong>Wrong game folder</strong>
            </div>
            <button className="button button--quiet button--compact" type="button" onClick={onChooseInstall} disabled={operationBlocked}>Choose folder<ArrowIcon /></button>
          </li>
          <li>
            <div>
              <strong>Launch still feels slow</strong>
            </div>
            <button className="button button--quiet button--compact" type="button" onClick={() => onNavigate("speed")}>Open Speed<ArrowIcon /></button>
          </li>
          <li>
            <div>
              <strong>Starsector won’t open</strong>
            </div>
            {optimizationPreset === "off"
              ? <button className="button button--quiet button--compact" type="button" onClick={() => onNavigate("home")}>Go to launch<ArrowIcon /></button>
              : <button className="button button--quiet button--compact" type="button" onClick={onTurnOffOptimizations} disabled={operationBlocked}>Try without optimizations<ArrowIcon /></button>}
          </li>
        </ul>
      </section>

      <section className="card help-boundary-card" aria-labelledby="help-boundary-title">
        <div className="card__heading">
          <div><p className="eyebrow">Containment</p><h2 id="help-boundary-title">What Preflight changes</h2></div>
          <ShieldIcon className="settings-check" />
        </div>
        <p>Prepared data stays in Preflight’s own storage. Optimized launches do not rewrite Starsector or mod files, and Preflight does not put prepared data in campaign saves.</p>
        <ul className="help-boundary-facts">
          <li><strong>Repair and Free space</strong> remove only Preflight-owned data, not game files, mods, or saves.</li>
          <li><strong>Saving after launch</strong> remains Starsector’s job; the game and mods can still make their normal save writes.</li>
          <li><strong>Named profiles and launch settings</strong> are the explicit, backed-up changes to game-owned preferences.</li>
        </ul>
      </section>

      <section className="card support-card">
        <div className="support-card__main">
          <div>
            <div className="heading-with-info">
              <h2>{diagnosticsExport ? "Support file ready" : "Report a problem"}</h2>
            </div>
            <p>{diagnosticsExport
              ? `${formatBytes(diagnosticsExport.bytes)} · ${shortPath(diagnosticsExport.output)}`
              : "Copy or save a public setup summary. Make a support file only when more detail is needed."}</p>
          </div>
          <div className="report-actions">
            <button className={`button ${setupCopy.copyState === "copied" ? "button--quiet" : "button--primary"} button--support`} type="button" onClick={() => void setupCopy.copySetup()} disabled={operationBlocked || setupSummaryBusy}>
              {setupCopy.copyState === "copying" ? "Copying…" : setupCopy.copyState === "copied" ? "Setup copied" : "Copy setup"}
            </button>
            <button className="button button--quiet button--support" type="button" onClick={() => void setupCopy.saveSetupSummary()} disabled={operationBlocked || setupSummaryBusy}>
              <FolderIcon />{setupCopy.saveState === "saving" ? "Saving…" : setupCopy.saveState === "saved" ? "Summary saved" : "Save setup summary…"}
            </button>
            <button className="button button--quiet button--support" type="button" onClick={() => void openProjectLink("report-issue")}>Open issue<ArrowIcon /></button>
            <button className="button button--quiet button--support" type="button" onClick={() => void saveDiagnostics()} disabled={operationBlocked || diagnosticsBusy || reportUploading}>
              <FolderIcon />{diagnosticsBusy ? "Creating…" : diagnosticsExport ? "Make another one" : "Make a support file"}
            </button>
            {diagnosticsExport ? <button className="button button--primary" type="button" onClick={() => setReportReview(true)} disabled={!reportIntake?.configured || reportUploading || reportReceipt !== null}>{reportReceipt ? "Sent" : "Review and send"}</button> : null}
          </div>
        </div>
        {setupCopy.savedOutput && setupCopy.saveState === "saved" ? <p className="support-summary-saved"><CheckIcon /> Saved to {shortPath(setupCopy.savedOutput)}</p> : null}
        {setupCopy.saveState === "error" && setupCopy.text ? (
          <div className="report-recovery" role="alert">
            <strong>Setup summary wasn’t saved</strong>
            <p>{setupCopy.saveError} The summary is still available below; choose another filename or copy it.</p>
            <textarea aria-label="Save setup summary" readOnly rows={10} value={setupCopy.text} />
            {setupCopy.copyState === "copied" ? <p className="support-summary-copy-status" role="status"><CheckIcon /> Copied the summary.</p> : null}
            {setupCopy.copyState === "error" ? <p className="support-summary-copy-error" role="alert">Clipboard access failed. Select and copy the text above, or try again.</p> : null}
            <div className="report-actions">
              <button className="button button--quiet button--compact" type="button" onClick={() => void setupCopy.retrySaveSetup()} disabled={setupSummaryBusy}>Choose another file…</button>
              <button className="button button--quiet button--compact" type="button" onClick={() => void setupCopy.retryCopySetup()} disabled={setupSummaryBusy}>
                {setupCopy.copyState === "copying" ? "Copying…" : setupCopy.copyState === "copied" ? "Summary copied" : setupCopy.copyState === "error" ? "Try clipboard again" : "Copy same summary"}
              </button>
            </div>
          </div>
        ) : setupCopy.saveState === "error" ? (
          <p className="report-unavailable" role="alert"><ShieldIcon /> {setupCopy.saveError || "Preflight couldn’t build the setup summary."} Try Save setup summary again or use the separate support file action.</p>
        ) : setupCopy.copyState === "error" && setupCopy.text ? (
          <div className="report-recovery" role="alert">
            <strong>Clipboard access failed</strong>
            <p>The setup summary is still available below. Select and copy it manually, or retry the same summary without rescanning your setup.</p>
            <textarea aria-label="Copy setup summary" readOnly rows={10} value={setupCopy.text} />
            <button className="button button--quiet button--compact" type="button" onClick={() => void setupCopy.retryCopySetup()}>Try clipboard again</button>
          </div>
        ) : setupCopy.copyState === "error" ? (
          <p className="report-unavailable" role="alert"><ShieldIcon /> Preflight couldn’t build the setup summary. Try Copy setup again or use the separate support file action.</p>
        ) : null}

        <details className="settings-disclosure support-contents">
          <summary><span><strong>What’s inside?</strong></span></summary>
          <div className="settings-grid settings-disclosure__body">
            <section className="diagnostics-card">
              <div className="card__heading"><div><p className="eyebrow">Included</p><h2>Run details</h2></div><CheckIcon className="settings-check" /></div>
              <ul>
                <li>Whether the launch finished, which Java version ran, which optimizations were active, and timing summaries</li>
                <li>Enabled-mod and resource names, counts, sizes and file fingerprints</li>
                <li>Benchmark settings and results</li>
                <li>A list of every file included or skipped</li>
              </ul>
            </section>
            <section className="diagnostics-card diagnostics-card--excluded">
              <div className="card__heading"><div><p className="eyebrow">Left out</p><h2>Your game and data</h2></div><ShieldIcon className="settings-check" /></div>
              <ul>
                <li>Game, mod, save, texture, audio or compiled-code contents</li>
                <li>Prepared data, console logs and crash dumps</li>
                <li>Performance recordings, screenshots, audio or files Preflight doesn’t recognize</li>
                <li>Links to other locations or any individual report file larger than 512 KiB</li>
              </ul>
            </section>
          </div>
        </details>
      </section>

      {diagnosticsExport && reportIntake && !reportIntake.configured ? <p className="report-unavailable"><ShieldIcon /> {reportIntake.reason ?? "This build can’t send support files."} The ZIP is still on this computer.</p> : null}

      {reportReview && diagnosticsExport ? (
        <section className="card report-review" aria-label="Run report consent">
          <div className="activation-review__heading">
            <div><p className="eyebrow">Send support file</p><h2>Send this file?</h2></div>
            <button className="text-button" type="button" onClick={() => setReportReview(false)} disabled={reportUploading}>Cancel</button>
          </div>
          <p>This sends the ZIP below to {reportIntake?.origin}. The service receives your IP address for delivery and rate limiting.</p>
          <div className="report-facts">
            <div><span>File</span><strong>{shortPath(diagnosticsExport.output)}</strong></div>
            <div><span>Size</span><strong>{formatBytes(diagnosticsExport.bytes)} ({diagnosticsExport.bytes.toLocaleString()} bytes)</strong></div>
            <div className="report-facts__digest"><span>SHA-256</span><code>{diagnosticsExport.sha256}</code></div>
            <div><span>Retention</span><strong>Deleted automatically within 15 days</strong></div>
          </div>
          <div className="report-contents">
            <strong>Included entries ({diagnosticsExport.included.length})</strong>
            {diagnosticsExport.included.length > 0 ? <ul>{diagnosticsExport.included.map((entry) => <li key={entry.entry}><span>{entry.entry}</span><small>{formatBytes(entry.bytes)}</small></li>)}</ul> : <p>No run or benchmark details were found. The file contains only its contents list.</p>}
          </div>
          <p>Personal paths are hidden.</p>
          {diagnosticsExport.skipped.length > 0 ? <p>{diagnosticsExport.skipped.length} source file{diagnosticsExport.skipped.length === 1 ? " was" : "s were"} skipped.</p> : null}
          {reportError ? (
            <div className="report-recovery" role="alert">
              <strong>It wasn’t sent</strong>
              <p>{reportError}</p>
              <small>The file is still on this computer at {shortPath(diagnosticsExport.output)}.</small>
            </div>
          ) : null}
          {reportUploading ? (
            <div className="report-progress" role="progressbar" aria-label="Run report upload" aria-valuemin={0} aria-valuemax={diagnosticsExport.bytes} aria-valuenow={reportUploadedBytes}>
              <span style={{ width: `${Math.min(100, diagnosticsExport.bytes > 0 ? reportUploadedBytes / diagnosticsExport.bytes * 100 : 0)}%` }} />
              <strong>{reportFinalizing ? "Received · finishing…" : reportCancelling ? "Stopping…" : `${formatBytes(reportUploadedBytes)} of ${formatBytes(diagnosticsExport.bytes)}`}</strong>
            </div>
          ) : null}
          <div className="activation-review__footer">
            <span><ShieldIcon /> Preflight rechecks the file, size, and SHA-256 immediately before upload.</span>
            {reportUploading
              ? <button className="button button--quiet" type="button" onClick={() => void stopRunReport()} disabled={reportCancelling || reportFinalizing}>{reportFinalizing ? "Finishing…" : reportCancelling ? "Stopping…" : "Cancel upload"}</button>
              : <button className="button button--primary" type="button" onClick={() => void submitRunReport()} disabled={!reportIntake?.configured || diagnosticsBusy}>{reportError ? "Try sending again" : "Send file"}</button>}
          </div>
        </section>
      ) : null}

      {reportReceipt ? (
        <section className="card report-receipt" aria-label="Uploaded support file">
          <div className="card__heading"><div><p className="eyebrow">Upload complete</p><h2>Case {reportReceipt.caseId}</h2></div><CheckIcon className="settings-check" /></div>
          <p>Your {formatBytes(reportReceipt.bytes)} support file arrived. Add the case number to your issue. You can delete the upload here before its deadline.</p>
          <div className="report-facts">
            <div><span>Received</span><strong>{new Date(reportReceipt.receivedAt).toLocaleString()}</strong></div>
            <div><span>Retention deadline</span><strong>{new Date(reportReceipt.retentionDeadline).toLocaleString()}</strong></div>
            <div className="report-facts__digest"><span>SHA-256</span><code>{reportReceipt.sha256}</code></div>
          </div>
          <div className="update-actions">
            <button className="button button--quiet button--compact" type="button" onClick={() => void copyRunReportReceipt()}><CopyIcon />Copy case details</button>
            <button className="button button--quiet button--compact" type="button" onClick={() => void openProjectLink("report-issue")}>Open issue<ArrowIcon /></button>
            <button className="button button--quiet button--compact" type="button" onClick={dismissRunReportReceipt}>Dismiss</button>
            <button className="button button--danger button--compact" type="button" onClick={() => void removeRunReport()} disabled={reportDeleting}>{reportDeleting ? "Deleting…" : "Delete uploaded file"}</button>
          </div>
        </section>
      ) : null}

    </div>
  );
}
