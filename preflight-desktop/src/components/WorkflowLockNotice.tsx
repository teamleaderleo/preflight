import { ArrowIcon } from "../icons";

interface WorkflowLockNoticeProps {
  reason: string;
  actionLabel?: string;
  onAction?: () => void;
}

export function WorkflowLockNotice({
  reason,
  actionLabel,
  onAction,
}: WorkflowLockNoticeProps) {
  return (
    <div className="workflow-lock" role="status">
      <p>{reason}</p>
      {actionLabel && onAction ? (
        <button className="text-button" type="button" onClick={onAction}>
          {actionLabel} <ArrowIcon />
        </button>
      ) : null}
    </div>
  );
}
