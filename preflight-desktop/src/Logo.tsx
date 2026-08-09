import preflightMark from "./assets/preflight-mark-v10.png";

export default function Logo({ compact = false }: { compact?: boolean }) {
  return (
    <div className={`brand ${compact ? "brand--compact" : ""}`} aria-label="Preflight for Starsector">
      <img className="brand__mark" src={preflightMark} alt="" />
      {!compact && (
        <span className="brand__type">
          <strong>Preflight</strong>
          <small>for Starsector</small>
        </span>
      )}
    </div>
  );
}
