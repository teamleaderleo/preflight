import preflightMark from "./assets/preflight-mark-v12-diagonal-candidate.png";

export default function Logo({ compact = false }: { compact?: boolean }) {
  return (
    <div className={`brand ${compact ? "brand--compact" : ""}`} aria-label="Preflight">
      <img className="brand__mark" src={preflightMark} alt="" />
      {/*
        * The wordmark alone never says what this launches. The subtitle is label-sized and lives
        * only here, in the one place a player looks once, so the rest of the app is not repeating
        * the game's name at them on every screen. It goes with the wordmark at icon width.
        */}
      {!compact && (
        <span className="brand__type">
          <strong>Preflight</strong>
          <small>for Starsector</small>
        </span>
      )}
    </div>
  );
}
