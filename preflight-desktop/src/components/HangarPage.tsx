import type { useInstrumentHull } from "../useInstrumentHull";
import { FEATURED_HULL_IDS } from "../useInstrumentHull";
import { useInstrumentMotion } from "../useInstrumentMotion";
import { FlightInstrument } from "./FlightInstrument";
import { HullPicker } from "./HullPicker";

type InstrumentHullState = ReturnType<typeof useInstrumentHull>;

const FEATURED_IDS = new Set<string>(FEATURED_HULL_IDS);

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
      <output>{valueText}</output>
    </label>
  );
}

interface HangarPageProps {
  instrumentHull: InstrumentHullState;
}

export function HangarPage({ instrumentHull }: HangarPageProps) {
  const { motion, setMotion } = useInstrumentMotion();
  const featured = instrumentHull.hulls.filter((hull) => FEATURED_IDS.has(hull.id));
  const additional = instrumentHull.hulls.filter((hull) => !FEATURED_IDS.has(hull.id));
  const selectedIsFeatured = FEATURED_IDS.has(instrumentHull.selectedId);
  const quickHulls = selectedIsFeatured
    ? featured
    : [...featured, instrumentHull.selected];

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

        <div className="hangar-dock hangar-dock--catalog">
          <div className="hangar-selection">
            <select
              className="hangar-hull-select"
              aria-label="Display ship"
              value={instrumentHull.selectedId}
              onChange={(event) => instrumentHull.choose(event.target.value)}
            >
              {quickHulls.map((hull) => (
                <option key={hull.id} value={hull.id}>{hull.name}</option>
              ))}
            </select>
            <span className="hangar-catalog-status" aria-live="polite">
              {instrumentHull.catalog
                ? `${instrumentHull.catalog.hulls.length.toLocaleString()} installed`
                : instrumentHull.catalogLoaded ? "Included ships" : "Finding installed ships…"}
            </span>
            <button
              className="button button--quiet button--compact"
              type="button"
              aria-pressed={motion === "still"}
              title={motion === "rotate" ? "Stop decorative hull rotation" : "Resume decorative hull rotation"}
              onClick={() => setMotion(motion === "rotate" ? "still" : "rotate")}
            >
              {motion === "rotate" ? "Motion: Rotate" : "Motion: Still"}
            </button>
            {additional.length > 0 ? (
              <HullPicker
                hulls={additional}
                selectedId={instrumentHull.selectedId}
                onChoose={instrumentHull.choose}
              />
            ) : null}
          </div>

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
              label="Interior smooth"
              value={instrumentHull.tuning.innerSmooth}
              valueText={instrumentHull.tuning.innerSmooth === 0 ? "None" : instrumentHull.tuning.innerSmooth.toFixed(2)}
              minimum={0}
              maximum={0.9}
              step={0.02}
              onChange={(value) => instrumentHull.customize({ innerSmooth: value })}
            />
            <HangarDial
              label="Interior detail"
              value={instrumentHull.tuning.innerDetail}
              valueText={instrumentHull.tuning.innerDetail === 0 ? "Full" : instrumentHull.tuning.innerDetail.toFixed(3)}
              minimum={0}
              maximum={0.06}
              step={0.001}
              onChange={(value) => instrumentHull.customize({ innerDetail: value })}
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
