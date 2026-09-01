import React from "react";
import type { BisectOffendingMod } from "../types";

export function BisectProgressBar({
  stepNumber,
  totalSteps,
  suspectsRemaining,
}: {
  stepNumber: number;
  totalSteps: number;
  suspectsRemaining: number;
}) {
  const percent = totalSteps > 0 ? Math.min(100, Math.round((stepNumber / totalSteps) * 100)) : 0;
  const remainingLaunches = Math.max(0, totalSteps - stepNumber);

  return (
    <div className="bisect-progress-container" data-testid="bisect-progress">
      <div className="bisect-progress-meta">
        <span>PROGRESS: <strong>{percent}%</strong></span>
        <span>ESTIMATED ~{remainingLaunches} TEST LAUNCHES REMAINING</span>
      </div>
      <div className="progress-bar-track">
        <div className="progress-bar-fill" style={{ width: `${percent}%` }} />
      </div>
    </div>
  );
}

export function BisectActivePartitionCard({
  currentTestSubset,
  fixedBaseMods,
}: {
  currentTestSubset: string[];
  fixedBaseMods: string[];
}) {
  return (
    <section className="card bisect-partition-card" aria-label="Current Test Partition">
      <div className="partition-header">
        <h3>ACTIVE TEST PARTITION ({currentTestSubset.length} MODS ENABLED)</h3>
        <span className="field-note">Dependency-closed topological partition</span>
      </div>

      <div className="mod-tag-cloud" data-testid="mod-tag-cloud">
        {currentTestSubset.map((modId) => {
          const isBase = fixedBaseMods.includes(modId);
          return (
            <span
              key={modId}
              className={`mod-tag mod-tag--${isBase ? "base" : "testing"}`}
              data-testid={`mod-tag-${modId}`}
            >
              {modId} {isBase ? "(Prerequisite Base)" : "(Testing)"}
            </span>
          );
        })}
      </div>
    </section>
  );
}

export function BisectCulpritView({
  culprit,
  onApplyResolution,
  onAbort,
  busy,
}: {
  culprit: BisectOffendingMod;
  onApplyResolution: () => Promise<void>;
  onAbort: () => Promise<void>;
  busy: boolean;
}) {
  return (
    <div className="card bisect-culprit-card" role="region" aria-label="Culprit Isolated Summary">
      <div className="culprit-header">
        <span className="culprit-badge">🎯 CULPRIT MOD ISOLATED</span>
        <h2 className="orbitron-title">{culprit.name.toUpperCase()} ({culprit.id} v{culprit.version})</h2>
        <span className="culprit-path">{culprit.directory}</span>
      </div>

      {culprit.crashingTrace && (
        <div className="culprit-trace">
          <h3>Attributed Crash Trace:</h3>
          <pre className="mono trace-box">{culprit.crashingTrace}</pre>
        </div>
      )}

      {culprit.downstreamDependents.length > 0 ? (
        <div className="downstream-warning">
          <strong>⚠️ Downstream mods depending on this mod:</strong>
          <ul>
            {culprit.downstreamDependents.map((dep) => (
              <li key={dep}>{dep}</li>
            ))}
          </ul>
          <p>Disabling this mod will also require disabling or updating these dependent mods.</p>
        </div>
      ) : (
        <p className="clean-notice">✓ No other active mods depend on this mod.</p>
      )}

      <div className="culprit-actions">
        <button
          className="button button--primary"
          onClick={() => void onApplyResolution()}
          disabled={busy}
        >
          {busy ? "Applying Fix…" : `[ 1-Click Fix: Disable '${culprit.id}' & Restore Rest ]`}
        </button>
        <button
          className="button button--quiet"
          onClick={() => void onAbort()}
          disabled={busy}
        >
          Restore Original Mod List
        </button>
      </div>
    </div>
  );
}
