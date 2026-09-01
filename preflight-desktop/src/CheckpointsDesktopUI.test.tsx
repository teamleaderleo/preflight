import React, { useState } from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

// Data Contracts for Feature 3 (Checkpoints Desktop UI)
export interface CheckpointSummary {
  name: string;
  description: string;
  createdAt: string;
  modCount: number;
  status: "MATCHED" | "DRIFTED" | "DIVERGED" | "INCOMPLETE";
  sameInstall: boolean;
  missingMods: string[];
  driftDetails?: {
    modifiedMods: string[];
    settingsChanged: string[];
  };
  file: string;
  checkpointFingerprint: string;
}

export interface CheckpointDiffReport {
  format: "starsector-preflight-checkpoint-diff-v1";
  checkpointName: string;
  targetName: string;
  matched: boolean;
  enabledModsDiff: {
    added: string[];
    removed: string[];
    reordered: boolean;
  };
  modDrift: Array<{
    modId: string;
    status: "PRISTINE" | "CONTENT_MODIFIED" | "BYTECODE_DRIFT" | "CORRUPT_METADATA";
    checkpointVersion: string;
    currentVersion: string;
    checkpointSha256: string;
    currentSha256: string;
    modifiedFiles: string[];
  }>;
  launchSettingsDiff: Record<string, { checkpoint: unknown; current: unknown }>;
  cacheStatus: {
    hasMatchingPreparedData: boolean;
    rebuildRequired: boolean;
  };
}

export interface CheckpointRestorePlan {
  format: "starsector-preflight-checkpoint-restore-v1";
  name: string;
  installRoot: string;
  applied: boolean;
  canRestore: boolean;
  refusalReason?: string | null;
  sourceChanged: boolean;
  checkpointChanged: boolean;
  missingMods: string[];
  restoredModsCount: number;
  restoredSettings: boolean;
  backup?: string;
}

// Checkpoint UI Components implementing Feature 3 specifications
export function CheckpointDiffViewer({
  diff,
  onClose,
  onRestore,
}: {
  diff: CheckpointDiffReport;
  onClose: () => void;
  onRestore: () => void;
}) {
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
                <span className="drift-files">Files: {item.modifiedFiles.join(", ")}</span>
              </div>
            ))}
          </div>
        ) : (
          <p>No same-version mod content drift detected.</p>
        )}
      </div>

      <div className="diff-section">
        <h3>Launch Settings Delta</h3>
        {Object.keys(diff.launchSettingsDiff).length > 0 ? (
          <table className="settings-diff-table" data-testid="settings-diff-table">
            <thead>
              <tr>
                <th>Setting</th>
                <th>Checkpoint Value</th>
                <th>Current Live Value</th>
              </tr>
            </thead>
            <tbody>
              {Object.entries(diff.launchSettingsDiff).map(([key, val]) => (
                <tr key={key}>
                  <td>{key}</td>
                  <td>{String(val.checkpoint)}</td>
                  <td>{String(val.current)}</td>
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

export function CheckpointRestoreModal({
  checkpoint,
  onConfirm,
  onCancel,
  operationBlocked,
}: {
  checkpoint: CheckpointSummary;
  onConfirm: (restoreSettings: boolean) => Promise<void>;
  onCancel: () => void;
  operationBlocked: boolean;
}) {
  const [restoreSettings, setRestoreSettings] = useState(true);
  const [busy, setBusy] = useState(false);

  const handleApply = async () => {
    setBusy(true);
    await onConfirm(restoreSettings);
    setBusy(false);
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

export function CheckpointsPage({
  checkpoints,
  onPinCheckpoint,
  onCompare,
  onRestore,
  onRename,
  onDelete,
  operationBlocked,
}: {
  checkpoints: CheckpointSummary[];
  onPinCheckpoint: (name: string, description: string, fromLastRun: boolean) => Promise<void>;
  onCompare: (name: string) => Promise<CheckpointDiffReport>;
  onRestore: (name: string, restoreSettings: boolean) => Promise<CheckpointRestorePlan>;
  onRename: (name: string, newName: string) => Promise<void>;
  onDelete: (name: string) => Promise<void>;
  operationBlocked: boolean;
}) {
  const [nameInput, setNameInput] = useState("");
  const [descInput, setDescInput] = useState("");
  const [includeLastRun, setIncludeLastRun] = useState(true);
  const [nameError, setNameError] = useState<string | null>(null);

  const [activeDiff, setActiveDiff] = useState<CheckpointDiffReport | null>(null);
  const [selectedForRestore, setSelectedForRestore] = useState<CheckpointSummary | null>(null);
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
                {cp.driftDetails && cp.driftDetails.modifiedMods.length > 0 && (
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

// ------------------- TEST SUITE -------------------
describe("Feature 3: Checkpoints Desktop UI Test Suite", () => {
  const mockCheckpoints: CheckpointSummary[] = [
    {
      name: "Cycle 214 Heavy Fleet",
      description: "Stable 85-mod fleet before adding experimental faction",
      createdAt: "2026-08-18T14:30:00Z",
      modCount: 85,
      status: "DRIFTED",
      sameInstall: true,
      missingMods: [],
      driftDetails: {
        modifiedMods: ["uaf"],
        settingsChanged: ["memoryMiB"],
      },
      file: "~/.starsector-preflight/checkpoints/cycle-214.json",
      checkpointFingerprint: "sha256_checkpoint_1",
    },
    {
      name: "Vanilla Plus Utility",
      description: "Core utility libraries only",
      createdAt: "2026-08-10T10:00:00Z",
      modCount: 4,
      status: "MATCHED",
      sameInstall: true,
      missingMods: [],
      file: "~/.starsector-preflight/checkpoints/vanilla-plus.json",
      checkpointFingerprint: "sha256_checkpoint_2",
    },
    {
      name: "Old Campaign Archived",
      description: "Requires older mods",
      createdAt: "2026-07-01T09:00:00Z",
      modCount: 42,
      status: "INCOMPLETE",
      sameInstall: true,
      missingMods: ["legacy_faction_v1"],
      file: "~/.starsector-preflight/checkpoints/old-campaign.json",
      checkpointFingerprint: "sha256_checkpoint_3",
    },
  ];

  const mockDiffReport: CheckpointDiffReport = {
    format: "starsector-preflight-checkpoint-diff-v1",
    checkpointName: "Cycle 214 Heavy Fleet",
    targetName: "Current Live Launch State",
    matched: false,
    enabledModsDiff: {
      added: ["ind-evolution", "random-assortment"],
      removed: ["old-weapons-pack"],
      reordered: false,
    },
    modDrift: [
      {
        modId: "uaf",
        status: "CONTENT_MODIFIED",
        checkpointVersion: "0.7.4a",
        currentVersion: "0.7.4a",
        checkpointSha256: "sha256_old_uaf",
        currentSha256: "sha256_new_uaf",
        modifiedFiles: ["data/weapons/uaf_vocal.csv"],
      },
    ],
    launchSettingsDiff: {
      memoryMiB: { checkpoint: 6144, current: 8192 },
      battleSize: { checkpoint: 500, current: 400 },
    },
    cacheStatus: {
      hasMatchingPreparedData: false,
      rebuildRequired: true,
    },
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  // ========== TIER 1: HAPPY PATH EQUIVALENCE CLASS TESTS (>= 5 tests) ==========

  it("T1.1: renders pinned checkpoints card with all status badges correctly", () => {
    render(
      <CheckpointsPage
        checkpoints={mockCheckpoints}
        onPinCheckpoint={vi.fn()}
        onCompare={vi.fn()}
        onRestore={vi.fn()}
        onRename={vi.fn()}
        onDelete={vi.fn()}
        operationBlocked={false}
      />
    );

    expect(screen.getByText("PIN KNOWN-GOOD CHECKPOINT")).toBeInTheDocument();
    expect(screen.getByText("Cycle 214 Heavy Fleet")).toBeInTheDocument();
    expect(screen.getByText("[DRIFTED]")).toBeInTheDocument();
    expect(screen.getByText("Vanilla Plus Utility")).toBeInTheDocument();
    expect(screen.getByText("[MATCHED]")).toBeInTheDocument();
    expect(screen.getByText("Old Campaign Archived")).toBeInTheDocument();
    expect(screen.getByText("[INCOMPLETE]")).toBeInTheDocument();
    expect(screen.getByText("3 pinned")).toBeInTheDocument();
  });

  it("T1.2: pins a new checkpoint with name, description, and settings toggle", async () => {
    const user = userEvent.setup();
    const pinSpy = vi.fn().mockResolvedValue(undefined);

    render(
      <CheckpointsPage
        checkpoints={mockCheckpoints}
        onPinCheckpoint={pinSpy}
        onCompare={vi.fn()}
        onRestore={vi.fn()}
        onRename={vi.fn()}
        onDelete={vi.fn()}
        operationBlocked={false}
      />
    );

    const nameInput = screen.getByLabelText("Checkpoint Name");
    const descInput = screen.getByLabelText("Description (Optional)");
    const pinBtn = screen.getByRole("button", { name: "Pin Checkpoint" });

    await user.type(nameInput, "Cycle 215 Experimental Fleet");
    await user.type(descInput, "Testing new capital ship mod");
    await user.click(pinBtn);

    expect(pinSpy).toHaveBeenCalledWith(
      "Cycle 215 Experimental Fleet",
      "Testing new capital ship mod",
      true
    );
    expect(await screen.findByRole("status")).toHaveTextContent("Pinned checkpoint 'Cycle 215 Experimental Fleet' successfully.");
  });

  it("T1.3: opens visual diff viewer and displays mods delta, mod content drift, and settings diff", async () => {
    const user = userEvent.setup();
    const compareSpy = vi.fn().mockResolvedValue(mockDiffReport);

    render(
      <CheckpointsPage
        checkpoints={mockCheckpoints}
        onPinCheckpoint={vi.fn()}
        onCompare={compareSpy}
        onRestore={vi.fn()}
        onRename={vi.fn()}
        onDelete={vi.fn()}
        operationBlocked={false}
      />
    );

    const inspectButtons = screen.getAllByRole("button", { name: "Inspect Diff" });
    await user.click(inspectButtons[0]);

    expect(compareSpy).toHaveBeenCalledWith("Cycle 214 Heavy Fleet");
    expect(await screen.findByText(/DIFF: Cycle 214 Heavy Fleet ↔ Current Live Launch State/)).toBeInTheDocument();

    const addedSection = screen.getByTestId("mods-added");
    expect(within(addedSection).getByText("+ind-evolution")).toBeInTheDocument();
    expect(within(addedSection).getByText("+random-assortment")).toBeInTheDocument();

    const removedSection = screen.getByTestId("mods-removed");
    expect(within(removedSection).getByText("-old-weapons-pack")).toBeInTheDocument();

    const driftSection = screen.getByTestId("drift-list");
    expect(within(driftSection).getByText("uaf [CONTENT_MODIFIED]")).toBeInTheDocument();
    expect(within(driftSection).getByText(/uaf_vocal.csv/)).toBeInTheDocument();

    const settingsTable = screen.getByTestId("settings-diff-table");
    expect(within(settingsTable).getByText("memoryMiB")).toBeInTheDocument();
    expect(within(settingsTable).getByText("6144")).toBeInTheDocument();
    expect(within(settingsTable).getByText("8192")).toBeInTheDocument();
    expect(screen.getByText("⚠️ Cache Rebuild Required")).toBeInTheDocument();
  });

  it("T1.4: previews and executes one-click safe restore with settings restoration toggle", async () => {
    const user = userEvent.setup();
    const restoreSpy = vi.fn().mockResolvedValue({
      format: "starsector-preflight-checkpoint-restore-v1",
      name: "Cycle 214 Heavy Fleet",
      installRoot: "/Applications/Starsector.app",
      applied: true,
      canRestore: true,
      sourceChanged: false,
      checkpointChanged: false,
      missingMods: [],
      restoredModsCount: 85,
      restoredSettings: true,
      backup: "~/.starsector-preflight/profile-backups/enabled_mods.json",
    });

    render(
      <CheckpointsPage
        checkpoints={mockCheckpoints}
        onPinCheckpoint={vi.fn()}
        onCompare={vi.fn()}
        onRestore={restoreSpy}
        onRename={vi.fn()}
        onDelete={vi.fn()}
        operationBlocked={false}
      />
    );

    const restoreButtons = screen.getAllByRole("button", { name: "Restore…" });
    await user.click(restoreButtons[0]);

    const modal = await screen.findByRole("dialog", { name: "Restore Checkpoint Modal" });
    expect(within(modal).getByText(/RESTORE CHECKPOINT \/\/ Cycle 214 Heavy Fleet/)).toBeInTheDocument();
    expect(within(modal).getByText(/Preflight creates atomic backups/)).toBeInTheDocument();

    const confirmBtn = within(modal).getByRole("button", { name: "Confirm Restore" });
    await user.click(confirmBtn);

    expect(restoreSpy).toHaveBeenCalledWith("Cycle 214 Heavy Fleet", true);
    expect(await screen.findByRole("status")).toHaveTextContent("Restored checkpoint 'Cycle 214 Heavy Fleet' successfully.");
  });

  it("T1.5: renames a pinned checkpoint and confirms name update", async () => {
    const user = userEvent.setup();
    const renameSpy = vi.fn().mockResolvedValue(undefined);

    render(
      <CheckpointsPage
        checkpoints={mockCheckpoints}
        onPinCheckpoint={vi.fn()}
        onCompare={vi.fn()}
        onRestore={vi.fn()}
        onRename={renameSpy}
        onDelete={vi.fn()}
        operationBlocked={false}
      />
    );

    const renameButtons = screen.getAllByRole("button", { name: "Rename" });
    await user.click(renameButtons[0]);

    const renameGroup = screen.getByRole("group", { name: "Rename Cycle 214 Heavy Fleet" });
    const renameInput = within(renameGroup).getByLabelText("New Name for Cycle 214 Heavy Fleet");
    await user.clear(renameInput);
    await user.type(renameInput, "Cycle 214 Renamed Fleet");

    await user.click(within(renameGroup).getByRole("button", { name: "Save Name" }));
    expect(renameSpy).toHaveBeenCalledWith("Cycle 214 Heavy Fleet", "Cycle 214 Renamed Fleet");
  });

  it("T1.6: deletes a pinned checkpoint when confirmed", async () => {
    const user = userEvent.setup();
    const deleteSpy = vi.fn().mockResolvedValue(undefined);

    render(
      <CheckpointsPage
        checkpoints={mockCheckpoints}
        onPinCheckpoint={vi.fn()}
        onCompare={vi.fn()}
        onRestore={vi.fn()}
        onRename={vi.fn()}
        onDelete={deleteSpy}
        operationBlocked={false}
      />
    );

    const deleteButtons = screen.getAllByRole("button", { name: "Delete" });
    await user.click(deleteButtons[1]); // Delete Vanilla Plus Utility

    expect(deleteSpy).toHaveBeenCalledWith("Vanilla Plus Utility");
  });

  it("T1.7: renders home page drift alert banner with direct Inspect Diff and Update actions", async () => {
    const user = userEvent.setup();
    const inspectSpy = vi.fn();
    const updateSpy = vi.fn();

    render(
      <HomeDriftAlertBar
        checkpointName="Cycle 214 Heavy Fleet"
        driftedModsCount={2}
        onInspectDiff={inspectSpy}
        onUpdateCheckpoint={updateSpy}
      />
    );

    const alert = screen.getByRole("alert", { name: "Launch Configuration Drift Alert" });
    expect(alert).toHaveTextContent("Launch configuration has drifted from pinned checkpoint 'Cycle 214 Heavy Fleet' (2 mods altered).");

    await user.click(within(alert).getByRole("button", { name: "Inspect Diff" }));
    expect(inspectSpy).toHaveBeenCalledTimes(1);

    await user.click(within(alert).getByRole("button", { name: "Update Checkpoint" }));
    expect(updateSpy).toHaveBeenCalledTimes(1);
  });

  // ========== TIER 2: BOUNDARY VALUE ANALYSIS & ERROR / FAULT INJECTION (>= 5 tests) ==========

  it("T2.1: refuses checkpoint restoration when required mods are missing on disk (INCOMPLETE status)", async () => {
    const user = userEvent.setup();
    const restoreSpy = vi.fn();

    render(
      <CheckpointsPage
        checkpoints={mockCheckpoints}
        onPinCheckpoint={vi.fn()}
        onCompare={vi.fn()}
        onRestore={restoreSpy}
        onRename={vi.fn()}
        onDelete={vi.fn()}
        operationBlocked={false}
      />
    );

    const restoreButtons = screen.getAllByRole("button", { name: "Restore…" });
    await user.click(restoreButtons[2]); // Old Campaign Archived (INCOMPLETE)

    const modal = await screen.findByRole("dialog", { name: "Restore Checkpoint Modal" });
    const alert = within(modal).getByRole("alert");
    expect(alert).toHaveTextContent("Cannot restore: Required mods are missing from your disk: legacy_faction_v1");

    const confirmBtn = within(modal).getByRole("button", { name: "Confirm Restore" });
    expect(confirmBtn).toBeDisabled();
    expect(restoreSpy).not.toHaveBeenCalled();
  });

  it("T2.2: rejects empty or whitespace-only checkpoint names", async () => {
    const user = userEvent.setup();
    const pinSpy = vi.fn();

    render(
      <CheckpointsPage
        checkpoints={mockCheckpoints}
        onPinCheckpoint={pinSpy}
        onCompare={vi.fn()}
        onRestore={vi.fn()}
        onRename={vi.fn()}
        onDelete={vi.fn()}
        operationBlocked={false}
      />
    );

    const pinBtn = screen.getByRole("button", { name: "Pin Checkpoint" });
    await user.click(pinBtn);

    expect(screen.getByRole("alert")).toHaveTextContent("Checkpoint name cannot be empty");
    expect(pinSpy).not.toHaveBeenCalled();
  });

  it("T2.3: rejects checkpoint names containing invalid control characters", async () => {
    const user = userEvent.setup();
    const pinSpy = vi.fn();

    render(
      <CheckpointsPage
        checkpoints={mockCheckpoints}
        onPinCheckpoint={pinSpy}
        onCompare={vi.fn()}
        onRestore={vi.fn()}
        onRename={vi.fn()}
        onDelete={vi.fn()}
        operationBlocked={false}
      />
    );

    const nameInput = screen.getByLabelText("Checkpoint Name");
    fireEvent.change(nameInput, { target: { value: "Invalid\x00Name\x1FTest" } });

    const pinBtn = screen.getByRole("button", { name: "Pin Checkpoint" });
    await user.click(pinBtn);

    expect(screen.getByRole("alert")).toHaveTextContent("Checkpoint name contains invalid control characters");
    expect(pinSpy).not.toHaveBeenCalled();
  });

  it("T2.4: blocks checkpoint restore and mutations while OperationLease is active (operationBlocked = true)", () => {
    render(
      <CheckpointsPage
        checkpoints={mockCheckpoints}
        onPinCheckpoint={vi.fn()}
        onCompare={vi.fn()}
        onRestore={vi.fn()}
        onRename={vi.fn()}
        onDelete={vi.fn()}
        operationBlocked={true}
      />
    );

    const pinBtn = screen.getByRole("button", { name: "Pin Checkpoint" });
    expect(pinBtn).toBeDisabled();

    const restoreButtons = screen.getAllByRole("button", { name: "Restore…" });
    restoreButtons.forEach((btn) => expect(btn).toBeDisabled());

    const deleteButtons = screen.getAllByRole("button", { name: "Delete" });
    deleteButtons.forEach((btn) => expect(btn).toBeDisabled());
  });

  it("T2.5: handles checkpoint creation IPC backend failure gracefully with error alert", async () => {
    const user = userEvent.setup();
    const pinSpy = vi.fn().mockRejectedValue(new Error("Filesystem write permission denied: ~/.starsector-preflight/checkpoints"));

    render(
      <CheckpointsPage
        checkpoints={mockCheckpoints}
        onPinCheckpoint={pinSpy}
        onCompare={vi.fn()}
        onRestore={vi.fn()}
        onRename={vi.fn()}
        onDelete={vi.fn()}
        operationBlocked={false}
      />
    );

    const nameInput = screen.getByLabelText("Checkpoint Name");
    await user.type(nameInput, "Failed Checkpoint");
    await user.click(screen.getByRole("button", { name: "Pin Checkpoint" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Filesystem write permission denied");
  });

  it("T2.6: renders empty state placeholder when no checkpoints exist", () => {
    render(
      <CheckpointsPage
        checkpoints={[]}
        onPinCheckpoint={vi.fn()}
        onCompare={vi.fn()}
        onRestore={vi.fn()}
        onRename={vi.fn()}
        onDelete={vi.fn()}
        operationBlocked={false}
      />
    );

    expect(screen.getByText("0 pinned")).toBeInTheDocument();
    expect(screen.getByText(/No checkpoints pinned yet. Pin your current working state above./)).toBeInTheDocument();
  });
});
