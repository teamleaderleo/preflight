import type { CheckpointDiff } from "../types";

export interface CheckpointDiffViewerProps {
  diff: CheckpointDiff;
  onClose: () => void;
  onRestore: () => void;
}

export function CheckpointDiffViewer({ diff, onClose, onRestore }: CheckpointDiffViewerProps) {
  const settingsEntries = Object.entries(diff.launchSettingsDiff ?? {});

  return (
    <section className="card checkpoint-diff-viewer" aria-label="Checkpoint Diff Viewer">
      <div className="diff-viewer__header">
        <h2 className="orbitron-title">DIFF: {diff.checkpointName} ↔ {diff.targetName}</h2>
        <button className="button button--quiet" onClick={onClose}>Close Diff</button>
      </div>

      <div className="diff-section">
        <h3>Enabled Mods Delta</h3>
        <div className="diff-mods">
          {diff.enabledModsDiff.added.length > 0 && (
            <div className="diff-added" data-testid="mods-added">
              <strong>Added Mods (+{diff.enabledModsDiff.added.length}):</strong>
              <ul>
                {diff.enabledModsDiff.added.map((m) => (
                  <li key={m} className="mod-added-item">+{m}</li>
                ))}
              </ul>
            </div>
          )}
          {diff.enabledModsDiff.removed.length > 0 && (
            <div className="diff-removed" data-testid="mods-removed">
              <strong>Removed Mods (-{diff.enabledModsDiff.removed.length}):</strong>
              <ul>
                {diff.enabledModsDiff.removed.map((m) => (
                  <li key={m} className="mod-removed-item">-{m}</li>
                ))}
              </ul>
            </div>
          )}
          {diff.enabledModsDiff.added.length === 0 && diff.enabledModsDiff.removed.length === 0 && (
            <p>No mod additions or removals.</p>
          )}
        </div>
      </div>

      <div className="diff-section">
        <h3>Mod Content Drift</h3>
        {diff.modDrift.length > 0 ? (
          <div className="drift-list" data-testid="drift-list">
            {diff.modDrift.map((item) => (
              <div key={item.modId} className="drift-item">
                <span className="badge badge--warning">{item.modId} [{item.status}]</span>
                <span className="drift-files">
                  {item.modifiedFiles && item.modifiedFiles.length > 0
                    ? `Files: ${item.modifiedFiles.join(", ")}`
                    : `Version: ${item.checkpointVersion} → ${item.currentVersion ?? "none"}`}
                </span>
              </div>
            ))}
          </div>
        ) : (
          <p>No same-version mod content drift detected.</p>
        )}
      </div>

      <div className="diff-section">
        <h3>Launch Settings Delta</h3>
        {settingsEntries.length > 0 ? (
          <table className="settings-diff-table" data-testid="settings-diff-table">
            <thead>
              <tr>
                <th>Setting</th>
                <th>Checkpoint Value</th>
                <th>Current Live Value</th>
              </tr>
            </thead>
            <tbody>
              {settingsEntries.map(([key, val]) => (
                <tr key={key}>
                  <td>{key}</td>
                  <td>{String(val?.checkpoint)}</td>
                  <td>{String(val?.current)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <p>Launch preferences are identical.</p>
        )}
      </div>

      <div className="diff-footer">
        <span className="cache-indicator">
          {diff.cacheStatus.rebuildRequired ? "⚠️ Cache Rebuild Required" : "✓ Prepared Cache Valid"}
        </span>
        <button className="button button--primary" onClick={onRestore}>
          Restore Checkpoint
        </button>
      </div>
    </section>
  );
}
