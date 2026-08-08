import { ShieldIcon } from "../icons";
import type { useSignedUpdates } from "../useSignedUpdates";
import { formatBytes, shortPath } from "../uiFormat";
import type { AppStatus, RemovalPlan, RemovalScope } from "../types";

type UpdateState = ReturnType<typeof useSignedUpdates>;

interface SettingsPageProps {
  message: string;
  status: AppStatus;
  preparing: boolean;
  updates: UpdateState;
  removalPlan: RemovalPlan | null;
  removalBusy: boolean;
  onReviewRemoval: (scope: RemovalScope) => void;
  onDismissRemoval: () => void;
  onRemove: () => void;
}

export function SettingsPage({
  message,
  status,
  preparing,
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
    checkUpdates,
    installSignedUpdate,
  } = updates;
  const operationBlocked = preparing || status === "running";

  return (
    <div className="settings-page">
      {message ? <div className="notice" role="status"><span>✦</span><p>{message}</p></div> : null}
      <div className="settings-overview">
        <section className="card update-card">
          <div className="card__heading">
            <div><p className="eyebrow">Application</p><h2>{updateStatus?.available ? `Preflight ${updateStatus.version}` : "Updates"}</h2></div>
            <ShieldIcon className="settings-check" />
          </div>
          <p className={updateStatus?.available ? "update-release-notes" : undefined}>{updateStatus?.available
            ? updateStatus.notes || "A newer verified release is ready. Installation starts only after confirmation."
            : updateStatus?.configured
              ? `Version ${updateStatus.currentVersion} is current.`
              : updateStatus?.reason || "Update status hasn’t been checked yet."}</p>
          {updateError ? <p className="activation-warning">{updateError}</p> : null}
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
        </section>
      </div>

      <details className="card settings-disclosure removal-card">
        <summary><span><strong>Remove Preflight</strong><small>Launcher only or all local data</small></span></summary>
        <div className="settings-disclosure__body">
          <p>Every removal is previewed first. Starsector, mods, saves, and game settings stay untouched.</p>
          <div className="removal-choices">
            <div><strong>Launch integration</strong><span>Remove Preflight’s installed command engine and OS launch shortcuts. Keep prepared data and diagnostics.</span><button className="button button--quiet button--compact" type="button" onClick={() => onReviewRemoval("launcher")} disabled={removalBusy || operationBlocked}>Review launcher removal</button></div>
            <div><strong>All Preflight data</strong><span>Remove launch integrations, caches, profiles, evidence, and backups. The packaged desktop app remains for the operating system to uninstall.</span><button className="button button--quiet button--compact" type="button" onClick={() => onReviewRemoval("all-data")} disabled={removalBusy || operationBlocked}>Review all data removal</button></div>
          </div>
        </div>
      </details>

      {removalPlan ? (
        <section className="card removal-review" aria-label="Removal review">
          <div className="activation-review__heading">
            <div><p className="eyebrow">Nothing removed yet</p><h2>{removalPlan.scope === "all-data" ? "Remove all Preflight data?" : "Remove launch integration?"}</h2></div>
            <button className="text-button" type="button" onClick={onDismissRemoval} disabled={removalBusy}>Cancel</button>
          </div>
          <p className="cleanup-summary">{formatBytes(removalPlan.bytes)} across {removalPlan.files.toLocaleString()} files. The plan was measured from the paths below.</p>
          <div className="cleanup-groups">{removalPlan.targets.map((target) => <div key={`${target.kind}:${target.path}`}><span>{target.label}</span><strong>{formatBytes(target.bytes)} · {shortPath(target.path)}</strong></div>)}</div>
          <div className="activation-review__footer">
            <span><ShieldIcon /> Starsector, mods, saves, and game settings aren’t removal targets.</span>
            <button className="button button--danger" type="button" onClick={onRemove} disabled={!removalPlan.safe || removalPlan.targets.length === 0 || removalBusy}>{removalBusy ? "Removing…" : removalPlan.targets.length === 0 ? "Nothing to remove" : removalPlan.scope === "all-data" ? "Remove all Preflight data" : "Remove launch integration"}</button>
          </div>
        </section>
      ) : null}
    </div>
  );
}
