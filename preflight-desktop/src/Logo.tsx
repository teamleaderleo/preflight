import preflightMark from "./assets/preflight-mark-v12-diagonal-candidate.png";

export default function Logo({ compact = false }: { compact?: boolean }) {
  return (
    <div className={`brand ${compact ? "brand--compact" : ""}`} aria-label="Preflight">
      <img className="brand__mark" src={preflightMark} alt="" />
      {!compact && (
        <span className="brand__type">
          <strong>Preflight</strong>
        </span>
      )}
    </div>
  );
}
