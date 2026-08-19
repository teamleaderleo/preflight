import type { OptimizationPreset } from "../types";
import { useCopySetup } from "../useCopySetup";

interface RunRecoveryActionsProps {
  optimizationPreset: OptimizationPreset;
  operationBlocked: boolean;
  onRelaunch: () => void;
  onGetHelp: () => void;
  onDismiss: () => void;
}

export function RunRecoveryActions({
  optimizationPreset,
  operationBlocked,
  onRelaunch,
  onGetHelp,
  onDismiss,
}: RunRecoveryActionsProps) {
  const setupCopy = useCopySetup(optimizationPreset);
  const copying = setupCopy.state === "copying";
  const copied = setupCopy.state === "copied";

  return (
    <div className="run-recovery__actions">
      <button
        className="button button--primary button--compact"
        type="button"
        onClick={onRelaunch}
        disabled={operationBlocked}
      >
        Relaunch
      </button>
      <button
        className="button button--quiet button--compact"
        type="button"
        onClick={() => void setupCopy.copySetup()}
        disabled={operationBlocked || copying}
      >
        {copying ? "Copying…" : copied ? "Setup copied" : "Copy setup"}
      </button>
      <button className="button button--quiet button--compact" type="button" onClick={onGetHelp}>
        Get help
      </button>
      <button className="button button--quiet button--compact" type="button" onClick={onDismiss}>
        Dismiss
      </button>
    </div>
  );
}
