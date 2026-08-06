export default function Logo({ compact = false }: { compact?: boolean }) {
  return (
    <div className={`brand ${compact ? "brand--compact" : ""}`} aria-label="Preflight for Starsector">
      <svg className="brand__mark" viewBox="0 0 56 56" aria-hidden="true">
        <defs>
          <linearGradient id="planet" x1="12" y1="8" x2="42" y2="46" gradientUnits="userSpaceOnUse">
            <stop stopColor="#FFB895" />
            <stop offset="1" stopColor="#F07463" />
          </linearGradient>
        </defs>
        <circle cx="28" cy="28" r="19" fill="url(#planet)" />
        <path d="M13.5 31.5c6 5.2 18.4 7.8 28.6 3.2" fill="none" stroke="#fff7eb" strokeWidth="3" strokeLinecap="round" />
        <path d="M11 35.5c-5.8-2.6-7.1-5.4-5-7.3 2.2-2 8.6-.8 14.8 1.3 8.4 2.8 19 4 25.9 1.4 4.6-1.7 5.9-4 4-5.2-1.1-.8-3.3-.8-5.6-.3" fill="none" stroke="#233757" strokeWidth="2.5" strokeLinecap="round" />
        <path d="m36.5 13.5.9 2.6 2.6.9-2.6.9-.9 2.6-.9-2.6-2.6-.9 2.6-.9z" fill="#fff7eb" />
      </svg>
      {!compact && (
        <span className="brand__type">
          <strong>Preflight</strong>
          <small>for Starsector</small>
        </span>
      )}
    </div>
  );
}
