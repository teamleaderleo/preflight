import { ShieldIcon } from "../icons";
import { NoticeBanner } from "./NoticeBanner";
import { openProjectLink } from "../bridge";
import type { useSignedUpdates } from "../useSignedUpdates";
import { formatBytes, shortPath } from "../uiFormat";
import type { AfterLaunchBehavior, NoticeTone, RemovalPlan, RemovalScope, ReportIntakeStatus } from "../types";

type UpdateState = ReturnType<typeof useSignedUpdates>;

interface SettingsPageProps {
  message: string;
  messageTone: NoticeTone;
  operationBlocked: boolean;
  updates: UpdateState;
  reportIntake: ReportIntakeStatus | null;
  removalPlan: RemovalPlan | null;
  removalBusy: boolean;
  afterLaunchBehavior: AfterLaunchBehavior;
  automaticRunReports: boolean;
  onAutomaticRunReportsChange: (enabled: boolean) => void;
  onAfterLaunchBehaviorChange: (behavior: AfterLaunchBehavior) => void;
  onReviewRemoval: (scope: RemovalScope) => void;
  onDismissRemoval: () => void;
  onRemove: () => void;
}

export function SettingsPage({
  message,
  messageTone,
  operationBlocked,
  updates,
  reportIntake,
  removalPlan,
  removalBusy,
  afterLaunchBehavior,
  automaticRunReports,
  onAutomaticRunReportsChange,
  onAfterLaunchBehaviorChange,
  onReviewRemoval,
  onDismissRemoval,
  onRemove,
}: SettingsPageProps) {
  const {
    updateChecking,
    updateError,
    updateInstalling,
    updateProgress,
    updateStatus,
    automaticUpdateChecks,
    setAutomaticUpdateChecks,
    checkUpdates,
    installSignedUpdate,
  } = updates;
  return (
    <div className="settings-page">
      <NoticeBanner message={updateError === message ? "" : message} tone={messageTone} />
      <section className="card preferences-card">
        <div className="preference-block">
          <div>
            <h2>After launch</h2>
            <p>Keep Preflight nearby without covering the game.</p>
          </div>
          <label className="setting-field preference-field">
            <span>Window</span>
            <select
              aria-label="Preflight window"
              value={afterLaunchBehavior}
              onChange={(event) => onAfterLaunchBehaviorChange(event.target.value as AfterLaunchBehavior)}
            >
              <option value="minimize">Minimize</option>
              <option value="keep">Keep open</option>
              <option value="quit">Quit</option>
            </select>
            <small>{afterLaunchBehavior === "minimize"
              ? "Restore it for logs or to stop Starsector."
              : afterLaunchBehavior === "quit"
                ? "Playtime still records."
                : "Useful while testing."}</small>
          </label>
        </div>
      </section>
      <div className="settings-overview">
        <section className="card update-card">
          <div className="card__heading">
            <div><h2>{updateStatus?.available ? `Preflight ${updateStatus.version}` : "Updates"}</h2></div>
            <ShieldIcon className="settings-check" />
          </div>
          <p className={updateStatus?.available ? "update-release-notes" : undefined}>{updateStatus?.available
            ? updateStatus.notes || "A newer verified release is ready. Installation starts only after confirmation."
            : updateStatus?.configured
              ? `Version ${updateStatus.currentVersion} is current.`
              : updateStatus?.reason || "Update status hasn’t been checked yet."}</p>
          {updateError ? <p className="activation-warning" role="alert">{updateError}</p> : null}
          {updateInstalling ? (
            <div className="update-progress" role="progressbar" aria-label="Update download" aria-valuemin={0} aria-valuemax={updateProgress?.contentLength ?? undefined} aria-valuenow={updateProgress?.downloadedBytes ?? 0}>
              <span>{updateProgress?.contentLength ? `${formatBytes(updateProgress.downloadedBytes)} of ${formatBytes(updateProgress.contentLength)}` : `${formatBytes(updateProgress?.downloadedBytes ?? 0)} downloaded`}</span>
            </div>
          ) : null}
          <div className="update-actions">
            <button className="button button--quiet button--compact" type="button" onClick={() => void checkUpdates(true)} disabled={updateChecking || updateInstalling}>{updateChecking ? "Checking…" : updateStatus ? "Check again" : "Check for updates"}</button>
            {updateStatus?.available ? <button className="button button--primary" type="button" onClick={() => void installSignedUpdate()} disabled={updateInstalling || operationBlocked}>{updateInstalling ? "Installing…" : "Install and restart"}</button> : null}
          </div>
          {updateStatus?.available ? <small>Prepared profiles stay in place. If the cache format changed, the previous copy is kept for rollback.</small> : null}
          <small>Updates are verified before installation. A failed check leaves this version untouched.</small>
          <label className="settings-toggle">
            <input
              type="checkbox"
              checked={automaticUpdateChecks}
              onChange={(event) => setAutomaticUpdateChecks(event.target.checked)}
            />
            <span>Check for updates automatically<small>Checks the release feed when Preflight starts.</small></span>
          </label>
          <label className="settings-toggle">
            <input
              type="checkbox"
              checked={automaticRunReports}
              disabled={!reportIntake?.configured}
              onChange={(event) => onAutomaticRunReportsChange(event.target.checked)}
            />
            <span>Send failed-run reports automatically<small>{reportIntake?.configured ? "Sends disclosed diagnostics only when Starsector fails to launch." : "Report intake is unavailable in this build."}</small></span>
          </label>
        </section>

        {/*
          * Preflight is an unsigned application from a stranger that reads the player's game folder,
          * and until this panel existed the app itself said nothing at all about what it sends. The
          * exact behaviour was written down, but only in the repository, which is precisely the place
          * a wary player has not gone.
          */}
        <section className="card privacy-card">
          <div className="card__heading"><div><h2>Privacy</h2></div><ShieldIcon className="settings-check" /></div>
          <ul className="privacy-facts">
            <li><strong>No telemetry or accounts.</strong></li>
            {/*
              * A build without a configured intake cannot send a report at all, and the Benchmark
              * page already says so where the button would be. Describing the send flow here anyway
              * would advertise a feature this build doesn't have -- and understate the actual
              * privacy position, which in that case is stronger, not weaker.
              */}
            {reportIntake && !reportIntake.configured ? (
              <li>Update checks fetch version metadata. Support ZIPs stay here until you share one.</li>
            ) : (
              <li>Update checks fetch version metadata. A support ZIP is sent only when you press Send.</li>
            )}
            <li>Saves, mods, screenshots, and game files stay out.</li>
          </ul>
          <div className="privacy-links">
            <button className="button button--quiet button--compact" type="button" onClick={() => void openProjectLink("privacy")}>Full privacy statement</button>
            <button className="button button--quiet button--compact" type="button" onClick={() => void openProjectLink("capabilities")}>What Preflight can access</button>
            <button className="button button--quiet button--compact" type="button" onClick={() => void openProjectLink("project")}>Source code</button>
          </div>
        </section>

      </div>

      <details className="card settings-disclosure removal-card">
        <summary><span><strong>Remove Preflight</strong><small>Launcher, prepared data, profiles, and reports</small></span></summary>
        <div className="settings-disclosure__body">
          <p className="removal-safety">Neither choice removes Starsector, mods, saves, or game settings.</p>
          <div className="removal-choices">
            <div><strong>Stop using Preflight to launch</strong><span>Remove its command engine and shortcuts. Keep prepared caches, profiles, and run reports.</span><button className="button button--quiet button--compact" type="button" onClick={() => onReviewRemoval("launcher")} disabled={removalBusy || operationBlocked}>Review</button></div>
            <div><strong>Delete all Preflight data</strong><span>Also remove prepared caches, profiles, run reports, and backups. Uninstall the desktop app through your operating system afterward.</span><button className="button button--danger button--compact" type="button" onClick={() => onReviewRemoval("all-data")} disabled={removalBusy || operationBlocked}>Review deletion</button></div>
          </div>
        </div>
      </details>

      {removalPlan ? (
        <section className="card removal-review" aria-label="Removal review">
          <div className="activation-review__heading">
            <div><p className="eyebrow">Removal review</p><h2>{removalPlan.scope === "all-data" ? "Remove all Preflight data?" : "Remove launch integration?"}</h2></div>
            <button className="text-button" type="button" onClick={onDismissRemoval} disabled={removalBusy}>Cancel</button>
          </div>
          <p className="cleanup-summary">{formatBytes(removalPlan.bytes)} across {removalPlan.files.toLocaleString()} files. The plan was measured from the paths below.</p>
          <div className="cleanup-groups">{removalPlan.targets.map((target) => <div key={`${target.kind}:${target.path}`}><span>{target.label}</span><strong>{formatBytes(target.bytes)} · {shortPath(target.path)}</strong></div>)}</div>
          <div className="activation-review__footer">
            <span><ShieldIcon /> Starsector, mods, saves, and game settings aren’t removal targets.</span>
            <button className="button button--danger" type="button" onClick={onRemove} disabled={!removalPlan.safe || removalPlan.targets.length === 0 || removalBusy || operationBlocked}>{removalBusy ? "Removing…" : removalPlan.targets.length === 0 ? "Nothing to remove" : removalPlan.scope === "all-data" ? "Remove all Preflight data" : "Remove launch integration"}</button>
          </div>
        </section>
      ) : null}
    </div>
  );
}
