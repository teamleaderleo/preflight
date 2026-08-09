import type { NoticeTone } from "../types";

interface NoticeBannerProps {
  message: string;
  tone?: NoticeTone;
  actionLabel?: string;
  onAction?: () => void;
}

export function NoticeBanner({
  message,
  tone = "info",
  actionLabel,
  onAction,
}: NoticeBannerProps) {
  if (!message) return null;
  return (
    <div
      className={`notice notice--${tone}`}
      role={tone === "error" ? "alert" : "status"}
    >
      <span aria-hidden="true">{tone === "error" ? "!" : tone === "warning" ? "△" : "✦"}</span>
      <p>{message}</p>
      {actionLabel && onAction ? (
        <button type="button" onClick={onAction}>{actionLabel}</button>
      ) : null}
    </div>
  );
}
