import { useMemo } from "react";
import type { PlaytimeSnapshot, WireframeTuning } from "../types";
import { formatPlaytime, splitPlaytime } from "../uiFormat";
import type { useInstrumentHull } from "../useInstrumentHull";
import { FlightInstrument } from "./FlightInstrument";
import { HullPicker } from "./HullPicker";

type InstrumentHullState = ReturnType<typeof useInstrumentHull>;

interface WireframeSliderProps {
  label: string;
  setting: keyof WireframeTuning;
  value: number;
  minimum: number;
  maximum: number;
  step: number;
  format: (value: number) => string;
  onChange: (patch: Partial<WireframeTuning>) => void;
}

function WireframeSlider({ label, setting, value, minimum, maximum, step, format, onChange }: WireframeSliderProps) {
  const valueText = format(value);
  return (
    <label className="wireframe-slider">
      <span>{label}</span>
      <input
        type="range"
        aria-label={label}
        aria-valuetext={valueText}
        min={minimum}
        max={maximum}
        step={step}
        value={value}
        onChange={(event) => onChange({ [setting]: Number(event.target.value) })}
      />
      <output>{valueText}</output>
    </label>
  );
}

interface HangarPageProps {
  instrumentHull: InstrumentHullState;
  playtime?: PlaytimeSnapshot;
}

function recordedSince(first: string | null) {
  if (!first) return "Not recorded yet";
  const date = new Date(first);
  if (Number.isNaN(date.getTime())) return "Unknown";
  return date.toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}

export function HangarPage({ instrumentHull, playtime }: HangarPageProps) {
  const featured = useMemo(
    () => instrumentHull.hulls.filter((hull) => hull.featured),
    [instrumentHull.hulls],
  );
  const more = useMemo(
    () => instrumentHull.hulls.filter((hull) => !hull.featured),
    [instrumentHull.hulls],
  );
  const selectedIndex = featured.findIndex((hull) => hull.id === instrumentHull.selectedId);
  const recordedPlaytime = playtime?.readable && playtime.launches > 0 && playtime.totalMillis > 0
    ? playtime
    : null;
  const total = recordedPlaytime ? splitPlaytime(recordedPlaytime.totalMillis) : { value: "0", unit: "h" };

  return (
    <div className="hangar-page">
      <section className="hangar-display" aria-label="Selected display ship">
        <div className="hangar-upper">
          <div className="hangar-stage">
            <div className="hangar-stage__instrument">
              <FlightInstrument hull={instrumentHull.selected} variant="stage" />
            </div>
            <span className="hangar-stage__corner hangar-stage__corner--start">Wireframe bay 01</span>
            <span className="hangar-stage__corner hangar-stage__corner--end">
              {selectedIndex >= 0 ? `${String(selectedIndex + 1).padStart(2, "0")} / ${String(featured.length).padStart(2, "0")}` : "Local hull"}
            </span>
          </div>
          <aside className="hangar-readout">
            <div
              className={recordedPlaytime ? "hangar-playtime" : "hangar-playtime hangar-playtime--empty"}
              aria-label={recordedPlaytime
                ? `${formatPlaytime(recordedPlaytime.totalMillis)} recorded playtime across ${recordedPlaytime.launches.toLocaleString()} sessions`
                : "No recorded playtime yet"}
            >
              {/* The number carries the weight; the unit is the part nobody has to read. */}
              <strong>{total.value}<i>{total.unit}</i></strong>
              <span>{recordedPlaytime ? `${recordedPlaytime.launches.toLocaleString()} recorded sessions` : "Recorded playtime"}</span>
              <dl>
                <div><dt>Longest</dt><dd>{recordedPlaytime ? formatPlaytime(recordedPlaytime.longestSessionMillis) : "—"}</dd></div>
                <div><dt>Typical</dt><dd>{recordedPlaytime ? formatPlaytime(recordedPlaytime.averageMillis) : "—"}</dd></div>
                <div><dt>Since</dt><dd>{recordedPlaytime ? recordedSince(recordedPlaytime.first) : "Next launch"}</dd></div>
              </dl>
            </div>
            <div className="hangar-readout__identity">
              <span>Display hull</span>
              <h2>{instrumentHull.selected.name}</h2>
              <p>{instrumentHull.selected.hullSize.replaceAll("_", " ").toLowerCase()}</p>
            </div>
          </aside>
        </div>

        <div className="hangar-console">
          <section className="hangar-console__ships" aria-labelledby="hangar-fleet-title">
            <div className="hangar-console__heading">
              <div>
                <span className="hangar-console__eyebrow">Display ship</span>
                <h2 id="hangar-fleet-title">Featured hulls</h2>
              </div>
              <span className="hangar-console__status" aria-live="polite">{instrumentHull.catalog
                ? `${instrumentHull.catalog.hulls.length.toLocaleString()} installed`
                : instrumentHull.catalogLoaded ? "Included hulls" : "Finding installed hulls…"}</span>
            </div>
            {featured.length > 0 ? (
              <div className="hangar-featured" role="group" aria-label="Featured ships">
                {featured.map((hull) => (
                  <button
                    key={hull.id}
                    className={hull.id === instrumentHull.selectedId ? "hangar-ship hangar-ship--selected" : "hangar-ship"}
                    type="button"
                    aria-label={hull.name}
                    title={hull.name}
                    aria-pressed={hull.id === instrumentHull.selectedId}
                    onClick={() => instrumentHull.choose(hull.id)}
                  >
                    <span>{hull.name}</span>
                    <small>{hull.hullSize.replaceAll("_", " ").toLowerCase()}</small>
                  </button>
                ))}
              </div>
            ) : (
              <p className="hangar-loading">Finding ships…</p>
            )}

            {more.length > 0 ? (
              <HullPicker hulls={more} selectedId={instrumentHull.selectedId} onChoose={instrumentHull.choose} />
            ) : null}
          </section>

          <section className="hangar-tuning" aria-labelledby="hangar-tuning-title">
            <div className="hangar-console__heading">
              <div>
                <span className="hangar-console__eyebrow">Appearance</span>
                <h2 id="hangar-tuning-title">Wireframe</h2>
              </div>
              <button className="button button--quiet button--compact" type="button" disabled={!instrumentHull.customized} onClick={instrumentHull.resetCustomization}>Reset</button>
            </div>
            <div className="hangar-tuning__grid">
              <fieldset className="hangar-tuning__group hangar-tuning__group--outline">
                <legend>Silhouette</legend>
                <WireframeSlider label="Outline smoothing" setting="outerSmooth" value={instrumentHull.tuning.outerSmooth} minimum={0} maximum={0.9} step={0.02} format={(value) => value === 0 ? "None" : value.toFixed(2)} onChange={instrumentHull.customize} />
                <WireframeSlider label="Outline simplification" setting="outerDetail" value={instrumentHull.tuning.outerDetail} minimum={0} maximum={0.06} step={0.001} format={(value) => value === 0 ? "Full" : value.toFixed(3)} onChange={instrumentHull.customize} />
              </fieldset>
              <fieldset className="hangar-tuning__group hangar-tuning__group--interior">
                <legend>Interior</legend>
                <WireframeSlider label="Interior smoothing" setting="innerSmooth" value={instrumentHull.tuning.innerSmooth} minimum={0} maximum={0.9} step={0.02} format={(value) => value === 0 ? "None" : value.toFixed(2)} onChange={instrumentHull.customize} />
                <WireframeSlider label="Interior simplification" setting="innerDetail" value={instrumentHull.tuning.innerDetail} minimum={0} maximum={0.06} step={0.001} format={(value) => value === 0 ? "Full" : value.toFixed(3)} onChange={instrumentHull.customize} />
              </fieldset>
              <fieldset className="hangar-tuning__group hangar-tuning__group--form">
                <legend>Depth</legend>
                <WireframeSlider label="Model height" setting="height" value={instrumentHull.tuning.height} minimum={0.2} maximum={2.2} step={0.05} format={(value) => `${value.toFixed(2)}×`} onChange={instrumentHull.customize} />
              </fieldset>
            </div>
          </section>
        </div>
      </section>
    </div>
  );
}
