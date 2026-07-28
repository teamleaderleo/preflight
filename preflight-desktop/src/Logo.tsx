export default function Logo() {
  return (
    <div className="brand" aria-label="Starsector Preflight">
      <svg className="brand__mark" viewBox="0 0 44 44" aria-hidden="true">
        <circle cx="22" cy="22" r="11" />
        <path d="M6.5 25.5c7.5 5.7 23.6 6.8 31.8.5 3.8-2.9 2.3-5.2-1.8-5.2" />
        <path d="m25 14 1.4 4.1 4.1 1.4-4.1 1.4L25 25l-1.4-4.1-4.1-1.4 4.1-1.4z" />
      </svg>
      <span>
        <strong>Preflight</strong>
        <small>Starsector launcher</small>
      </span>
    </div>
  );
}
