import type { CheckpointListEntry } from "../types";

export interface CheckpointsCardProps {
  checkpoints: CheckpointListEntry[];
  onOpenCheckpoints: () => void;
  onQuickCompare?: (name: string) => void;
  onQuickRestore?: (checkpoint: CheckpointListEntry) => void;
  operationBlocked?: boolean;
}

export function CheckpointsCard({
  checkpoints,
  onOpenCheckpoints,
  onQuickCompare,
  onQuickRestore,
  operationBlocked = false,
}: CheckpointsCardProps) {
  const matchedCount = checkpoints.filter((c) => c.status === "MATCHED").length;
  const driftedCount = checkpoints.filter((c) => c.status === "DRIFTED").length;
  const divergedCount = checkpoints.filter((c) => c.status === "DIVERGED").length;
  const incompleteCount = checkpoints.filter((c) => c.status === "INCOMPLETE").length;

  return (
    <section className="card checkpoints-card" aria-label="Pinned Checkpoints Summary">
      <div className="card__heading">
        <div className="heading-with-info">
          <h2>Launch Checkpoints</h2>
        </div>
        <span className="field-note">{checkpoints.length} pinned</span>
      </div>

      <p className="card-desc">
        Lock in exact known-good game states with full mod content signatures, load orders, and engine preferences.
      </p>

      {checkpoints.length === 0 ? (
        <div className="checkpoints-empty">
          <p>No checkpoints pinned yet. Pin your current working setup to safeguard against unintended mod updates.</p>
          <button className="button button--primary button--compact" onClick={onOpenCheckpoints}>
            Pin First Checkpoint
          </button>
        </div>
      ) : (
        <div className="checkpoints-summary-body">
          <div className="checkpoints-status-counters">
            <span className="status-badge status-badge--matched">Matched: {matchedCount}</span>
            {driftedCount > 0 && <span className="status-badge status-badge--drifted">Drifted: {driftedCount}</span>}
            {divergedCount > 0 && <span className="status-badge status-badge--diverged">Diverged: {divergedCount}</span>}
            {incompleteCount > 0 && <span className="status-badge status-badge--incomplete">Incomplete: {incompleteCount}</span>}
          </div>

          <div className="checkpoints-mini-list">
            {checkpoints.slice(0, 3).map((cp) => (
              <div key={cp.name} className={`checkpoint-mini-row checkpoint-mini-row--${cp.status.toLowerCase()}`}>
                <div className="mini-row-info">
                  <strong>{cp.name}</strong>
                  <span className="mini-row-meta">{cp.modCount} mods · {cp.status}</span>
                </div>
                <div className="mini-row-actions">
                  {onQuickCompare && (
                    <button
                      className="button button--quiet button--compact"
                      onClick={() => onQuickCompare(cp.name)}
                    >
                      Diff
                    </button>
                  )}
                  {onQuickRestore && (
                    <button
                      className="button button--quiet button--compact"
                      onClick={() => onQuickRestore(cp)}
                      disabled={operationBlocked || cp.status === "INCOMPLETE"}
                    >
                      Restore
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>

          <div className="checkpoints-card-footer">
            <button className="button button--quiet button--compact" onClick={onOpenCheckpoints}>
              Manage Checkpoints ({checkpoints.length}) →
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
