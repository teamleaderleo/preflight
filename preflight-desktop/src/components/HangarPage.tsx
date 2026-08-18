import type { useInstrumentHull } from "../useInstrumentHull";
import { FlightInstrument } from "./FlightInstrument";

type InstrumentHullState = ReturnType<typeof useInstrumentHull>;

interface HangarDialProps {
  label: string;
  value: number;
  valueText: string;
  minimum: number;
  maximum: number;
  step: number;
  onChange: (value: number) => void;
}

function HangarDial({ label, value, valueText, minimum, maximum, step, onChange }: HangarDialProps) {
  return (
    <label className="hangar-dial">
      <span>{label}</span>
      <input
        type="range"
        aria-label={label}
        aria-valuetext={valueText}
        min={minimum}
        max={maximum}
        step={step}
        value={value}
        onChange={(event) => onChange(Number(event.target.value))}
      />
    </label>
  );
}

interface HangarPageProps {
  instrumentHull: InstrumentHullState;
}

export function HangarPage({ instrumentHull }: HangarPageProps) {
  return (
    <div className="hangar-page">
      <section className="hangar-display hangar-display--minimal" aria-label="Selected display ship">
        <div className="hangar-stage hangar-stage--minimal">
          <div className="hangar-stage__instrument">
            <FlightInstrument hull={instrumentHull.selected} variant="stage" />
          </div>

          <div className="hangar-identity">
            <h2>{instrumentHull.selected.name}</h2>
            <p>{instrumentHull.selected.hullSize.replaceAll("_", " ").toLowerCase()}</p>
          </div>

        </div>

        <div className="hangar-dock">
          <select
            className="hangar-hull-select"
            aria-label="Display ship"
            value={instrumentHull.selectedId}
            onChange={(event) => instrumentHull.choose(event.target.value)}
          >
            {instrumentHull.hulls.map((hull) => (
              <option key={hull.id} value={hull.id}>{hull.name}</option>
            ))}
          </select>

          <div className="hangar-dials" aria-label="Wireframe appearance">
            <HangarDial
              label="Smooth"
              value={instrumentHull.tuning.outerSmooth}
              valueText={instrumentHull.tuning.outerSmooth === 0 ? "None" : instrumentHull.tuning.outerSmooth.toFixed(2)}
              minimum={0}
              maximum={0.9}
              step={0.02}
              onChange={(value) => instrumentHull.customize({ outerSmooth: value, innerSmooth: value })}
            />
            <HangarDial
              label="Detail"
              value={instrumentHull.tuning.outerDetail}
              valueText={instrumentHull.tuning.outerDetail === 0 ? "Full" : instrumentHull.tuning.outerDetail.toFixed(3)}
              minimum={0}
              maximum={0.06}
              step={0.001}
              onChange={(value) => instrumentHull.customize({ outerDetail: value, innerDetail: value })}
            />
            <HangarDial
              label="Depth"
              value={instrumentHull.tuning.height}
              valueText={`${instrumentHull.tuning.height.toFixed(2)}×`}
              minimum={0.2}
              maximum={2.2}
              step={0.05}
              onChange={(value) => instrumentHull.customize({ height: value })}
            />
          </div>

          <button
            className="button button--quiet button--compact hangar-reset"
            type="button"
            title="Reset appearance"
            disabled={!instrumentHull.customized}
            onClick={instrumentHull.resetCustomization}
          >
            Reset
          </button>
        </div>
      </section>
    </div>
  );
}
