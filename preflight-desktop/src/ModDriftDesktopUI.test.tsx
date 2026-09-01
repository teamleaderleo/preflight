import React, { useState } from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

// Data Contracts for Feature 13 (Drift Diagnostic UI Badges & Drawer)
export type DriftClassification =
  | "PRISTINE"
  | "SAME_VERSION_DRIFT"
  | "BYTECODE_DRIFT"
  | "CORRUPT_METADATA"
  | "MISSING_MOD";

export interface FileDriftItem {
  relativePath: string;
  changeType: "MODIFIED" | "ADDED" | "DELETED";
  expectedSha256?: string;
  actualSha256?: string;
  expectedSize?: number;
  actualSize?: number;
}

export interface ModDriftEntry {
  modId: string;
  name: string;
  declaredVersion: string;
  directory: string;
  classification: DriftClassification;
  severity: "none" | "warning" | "danger" | "critical";
  recommendedAction?: string;
  modifiedFilesCount: number;
  addedFilesCount: number;
  deletedFilesCount: number;
  files: FileDriftItem[];
}

export interface ModDriftReport {
  format: "starsector-preflight-mod-drift-v1";
  generatedAt: string;
  installRoot: string;
  totalModsCount: number;
  pristineModsCount: number;
  driftedModsCount: number;
  mods: ModDriftEntry[];
}

// UI Components for Feature 13
export function ModDriftBadge({
  classification,
  modifiedCount,
  onClick,
}: {
  classification: DriftClassification;
  modifiedCount?: number;
  onClick?: () => void;
}) {
  let badgeTone = "success";
  let label = "PRISTINE";

  switch (classification) {
    case "PRISTINE":
      badgeTone = "success";
      label = "PRISTINE";
      break;
    case "SAME_VERSION_DRIFT":
      badgeTone = "warning";
      label = modifiedCount ? `DRIFT (${modifiedCount} files)` : "SAME-VERSION DRIFT";
      break;
    case "BYTECODE_DRIFT":
      badgeTone = "danger";
      label = "BYTECODE DRIFT";
      break;
    case "CORRUPT_METADATA":
      badgeTone = "critical";
      label = "CORRUPT METADATA";
      break;
    case "MISSING_MOD":
      badgeTone = "critical";
      label = "MISSING ON DISK";
      break;
  }

  return (
    <button
      type="button"
      className={`mod-drift-badge mod-drift-badge--${badgeTone}`}
      onClick={onClick}
      aria-label={`Drift Status: ${label}`}
      data-testid={`drift-badge-${classification.toLowerCase()}`}
    >
      <span className="badge-bullet">●</span>
      <span className="badge-text">{label}</span>
    </button>
  );
}

export function ModDriftDrawer({
  modDrift,
  onClose,
  onInvalidateCache,
}: {
  modDrift: ModDriftEntry;
  onClose: () => void;
  onInvalidateCache: (modId: string) => Promise<void>;
}) {
  const [busy, setBusy] = useState(false);
  const [cacheInvalidated, setCacheInvalidated] = useState(false);

  const handleInvalidate = async () => {
    setBusy(true);
    await onInvalidateCache(modDrift.modId);
    setCacheInvalidated(true);
    setBusy(false);
  };

  return (
    <div className="drawer-backdrop" role="dialog" aria-modal="true" aria-label={`Drift Inspection for ${modDrift.name}`}>
      <div className="card mod-drift-drawer">
        <div className="drawer-header">
          <div>
            <h2 className="orbitron-title">MOD DRIFT DIAGNOSTIC // {modDrift.name.toUpperCase()}</h2>
            <span className="mod-version-tag">{modDrift.modId} (v{modDrift.declaredVersion})</span>
          </div>
          <button className="button button--quiet" onClick={onClose}>Close</button>
        </div>

        <div className={`drift-summary-banner drift-summary-banner--${modDrift.severity}`}>
          <strong>STATUS: [{modDrift.classification}]</strong>
          <p>{modDrift.recommendedAction ?? "Files have been altered in-place without a version bump."}</p>
        </div>

        <div className="drift-metrics">
          <span>Modified: <strong>{modDrift.modifiedFilesCount}</strong></span>
          <span>Added: <strong>{modDrift.addedFilesCount}</strong></span>
          <span>Deleted: <strong>{modDrift.deletedFilesCount}</strong></span>
        </div>

        <div className="drift-file-list" data-testid="drift-file-list">
          <h3>Detailed File Differences ({modDrift.files.length})</h3>
          {modDrift.files.length === 0 ? (
            <p className="no-files">No file discrepancies detected.</p>
          ) : (
            <table className="drift-files-table">
              <thead>
                <tr>
                  <th>Type</th>
                  <th>Relative File Path</th>
                  <th>Size Delta</th>
                </tr>
              </thead>
              <tbody>
                {modDrift.files.map((file) => {
                  const sizeDelta =
                    file.actualSize !== undefined && file.expectedSize !== undefined
                      ? file.actualSize - file.expectedSize
                      : undefined;
                  return (
                    <tr key={file.relativePath} className={`drift-file-row drift-file-row--${file.changeType.toLowerCase()}`}>
                      <td>
                        <span className={`file-type-pill file-type-pill--${file.changeType.toLowerCase()}`}>
                          {file.changeType}
                        </span>
                      </td>
                      <td className="mono">{file.relativePath}</td>
                      <td className="mono">
                        {sizeDelta !== undefined
                          ? sizeDelta > 0
                            ? `+${sizeDelta} B`
                            : `${sizeDelta} B`
                          : "N/A"}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>

        <div className="drawer-footer">
          {cacheInvalidated ? (
            <span className="success-tag" role="status">✓ Cache invalidated. Ready for re-preparation.</span>
          ) : (
            <button
              className="button button--primary"
              onClick={() => void handleInvalidate()}
              disabled={busy}
            >
              {busy ? "Invalidating Cache…" : "Invalidate Cache & Re-prepare"}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

export function ModDriftView({
  report,
  loading,
  error,
  onRefresh,
  onInvalidateModCache,
}: {
  report: ModDriftReport | null;
  loading: boolean;
  error: string | null;
  onRefresh: () => void;
  onInvalidateModCache: (modId: string) => Promise<void>;
}) {
  const [selectedMod, setSelectedMod] = useState<ModDriftEntry | null>(null);

  if (loading) {
    return (
      <div className="mod-drift-loading">
        <h2 className="orbitron-title">SCANNING MOD CONTENT DRIFT & SIGNATURES…</h2>
      </div>
    );
  }

  if (error) {
    return (
      <div className="mod-drift-error" role="alert">
        <h2>Drift Scan Failed</h2>
        <p>{error}</p>
        <button className="button button--primary" onClick={onRefresh}>Retry Scan</button>
      </div>
    );
  }

  if (!report) return null;

  return (
    <div className="mod-drift-container">
      <div className="drift-summary-bar">
        <h2 className="orbitron-title">MOD CONTENT INTEGRITY & DRIFT DIAGNOSTIC</h2>
        <div className="drift-stat-pills">
          <span className="pill pill--pristine">{report.pristineModsCount} Pristine</span>
          <span className="pill pill--drifted">{report.driftedModsCount} Drifted</span>
        </div>
      </div>

      <div className="mod-drift-list" data-testid="mod-drift-list">
        {report.mods.map((mod) => (
          <article key={mod.modId} className={`mod-card mod-card--${mod.classification.toLowerCase()}`} data-testid={`mod-card-${mod.modId}`}>
            <div className="mod-card__info">
              <strong>{mod.name}</strong>
              <small>{mod.modId} v{mod.declaredVersion}</small>
            </div>
            <div className="mod-card__status">
              <ModDriftBadge
                classification={mod.classification}
                modifiedCount={mod.modifiedFilesCount + mod.addedFilesCount + mod.deletedFilesCount}
                onClick={() => setSelectedMod(mod)}
              />
            </div>
          </article>
        ))}
      </div>

      {selectedMod && (
        <ModDriftDrawer
          modDrift={selectedMod}
          onClose={() => setSelectedMod(null)}
          onInvalidateCache={onInvalidateModCache}
        />
      )}
    </div>
  );
}

// ------------------- TEST SUITE -------------------
describe("Feature 13: Drift Diagnostic UI Badges & Drawer Test Suite", () => {
  const mockDriftReport: ModDriftReport = {
    format: "starsector-preflight-mod-drift-v1",
    generatedAt: "2026-08-18T15:15:00Z",
    installRoot: "/Applications/Starsector.app",
    totalModsCount: 4,
    pristineModsCount: 2,
    driftedModsCount: 2,
    mods: [
      {
        modId: "magiclib",
        name: "MagicLib",
        declaredVersion: "1.4.2",
        directory: "mods/MagicLib",
        classification: "PRISTINE",
        severity: "none",
        modifiedFilesCount: 0,
        addedFilesCount: 0,
        deletedFilesCount: 0,
        files: [],
      },
      {
        modId: "uaf",
        name: "United Aurora Federation",
        declaredVersion: "0.7.4a",
        directory: "mods/UAF",
        classification: "SAME_VERSION_DRIFT",
        severity: "warning",
        recommendedAction: "Invalidate cache and re-prepare textures/specs.",
        modifiedFilesCount: 2,
        addedFilesCount: 1,
        deletedFilesCount: 0,
        files: [
          {
            relativePath: "data/weapons/uaf_vocal.csv",
            changeType: "MODIFIED",
            expectedSize: 12000,
            actualSize: 12210,
          },
          {
            relativePath: "data/config/settings.json",
            changeType: "MODIFIED",
            expectedSize: 4500,
            actualSize: 4600,
          },
          {
            relativePath: "data/weapons/custom_shot.csv",
            changeType: "ADDED",
            actualSize: 850,
          },
        ],
      },
      {
        modId: "custom_janino_mod",
        name: "Custom Janino Mod",
        declaredVersion: "1.0.0",
        directory: "mods/CustomJanino",
        classification: "BYTECODE_DRIFT",
        severity: "danger",
        recommendedAction: "Mod script JAR or class files changed without version bump.",
        modifiedFilesCount: 1,
        addedFilesCount: 0,
        deletedFilesCount: 0,
        files: [
          {
            relativePath: "jars/CustomJanino.jar",
            changeType: "MODIFIED",
            expectedSize: 512000,
            actualSize: 524000,
          },
        ],
      },
      {
        modId: "broken_mod",
        name: "Broken Syntax Mod",
        declaredVersion: "0.1.0",
        directory: "mods/BrokenMod",
        classification: "CORRUPT_METADATA",
        severity: "critical",
        recommendedAction: "mod_info.json is corrupted or unparseable.",
        modifiedFilesCount: 0,
        addedFilesCount: 0,
        deletedFilesCount: 0,
        files: [],
      },
    ],
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  // ========== TIER 1: HAPPY PATH EQUIVALENCE CLASS TESTS (>= 5 tests) ==========

  it("T1.1: renders pristine badge for untouched mods with matching signatures", () => {
    render(
      <ModDriftView
        report={mockDriftReport}
        loading={false}
        error={null}
        onRefresh={vi.fn()}
        onInvalidateModCache={vi.fn()}
      />
    );

    const magiclibCard = screen.getByTestId("mod-card-magiclib");
    expect(within(magiclibCard).getByText("PRISTINE")).toBeInTheDocument();
    expect(within(magiclibCard).getByTestId("drift-badge-pristine")).toBeInTheDocument();
  });

  it("T1.2: renders warning badge for SAME_VERSION_DRIFT when CSV/JSON modified without version bump", () => {
    render(
      <ModDriftView
        report={mockDriftReport}
        loading={false}
        error={null}
        onRefresh={vi.fn()}
        onInvalidateModCache={vi.fn()}
      />
    );

    const uafCard = screen.getByTestId("mod-card-uaf");
    expect(within(uafCard).getByText("DRIFT (3 files)")).toBeInTheDocument();
    expect(within(uafCard).getByTestId("drift-badge-same_version_drift")).toBeInTheDocument();
  });

  it("T1.3: renders danger badge for BYTECODE_DRIFT when mod JAR or compiled classes differ", () => {
    render(
      <ModDriftView
        report={mockDriftReport}
        loading={false}
        error={null}
        onRefresh={vi.fn()}
        onInvalidateModCache={vi.fn()}
      />
    );

    const bytecodeCard = screen.getByTestId("mod-card-custom_janino_mod");
    expect(within(bytecodeCard).getByText("BYTECODE DRIFT")).toBeInTheDocument();
    expect(within(bytecodeCard).getByTestId("drift-badge-bytecode_drift")).toBeInTheDocument();
  });

  it("T1.4: opens drift detail drawer showing categorized list of modified and added files with size deltas", async () => {
    const user = userEvent.setup();
    render(
      <ModDriftView
        report={mockDriftReport}
        loading={false}
        error={null}
        onRefresh={vi.fn()}
        onInvalidateModCache={vi.fn()}
      />
    );

    const uafCard = screen.getByTestId("mod-card-uaf");
    const uafBadge = within(uafCard).getByRole("button", { name: /Drift Status/ });
    await user.click(uafBadge);

    const drawer = await screen.findByRole("dialog", { name: /Drift Inspection for United Aurora Federation/ });
    expect(drawer).toBeInTheDocument();

    const fileList = within(drawer).getByTestId("drift-file-list");
    expect(within(fileList).getByText("data/weapons/uaf_vocal.csv")).toBeInTheDocument();
    expect(within(fileList).getByText("+210 B")).toBeInTheDocument();
    expect(within(fileList).getByText("data/weapons/custom_shot.csv")).toBeInTheDocument();
  });

  it("T1.5: provides 1-click Invalidate Cache & Re-prepare action from drift drawer", async () => {
    const user = userEvent.setup();
    const invalidateSpy = vi.fn().mockResolvedValue(undefined);

    render(
      <ModDriftView
        report={mockDriftReport}
        loading={false}
        error={null}
        onRefresh={vi.fn()}
        onInvalidateModCache={invalidateSpy}
      />
    );

    const uafBadge = within(screen.getByTestId("mod-card-uaf")).getByRole("button", { name: /Drift Status/ });
    await user.click(uafBadge);

    const invalidateBtn = screen.getByRole("button", { name: "Invalidate Cache & Re-prepare" });
    await user.click(invalidateBtn);

    expect(invalidateSpy).toHaveBeenCalledWith("uaf");
    expect(await screen.findByRole("status")).toHaveTextContent("Cache invalidated. Ready for re-preparation.");
  });

  it("T1.6: renders summary stats bar showing Pristine vs Drifted totals", () => {
    render(
      <ModDriftView
        report={mockDriftReport}
        loading={false}
        error={null}
        onRefresh={vi.fn()}
        onInvalidateModCache={vi.fn()}
      />
    );

    expect(screen.getByText("2 Pristine")).toBeInTheDocument();
    expect(screen.getByText("2 Drifted")).toBeInTheDocument();
  });

  // ========== TIER 2: BOUNDARY VALUE ANALYSIS & ERROR / FAULT INJECTION (>= 5 tests) ==========

  it("T2.1: handles CORRUPT_METADATA when mod_info.json has syntax errors with critical badge", () => {
    render(
      <ModDriftView
        report={mockDriftReport}
        loading={false}
        error={null}
        onRefresh={vi.fn()}
        onInvalidateModCache={vi.fn()}
      />
    );

    const brokenCard = screen.getByTestId("mod-card-broken_mod");
    expect(within(brokenCard).getByText("CORRUPT METADATA")).toBeInTheDocument();
    expect(within(brokenCard).getByTestId("drift-badge-corrupt_metadata")).toBeInTheDocument();
  });

  it("T2.2: handles MISSING_MOD when mod folder was deleted while in active profile", () => {
    const reportWithMissing: ModDriftReport = {
      ...mockDriftReport,
      mods: [
        {
          modId: "deleted_mod",
          name: "Deleted Mod",
          declaredVersion: "1.0",
          directory: "mods/DeletedMod",
          classification: "MISSING_MOD",
          severity: "critical",
          recommendedAction: "Mod folder is missing from disk. Reinstall or disable.",
          modifiedFilesCount: 0,
          addedFilesCount: 0,
          deletedFilesCount: 1,
          files: [],
        },
      ],
    };

    render(
      <ModDriftView
        report={reportWithMissing}
        loading={false}
        error={null}
        onRefresh={vi.fn()}
        onInvalidateModCache={vi.fn()}
      />
    );

    expect(screen.getByText("MISSING ON DISK")).toBeInTheDocument();
  });

  it("T2.3: handles empty drift files list for pristine mod drawer", async () => {
    const user = userEvent.setup();
    render(
      <ModDriftView
        report={mockDriftReport}
        loading={false}
        error={null}
        onRefresh={vi.fn()}
        onInvalidateModCache={vi.fn()}
      />
    );

    const magiclibBadge = within(screen.getByTestId("mod-card-magiclib")).getByRole("button", { name: /Drift Status/ });
    await user.click(magiclibBadge);

    expect(await screen.findByText("No file discrepancies detected.")).toBeInTheDocument();
  });

  it("T2.4: handles drift scan IPC error with retry prompt", async () => {
    const user = userEvent.setup();
    const retrySpy = vi.fn();

    render(
      <ModDriftView
        report={null}
        loading={false}
        error="Permission denied while hashing mods directory."
        onRefresh={retrySpy}
        onInvalidateModCache={vi.fn()}
      />
    );

    expect(screen.getByRole("alert")).toHaveTextContent("Permission denied while hashing mods directory.");
    await user.click(screen.getByRole("button", { name: "Retry Scan" }));
    expect(retrySpy).toHaveBeenCalledTimes(1);
  });

  it("T2.5: handles loading state during background content hashing", () => {
    render(
      <ModDriftView
        report={null}
        loading={true}
        error={null}
        onRefresh={vi.fn()}
        onInvalidateModCache={vi.fn()}
      />
    );

    expect(screen.getByText("SCANNING MOD CONTENT DRIFT & SIGNATURES…")).toBeInTheDocument();
  });
});
