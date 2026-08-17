import { useMemo } from "react";
import type { PlaytimeSnapshot, WireframeTuning } from "../types";
import { formatPlaytime } from "../uiFormat";
import type { useInstrumentHull } from "../useInstrumentHull";
import { FlightInstrument } from "./FlightInstrument";

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
    () => instrumentHull.hulls.filter((hull) => hull.featured && hull.id !== "preflight-courier"),
    [instrumentHull.hulls],
  );
  const more = useMemo(
    () => instrumentHull.hulls.filter((hull) => !hull.featured && hull.id !== "preflight-courier"),
    [instrumentHull.hulls],
  );
  const selectedIndex = featured.findIndex((hull) => hull.id === instrumentHull.selectedId);
  const stepFeatured = (direction: -1 | 1) => {
    if (featured.length === 0) return;
    const current = selectedIndex >= 0 ? selectedIndex : 0;
    instrumentHull.choose(featured[(current + direction + featured.length) % featured.length].id);
  };

  return (
    <div className="hangar-page">
      <section className="hangar-display" aria-label="Selected display ship">
        <div className="hangar-stage">
          <div className="hangar-stage__instrument">
            <FlightInstrument hull={instrumentHull.selected} variant="stage" />
          </div>
          <span className="hangar-stage__corner hangar-stage__corner--start">Local wireframe</span>
          <span className="hangar-stage__corner hangar-stage__corner--end">Idle display</span>
          <div className="hangar-stage__stepper" aria-label="Featured ship">
            <button type="button" aria-label="Previous featured ship" onClick={() => stepFeatured(-1)} disabled={featured.length === 0}>←</button>
            <button type="button" aria-label="Next featured ship" onClick={() => stepFeatured(1)} disabled={featured.length === 0}>→</button>
          </div>
        </div>
        <aside className="hangar-readout">
          {playtime?.readable && playtime.launches > 0 && playtime.totalMillis > 0 ? (
            <div
              className="hangar-playtime"
              aria-label={`${formatPlaytime(playtime.totalMillis)} recorded playtime across ${playtime.launches.toLocaleString()} sessions`}
            >
              <strong>{formatPlaytime(playtime.totalMillis)}</strong>
              <span>in Starsector · {playtime.launches.toLocaleString()} sessions</span>
              <dl>
                <div><dt>Longest session</dt><dd>{formatPlaytime(playtime.longestSessionMillis)}</dd></div>
                <div><dt>Typical session</dt><dd>{formatPlaytime(playtime.averageMillis)}</dd></div>
                <div><dt>Recorded since</dt><dd>{recordedSince(playtime.first)}</dd></div>
              </dl>
            </div>
          ) : null}
          <div className="hangar-readout__identity">
            <span>Display hull</span>
            <h2>{instrumentHull.selected.name}</h2>
            <p>{instrumentHull.selected.hullSize.replaceAll("_", " ").toLowerCase()}</p>
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
                  {hull.name}
                </button>
              ))}
            </div>
          ) : (
            <p className="hangar-loading">{instrumentHull.catalogLoaded ? "Using the Preflight ship." : "Finding your ships…"}</p>
          )}

          <label className="setting-field hangar-all-ships">
            <span>All ships</span>
            <select
              aria-label="Display ship"
              value={instrumentHull.selectedId}
              onChange={(event) => instrumentHull.choose(event.target.value)}
            >
              {featured.length > 0 ? (
                <optgroup label="Featured">
                  {featured.map((hull) => <option key={hull.id} value={hull.id}>{hull.name}</option>)}
                </optgroup>
              ) : null}
              {more.length > 0 ? (
                <optgroup label="More from this installation">
                  {more.map((hull) => <option key={hull.id} value={hull.id}>{hull.name}</option>)}
                </optgroup>
              ) : null}
              <optgroup label="Preflight">
                <option value="preflight-courier">Preflight courier</option>
              </optgroup>
            </select>
            <small>{instrumentHull.catalog
              ? `${instrumentHull.catalog.hulls.length.toLocaleString()} available`
              : instrumentHull.catalogLoaded ? "Installation catalog unavailable" : "Reading the local catalog…"}</small>
          </label>

          <details className="wireframe-customizer">
            <summary>Adjust wireframe</summary>
            <div className="wireframe-customizer__body">
              <fieldset>
                <legend>Shape</legend>
                <WireframeSlider label="Outline smoothing" setting="outerSmooth" value={instrumentHull.tuning.outerSmooth} minimum={0} maximum={0.9} step={0.02} format={(value) => value === 0 ? "None" : value.toFixed(2)} onChange={instrumentHull.customize} />
                <WireframeSlider label="Outline simplification" setting="outerDetail" value={instrumentHull.tuning.outerDetail} minimum={0} maximum={0.06} step={0.001} format={(value) => value === 0 ? "Full" : value.toFixed(3)} onChange={instrumentHull.customize} />
                <WireframeSlider label="Model height" setting="height" value={instrumentHull.tuning.height} minimum={0.2} maximum={2.2} step={0.05} format={(value) => `${value.toFixed(2)}×`} onChange={instrumentHull.customize} />
              </fieldset>
              <div className="wireframe-customizer__actions">
                <button className="button button--quiet button--compact" type="button" disabled={!instrumentHull.customized} onClick={instrumentHull.resetCustomization}>Reset this ship</button>
                <small>Saved locally. Starsector stays untouched.</small>
              </div>
            </div>
          </details>
        </aside>
      </section>
    </div>
  );
}
