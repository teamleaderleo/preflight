import { useId, type ReactNode } from "react";

interface InfoTipProps {
  label: string;
  children: ReactNode;
}

export function InfoTip({ label, children }: InfoTipProps) {
  const tooltipId = useId();
  return (
    <span className="info-tip">
      <button className="info-tip__trigger" type="button" aria-label={label} aria-describedby={tooltipId}>
        <span aria-hidden="true">i</span>
      </button>
      <span className="info-tip__content" id={tooltipId} role="tooltip">{children}</span>
    </span>
  );
}
