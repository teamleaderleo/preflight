import { useEffect, useState } from "react";
import { CheckIcon } from "../icons";
import type { LaunchSettings } from "../types";

interface LaunchSettingsApplyBoundaryProps {
  boundary: LaunchSettings["applyBoundary"];
  saving: boolean;
  disabled: boolean;
  blockReason?: string;
  className?: string;
  onApply: (settingsToolsClosed: boolean) => void;
}

export function LaunchSettingsApplyBoundary({
  boundary,
  saving,
  disabled,
  blockReason,
  className,
  onApply,
}: LaunchSettingsApplyBoundaryProps) {
  const [confirmed, setConfirmed] = useState(false);
  useEffect(() => {
    if (disabled) setConfirmed(false);
  }, [disabled]);
  const apply = () => {
    setConfirmed(false);
    onApply(true);
  };

  return (
    <div className={`launch-apply-boundary${className ? ` ${className}` : ""}`}>
      <label className="launch-apply-boundary__confirm">
        <input
          type="checkbox"
          checked={confirmed}
          disabled={saving || disabled}
          onChange={(event) => setConfirmed(event.target.checked)}
        />
        <span>
          <strong>{boundary.confirmationLabel}</strong>
          <small>{boundary.instruction}</small>
        </span>
      </label>
      {blockReason ? <small className="launch-apply-boundary__blocked">{blockReason}</small> : null}
      <button
        className="button button--primary"
        type="button"
        onClick={apply}
        disabled={saving || disabled || !confirmed}
      >
        <CheckIcon />{saving ? "Applying…" : "Apply changes"}
      </button>
    </div>
  );
}
