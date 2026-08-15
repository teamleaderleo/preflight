import { ArrowIcon, CheckIcon, FolderIcon, ShieldIcon } from "../icons";
import type { Page } from "./DesktopShell";
import type { useDiagnosticsReport } from "../useDiagnosticsReport";
import { InfoTip } from "./InfoTip";
import { NoticeBanner } from "./NoticeBanner";
import { openProjectLink } from "../bridge";
import { formatBytes, shortPath } from "../uiFormat";
import type { NoticeTone } from "../types";

type DiagnosticsState = ReturnType<typeof useDiagnosticsReport>;

interface HelpPageProps {
  message: string;
  messageTone: NoticeTone;
  diagnostics: DiagnosticsState;
  onNavigate: (page: Page) => void;
}

/*
 * Recovery is the errand behind every "it won't start", and it used to live in a collapsed
 * accordion underneath the benchmark -- a page named after a measurement instrument that a
 * player has no reason to open. It is a destination now, so the failure card, Settings, and the
 * navigation all lead to the same place rather than to a panel that has to be expanded first.
 *
 * The support file is what the maintainer needs, not what the player came for. Somebody whose
 * game will not start wants it to start; making a ZIP is the fallback for when nothing here
 * worked. Every fix below is already a supported path in this app, so the page leads with them
 * and the file comes after.
 */
export function HelpPage({ message, messageTone, diagnostics, onNavigate }: HelpPageProps) {
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
        <h2>Try this first</h2>
        <ul className="fixes-list">
          <li>
            <div>
              <strong>Starsector won’t open, or closes straight away</strong>
              <p>Turn optimizations off, then launch. The game starts exactly as it does without Preflight, and nothing you have prepared is thrown away.</p>
            </div>
            <button className="button button--quiet button--compact" type="button" onClick={() => onNavigate("speed")}>Turn off<ArrowIcon /></button>
          </li>
          <li>
            <div>
              <strong>It doesn’t feel any faster</strong>
              <p>The first launch after changing mods rebuilds prepared data, so that one is slow. The next one is the fast one. You can time both to be sure.</p>
            </div>
            <button className="button button--quiet button--compact" type="button" onClick={() => onNavigate("benchmark")}>Time it<ArrowIcon /></button>
          </li>
          <li>
            <div>
              <strong>Preflight is using the wrong copy of the game</strong>
              <p>Point it at the folder you actually play from. Your mods, saves, and settings are read from there and are never moved.</p>
            </div>
            <button className="button button--quiet button--compact" type="button" onClick={() => onNavigate("home")}>Change it<ArrowIcon /></button>
          </li>
        </ul>
      </section>

      <section className="card support-card">
        <div className="support-card__main">
          <div>
            <div className="heading-with-info">
              <h2>{diagnosticsExport ? "Your support file is ready" : "Still stuck?"}</h2>
              <InfoTip label="About the support file">Collects bounded, redacted run and benchmark details. Game files, mods, saves, logs, screenshots, audio, caches, and personal paths stay out.</InfoTip>
            </div>
            <p>{diagnosticsExport
              ? `${formatBytes(diagnosticsExport.bytes)} · ${shortPath(diagnosticsExport.output)}`
              : "Make a file describing what Preflight did, so a problem can be looked at. You can open it and read it first."}</p>
            <small>It stays on this computer. Nothing is sent unless you choose to send it.</small>
          </div>
          <div className="report-actions">
            <button className={`button ${diagnosticsExport ? "button--quiet" : "button--primary"} button--support`} type="button" onClick={() => void saveDiagnostics()} disabled={diagnosticsBusy || reportUploading}>
              <FolderIcon />{diagnosticsBusy ? "Creating…" : diagnosticsExport ? "Make another one" : "Make a support file"}
            </button>
            {diagnosticsExport ? <button className="button button--primary" type="button" onClick={() => setReportReview(true)} disabled={!reportIntake?.configured || reportUploading || reportReceipt !== null}>{reportReceipt ? "Receipt below" : "Review send"}</button> : null}
          </div>
        </div>

        <details className="settings-disclosure support-contents">
          <summary><span><strong>What goes in it?</strong><small>Included and excluded data</small></span></summary>
          <div className="settings-grid settings-disclosure__body">
            <section className="diagnostics-card">
              <div className="card__heading"><div><p className="eyebrow">Included</p><h2>Details about the run</h2></div><CheckIcon className="settings-check" /></div>
              <ul>
                <li>Run outcome, runtime, adapter health and timing summaries</li>
                <li>Enabled-mod and resource names, counts, sizes and content hashes</li>
                <li>Benchmark identity, settings and result metadata</li>
                <li>A list of every file included or skipped</li>
              </ul>
            </section>
            <section className="diagnostics-card diagnostics-card--excluded">
              <div className="card__heading"><div><p className="eyebrow">Excluded</p><h2>Your game and your data</h2></div><ShieldIcon className="settings-check" /></div>
              <ul>
                <li>Game, mod, save, texture, audio or bytecode contents</li>
                <li>Acceleration caches, console logs and crash dumps</li>
                <li>JFR recordings, screenshots, audio or unknown files</li>
                <li>Symlinks or any source file larger than 512 KiB</li>
              </ul>
            </section>
          </div>
        </details>
      </section>

      {diagnosticsExport && reportIntake && !reportIntake.configured ? <p className="report-unavailable"><ShieldIcon /> {reportIntake.reason ?? "This build can't send support files."} The file is still on this computer to read and share yourself.</p> : null}

      {reportReview && diagnosticsExport ? (
        <section className="card report-review" aria-label="Run report consent">
          <div className="activation-review__heading">
            <div><p className="eyebrow">Send review</p><h2>Send this exact file?</h2></div>
            <button className="text-button" type="button" onClick={() => setReportReview(false)} disabled={reportUploading}>Cancel</button>
          </div>
          <p>Preflight will send the file shown below to {reportIntake?.origin}. The service also receives ordinary network metadata such as your IP address for delivery and rate limiting. There are no automatic or background uploads.</p>
          <div className="report-facts">
            <div><span>File</span><strong>{shortPath(diagnosticsExport.output)}</strong></div>
            <div><span>Size</span><strong>{formatBytes(diagnosticsExport.bytes)} ({diagnosticsExport.bytes.toLocaleString()} bytes)</strong></div>
            <div className="report-facts__digest"><span>SHA-256</span><code>{diagnosticsExport.sha256}</code></div>
            <div><span>Retention</span><strong>Automatic deletion starts after 14 days; receipt deadline is 15 days</strong></div>
          </div>
          <div className="report-contents">
            <strong>Included entries ({diagnosticsExport.included.length})</strong>
            {diagnosticsExport.included.length > 0 ? <ul>{diagnosticsExport.included.map((entry) => <li key={entry.entry}><span>{entry.entry}</span><small>{formatBytes(entry.bytes)}</small></li>)}</ul> : <p>No run or benchmark evidence is present; the file contains only its disclosure and manifest.</p>}
          </div>
          <p>Game and mod files, saves, logs and crash dumps, caches, JFR, screenshots, audio, unknown files, binary content, and symlinks stay excluded. Home-directory paths are replaced with <code>&lt;home&gt;</code>.</p>
          {diagnosticsExport.skipped.length > 0 ? <p>{diagnosticsExport.skipped.length} present source file{diagnosticsExport.skipped.length === 1 ? " was" : "s were"} skipped under the disclosed limits.</p> : null}
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
              <strong>{reportFinalizing ? "Accepted · finishing receipt…" : reportCancelling ? "Stopping…" : `${formatBytes(reportUploadedBytes)} of ${formatBytes(diagnosticsExport.bytes)}`}</strong>
            </div>
          ) : null}
          <div className="activation-review__footer">
            <span><ShieldIcon /> Preflight rechecks the file, size, and SHA-256 immediately before upload.</span>
            {reportUploading
              ? <button className="button button--quiet" type="button" onClick={() => void stopRunReport()} disabled={reportCancelling || reportFinalizing}>{reportFinalizing ? "Finishing receipt…" : reportCancelling ? "Stopping…" : "Cancel upload"}</button>
              : <button className="button button--primary" type="button" onClick={() => void submitRunReport()} disabled={!reportIntake?.configured || diagnosticsBusy}>{reportError ? "Try sending again" : "Send this exact file"}</button>}
          </div>
        </section>
      ) : null}

      {reportReceipt ? (
        <section className="card report-receipt" aria-label="Run report receipt">
          <div className="card__heading"><div><p className="eyebrow">Accepted</p><h2>Case {reportReceipt.caseId}</h2></div><CheckIcon className="settings-check" /></div>
          <p>The intake accepted {formatBytes(reportReceipt.bytes)} with the same SHA-256. Quote this case number in an issue instead of pasting logs. Preflight keeps the deletion receipt on this computer until you delete the report, dismiss the receipt, or its deadline passes.</p>
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

      <section className="card help-links-card">
        <div className="card__heading"><div><h2>Elsewhere</h2></div></div>
        {/*
          * The issue path says what to bring. Without it people paste raw logs into a public
          * thread, which is both less useful than the support file and worse for their privacy.
          */}
        <p>Opening an issue? Attach the support file, or quote the case number if you sent one. There is no need to paste logs.</p>
        <div className="privacy-links">
          <button className="button button--quiet button--compact" type="button" onClick={() => void openProjectLink("getting-started")}>Getting started</button>
          <button className="button button--quiet button--compact" type="button" onClick={() => void openProjectLink("report-issue")}>Open an issue</button>
        </div>
        <small>Links open in your browser. Preflight only ever opens its own pages.</small>
      </section>
    </div>
  );
}
