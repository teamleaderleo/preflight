import { CheckIcon, CopyIcon } from "../icons";
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
  const copyLabel = copying ? "Copying setup details…" : copied ? "Setup details copied" : "Copy setup details";

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
        className="icon-button icon-button--small"
        type="button"
        aria-label={copyLabel}
        title={copyLabel}
        onClick={() => void setupCopy.copySetup()}
        disabled={operationBlocked || copying}
      >
        {copied ? <CheckIcon /> : <CopyIcon />}
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