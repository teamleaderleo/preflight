import { ShieldIcon } from "../icons";
import { NoticeBanner } from "./NoticeBanner";
import { openProjectLink } from "../bridge";
import type { useSignedUpdates } from "../useSignedUpdates";
import { formatBytes, shortPath } from "../uiFormat";
import type { NoticeTone, RemovalPlan, RemovalScope } from "../types";

type UpdateState = ReturnType<typeof useSignedUpdates>;

interface SettingsPageProps {
  message: string;
  messageTone: NoticeTone;
  operationBlocked: boolean;
  updates: UpdateState;
  removalPlan: RemovalPlan | null;
  removalBusy: boolean;
  onReviewRemoval: (scope: RemovalScope) => void;
  onDismissRemoval: () => void;
  onRemove: () => void;
}

export function SettingsPage({
  message,
  messageTone,
  operationBlocked,
  updates,
  removalPlan,
  removalBusy,
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
          <small>Release signatures are checked before installation. A failed check leaves the current version untouched.</small>
          <label className="settings-toggle">
            <input
              type="checkbox"
              checked={automaticUpdateChecks}
              onChange={(event) => setAutomaticUpdateChecks(event.target.checked)}
            />
            <span>Check for updates automatically<small>Asks the release feed for version metadata when Preflight starts. Turning this off leaves the button above as the only check.</small></span>
          </label>
        </section>

        {/*
          * Preflight is an unsigned application from a stranger that reads the player's game folder,
          * and until this panel existed the app itself said nothing at all about what it sends. The
          * exact behaviour was written down, but only in the repository, which is precisely the place
          * a wary player has not gone.
          */}
        <section className="card privacy-card">
          <div className="card__heading"><div><h2>Preflight and your data</h2></div><ShieldIcon className="settings-check" /></div>
          <ul className="privacy-facts">
            <li><strong>No telemetry, no analytics, no accounts.</strong> Nothing is sent because you launched a game, prepared a profile, or ran a benchmark.</li>
            <li><strong>Two things leave this machine, both listed here.</strong> The update check above asks a fixed release address for version metadata. A support report is sent only when you choose to send one.</li>
            <li><strong>Support reports are shown before they go.</strong> Preflight lists every included and excluded file, its size and checksum, and gives you a receipt you can use to delete it afterwards.</li>
            <li><strong>Saves, mods, screenshots, and game files are never included</strong> in anything Preflight sends or removes.</li>
          </ul>
          <div className="privacy-links">
            <button className="button button--quiet button--compact" type="button" onClick={() => void openProjectLink("privacy")}>Full privacy statement</button>
            <button className="button button--quiet button--compact" type="button" onClick={() => void openProjectLink("project")}>Source code</button>
          </div>
        </section>

      </div>

      <section className="card help-card">
        <div className="card__heading"><div><h2>Help and reporting</h2></div></div>
        <p>Preflight is in beta. If something breaks, a report with the Preflight version in it is the fastest route to a fix.</p>
        <div className="privacy-links">
          <button className="button button--quiet button--compact" type="button" onClick={() => void openProjectLink("getting-started")}>Getting started</button>
          <button className="button button--quiet button--compact" type="button" onClick={() => void openProjectLink("report-issue")}>Report a problem</button>
        </div>
        <small>Links open in your browser. Preflight only ever opens its own pages.</small>
      </section>

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
