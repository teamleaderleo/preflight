/**
 * A deliberately small flight-recorder illustration. It uses ordinary SVG and compositor-only
 * motion so the desktop does not acquire a rendering engine for decoration.
 */
export function FlightInstrument() {
  return (
    <div className="flight-instrument" aria-hidden="true">
      <div className="flight-instrument__drift">
        <svg viewBox="0 0 240 150" focusable="false">
          <g className="flight-instrument__scope">
            <ellipse cx="124" cy="76" rx="96" ry="48" />
            <ellipse cx="124" cy="76" rx="70" ry="34" />
            <path d="M18 76h212M124 18v116" />
            <path className="flight-instrument__arc" d="M38 105c33 34 111 40 162-2" />
            <path className="flight-instrument__tick" d="M31 70v12m186-12v12M118 25h12m-12 102h12" />
          </g>
          <g className="flight-instrument__ship" transform="rotate(-13 124 76)">
            <path className="flight-instrument__ship-fill" d="M51 76 92 51l87 25-87 25Z" />
            <path d="M51 76 92 51l87 25-87 25Zm41-25 27 25-27 25m27-25 60 0" />
            <path className="flight-instrument__wing" d="m91 58-35-29 77 33m-42 32-35 29 77-33" />
            <path className="flight-instrument__pod" d="m65 61-26-11-12 9 38 17m0 15-26 11-12-9 38-17" />
            <path className="flight-instrument__cockpit" d="m105 59 36 10 13 7-13 7-36 10 14-17Z" />
            <path className="flight-instrument__engine" d="m52 69-20-4m20 18-20 4" />
            <circle cx="119" cy="76" r="3" />
          </g>
        </svg>
      </div>
    </div>
  );
}
