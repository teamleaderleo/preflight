import { useState } from "react";
import type {
  CheckpointDiff,
  CheckpointListEntry,
  CheckpointRestorePlan,
} from "../types";
import { CheckpointDiffViewer } from "./CheckpointDiffViewer";
import { CheckpointRestoreModal } from "./CheckpointRestoreModal";

export interface CheckpointsPageProps {
  checkpoints: CheckpointListEntry[];
  onPinCheckpoint: (name: string, description: string, fromLastRun: boolean) => Promise<void>;
  onCompare: (name: string) => Promise<CheckpointDiff>;
  onRestore: (name: string, restoreSettings: boolean) => Promise<CheckpointRestorePlan>;
  onRename: (name: string, newName: string) => Promise<void>;
  onDelete: (name: string) => Promise<void>;
  operationBlocked: boolean;
}

export function CheckpointsPage({
  checkpoints,
  onPinCheckpoint,
  onCompare,
  onRestore,
  onRename,
  onDelete,
  operationBlocked,
}: CheckpointsPageProps) {
  const [nameInput, setNameInput] = useState("");
  const [descInput, setDescInput] = useState("");
  const [includeLastRun, setIncludeLastRun] = useState(true);
  const [nameError, setNameError] = useState<string | null>(null);

  const [activeDiff, setActiveDiff] = useState<CheckpointDiff | null>(null);
  const [selectedForRestore, setSelectedForRestore] = useState<CheckpointListEntry | null>(null);
  const [renameTarget, setRenameTarget] = useState<string | null>(null);
  const [renameDraft, setRenameDraft] = useState("");
  const [statusMessage, setStatusMessage] = useState<string | null>(null);

  const handlePin = async () => {
    if (!nameInput.trim()) {
      setNameError("Checkpoint name cannot be empty");
      return;
    }
    // eslint-disable-next-line no-control-regex
    if (/[\x00-\x1F\x7F]/.test(nameInput)) {
      setNameError("Checkpoint name contains invalid control characters");
      return;
    }
    setNameError(null);
    try {
      await onPinCheckpoint(nameInput.trim(), descInput.trim(), includeLastRun);
      setNameInput("");
      setDescInput("");
      setStatusMessage(`Pinned checkpoint '${nameInput.trim()}' successfully.`);
    } catch (err: unknown) {
      setNameError(err instanceof Error ? err.message : "Failed to pin checkpoint");
    }
  };

  return (
    <div className="checkpoints-page">
      <section className="card pin-checkpoint-form">
        <h2 className="orbitron-title">PIN KNOWN-GOOD CHECKPOINT</h2>
        <p className="section-note">
          Capture your complete live launch configuration: enabled mods, mod content SHA-256 signatures, launch settings, and performance baseline.
        </p>
        <div className="form-row">
          <label htmlFor="checkpoint-name-input">Checkpoint Name</label>
          <input
            id="checkpoint-name-input"
            value={nameInput}
            onChange={(e) => setNameInput(e.target.value)}
            placeholder="e.g. Cycle 214 Heavy Fleet"
            maxLength={100}
          />
          {nameError && <span className="field-error" role="alert">{nameError}</span>}
        </div>
        <div className="form-row">
          <label htmlFor="checkpoint-desc-input">Description (Optional)</label>
          <input
            id="checkpoint-desc-input"
            value={descInput}
            onChange={(e) => setDescInput(e.target.value)}
            placeholder="e.g. Stable 85-mod campaign before adding experimental faction"
          />
        </div>
        <div className="form-row">
          <label className="checkbox-label">
            <input
              type="checkbox"
              checked={includeLastRun}
              onChange={(e) => setIncludeLastRun(e.target.checked)}
            />
            <span>Include current launch preferences & last run telemetry</span>
          </label>
        </div>
        <button
          className="button button--primary"
          onClick={() => void handlePin()}
          disabled={operationBlocked}
        >
          Pin Checkpoint
        </button>
      </section>

      {statusMessage && <div className="notice-banner" role="status">{statusMessage}</div>}

      <section className="card checkpoint-list-card">
        <div className="card__heading">
          <h2>Pinned Checkpoints</h2>
          <span className="field-note">{checkpoints.length} pinned</span>
        </div>

        {checkpoints.length === 0 ? (
          <p className="empty-notice">No checkpoints pinned yet. Pin your current working state above.</p>
        ) : (
          <div className="checkpoint-list">
            {checkpoints.map((cp) => (
              <article key={cp.name} className={`checkpoint-card checkpoint-card--${cp.status.toLowerCase()}`}>
                <div className="checkpoint-card__header">
                  <div>
                    <strong>{cp.name}</strong>
                    <span className={`status-badge status-badge--${cp.status.toLowerCase()}`}>
                      [{cp.status}]
                    </span>
                  </div>
                  <span className="checkpoint-meta">
                    {cp.modCount} mods · Created {new Date(cp.createdAt).toLocaleDateString()}
                  </span>
                </div>
                {cp.description && <p className="checkpoint-desc">{cp.description}</p>}
                {cp.driftDetails && cp.driftDetails.modifiedMods && cp.driftDetails.modifiedMods.length > 0 && (
                  <div className="drift-pill">
                    ⚡ {cp.driftDetails.modifiedMods.length} modified mods detected
                  </div>
                )}
                <div className="checkpoint-card__actions">
                  <button
                    className="button button--quiet button--compact"
                    onClick={async () => {
                      const diff = await onCompare(cp.name);
                      setActiveDiff(diff);
                    }}
                  >
                    Inspect Diff
                  </button>
                  <button
                    className="button button--primary button--compact"
                    onClick={() => setSelectedForRestore(cp)}
                    disabled={operationBlocked}
                  >
                    Restore…
                  </button>
                  <button
                    className="button button--quiet button--compact"
                    onClick={() => {
                      setRenameTarget(cp.name);
                      setRenameDraft(cp.name);
                    }}
                  >
                    Rename
                  </button>
                  <button
                    className="button button--quiet button--compact"
                    onClick={() => void onDelete(cp.name)}
                    disabled={operationBlocked}
                  >
                    Delete
                  </button>
                </div>
              </article>
            ))}
          </div>
        )}

        {renameTarget && (
          <div className="rename-dialog" role="group" aria-label={`Rename ${renameTarget}`}>
            <label htmlFor="rename-checkpoint-input">New Name for {renameTarget}</label>
            <input
              id="rename-checkpoint-input"
              value={renameDraft}
              onChange={(e) => setRenameDraft(e.target.value)}
            />
            <button
              className="button button--primary button--compact"
              onClick={async () => {
                await onRename(renameTarget, renameDraft);
                setRenameTarget(null);
              }}
            >
              Save Name
            </button>
            <button className="button button--quiet button--compact" onClick={() => setRenameTarget(null)}>
              Cancel
            </button>
          </div>
        )}
      </section>

      {activeDiff && (
        <CheckpointDiffViewer
          diff={activeDiff}
          onClose={() => setActiveDiff(null)}
          onRestore={() => {
            const targetCp = checkpoints.find((c) => c.name === activeDiff.checkpointName);
            if (targetCp) {
              setActiveDiff(null);
              setSelectedForRestore(targetCp);
            }
          }}
        />
      )}

      {selectedForRestore && (
        <CheckpointRestoreModal
          checkpoint={selectedForRestore}
          onConfirm={async (restoreSettings) => {
            await onRestore(selectedForRestore.name, restoreSettings);
            setSelectedForRestore(null);
            setStatusMessage(`Restored checkpoint '${selectedForRestore.name}' successfully.`);
          }}
          onCancel={() => setSelectedForRestore(null)}
          operationBlocked={operationBlocked}
        />
      )}
    </div>
  );
}

export function HomeDriftAlertBar({
  checkpointName,
  driftedModsCount,
  onInspectDiff,
  onUpdateCheckpoint,
}: {
  checkpointName: string;
  driftedModsCount: number;
  onInspectDiff: () => void;
  onUpdateCheckpoint: () => void;
}) {
  return (
    <div className="home-drift-alert-bar" role="alert" aria-label="Launch Configuration Drift Alert">
      <span className="alert-text">
        ⚡ Launch configuration has drifted from pinned checkpoint <strong>&apos;{checkpointName}&apos;</strong> ({driftedModsCount} mods altered).
      </span>
      <div className="alert-actions">
        <button className="button button--quiet button--compact" onClick={onInspectDiff}>
          Inspect Diff
        </button>
        <button className="button button--primary button--compact" onClick={onUpdateCheckpoint}>
          Update Checkpoint
        </button>
      </div>
    </div>
  );
}
