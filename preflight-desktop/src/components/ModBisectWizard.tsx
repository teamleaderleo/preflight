import React, { useState } from "react";
import type { BisectSessionSnapshot } from "../types";
import {
  BisectProgressBar,
  BisectActivePartitionCard,
  BisectCulpritView,
} from "./BisectStepView";

export interface ModBisectWizardProps {
  session: BisectSessionSnapshot;
  onLaunchTest: () => Promise<void>;
  onRecordVerdict: (verdict: "PASS" | "FAIL" | "SKIP") => Promise<void>;
  onApplyResolution: () => Promise<void>;
  onAbortSession: () => Promise<void>;
  operationBlocked: boolean;
}

export function ModBisectWizard({
  session,
  onLaunchTest,
  onRecordVerdict,
  onApplyResolution,
  onAbortSession,
  operationBlocked,
}: ModBisectWizardProps) {
  const [busy, setBusy] = useState(false);
  const [testLaunched, setTestLaunched] = useState(false);

  const handleLaunch = async () => {
    setBusy(true);
    try {
      await onLaunchTest();
      setTestLaunched(true);
    } finally {
      setBusy(false);
    }
  };

  const handleVerdict = async (verdict: "PASS" | "FAIL" | "SKIP") => {
    setBusy(true);
    try {
      await onRecordVerdict(verdict);
      setTestLaunched(false);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="modal-backdrop crt-theme" role="dialog" aria-modal="true" aria-label="Mod Bisect Assistant Wizard">
      <div className="card crt-wizard-card">
        <div className="crt-header">
          <div>
            <h1 className="orbitron-title">MOD FAILURE BISECT ASSISTANT</h1>
            <span className="step-badge">
              STEP {session.stepNumber} OF ~{session.totalEstimatedSteps} // {session.suspectMods.length} SUSPECTS REMAINING
            </span>
          </div>
          <span className={`status-pill status-pill--${session.state.toLowerCase()}`}>
            [{session.state}]
          </span>
        </div>

        {(session.state === "TESTING" || session.state === "INITIALIZING" || session.state === "VERIFYING") && (
          <>
            <BisectProgressBar
              stepNumber={session.stepNumber}
              totalSteps={session.totalEstimatedSteps}
              suspectsRemaining={session.suspectMods.length}
            />

            <BisectActivePartitionCard
              currentTestSubset={session.currentTestSubset}
              fixedBaseMods={session.fixedBaseMods}
            />

            <div className="bisect-controls-bar">
              <button
                className="button button--primary"
                onClick={() => void handleLaunch()}
                disabled={busy || operationBlocked}
              >
                {busy ? "Launching…" : "[ 1. Launch Test Run ]"}
              </button>

              <div className="verdict-buttons" data-testid="verdict-buttons">
                <button
                  className="button button--success"
                  onClick={() => void handleVerdict("PASS")}
                  disabled={busy || !testLaunched || operationBlocked}
                >
                  ✓ Passed (No Crash)
                </button>
                <button
                  className="button button--danger"
                  onClick={() => void handleVerdict("FAIL")}
                  disabled={busy || !testLaunched || operationBlocked}
                >
                  ✗ Failed (Crashed)
                </button>
                <button
                  className="button button--quiet"
                  onClick={() => void handleVerdict("SKIP")}
                  disabled={busy || operationBlocked}
                >
                  ↷ Skip Partition
                </button>
              </div>

              <button
                className="button button--quiet"
                onClick={() => void onAbortSession()}
                disabled={busy}
              >
                Abort & Restore Original Setup
              </button>
            </div>
          </>
        )}

        {session.state === "CULPRIT_FOUND" && session.candidateCulprit && (
          <BisectCulpritView
            culprit={session.candidateCulprit}
            onApplyResolution={onApplyResolution}
            onAbort={onAbortSession}
            busy={busy}
          />
        )}

        {session.state === "COMPLETED" && (
          <div className="bisect-completed-card" data-testid="bisect-completed">
            <h2>Bisect Session Completed Successfully</h2>
            <p>Original mod list has been updated with the culprit removed and clean state verified.</p>
          </div>
        )}
      </div>
    </div>
  );
}

export {
  BisectProgressBar,
  BisectActivePartitionCard,
  BisectCulpritView,
};
