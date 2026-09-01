import { useState } from "react";
import type { CheckpointListEntry } from "../types";

export interface CheckpointRestoreModalProps {
  checkpoint: CheckpointListEntry;
  onConfirm: (restoreSettings: boolean) => Promise<void>;
  onCancel: () => void;
  operationBlocked: boolean;
}

export function CheckpointRestoreModal({
  checkpoint,
  onConfirm,
  onCancel,
  operationBlocked,
}: CheckpointRestoreModalProps) {
  const [restoreSettings, setRestoreSettings] = useState(true);
  const [busy, setBusy] = useState(false);

  const handleApply = async () => {
    setBusy(true);
    try {
      await onConfirm(restoreSettings);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-label="Restore Checkpoint Modal">
      <div className="card checkpoint-restore-card">
        <h2 className="orbitron-title">RESTORE CHECKPOINT // {checkpoint.name}</h2>
        <p className="restore-desc">
          Restoring will reset enabled mods to the {checkpoint.modCount} mods pinned in this checkpoint.
        </p>

        {checkpoint.status === "INCOMPLETE" && (
          <div className="alert alert--danger" role="alert">
            Cannot restore: Required mods are missing from your disk: {checkpoint.missingMods.join(", ")}
          </div>
        )}

        <div className="restore-options">
          <label className="checkbox-label">
            <input
              type="checkbox"
              checked={restoreSettings}
              onChange={(e) => setRestoreSettings(e.target.checked)}
              disabled={busy || checkpoint.status === "INCOMPLETE"}
            />
            <span>Also restore Starsector preferences (Memory, Resolution, Battle Size)</span>
          </label>
        </div>

        <div className="safety-notice">
          <span>🛡️ Preflight creates atomic backups of enabled_mods.json and settings.json before restoring.</span>
        </div>

        <div className="modal-actions">
          <button className="button button--quiet" onClick={onCancel} disabled={busy}>
            Cancel
          </button>
          <button
            className="button button--primary"
            onClick={() => void handleApply()}
            disabled={busy || operationBlocked || checkpoint.status === "INCOMPLETE"}
          >
            {busy ? "Restoring…" : "Confirm Restore"}
          </button>
        </div>
      </div>
    </div>
  );
}
