import { useEffect, useId, useMemo, useRef, useState, type CSSProperties, type KeyboardEvent } from "react";
import { PauseIcon, PlayIcon, RefreshIcon, RotateClockwiseIcon, RotateCounterClockwiseIcon } from "../icons";
import type { useInstrumentHull } from "../useInstrumentHull";
import type { WireframeHull } from "../types";
import { useInstrumentMotion } from "../useInstrumentMotion";
import { FlightInstrument } from "./FlightInstrument";

type InstrumentHullState = ReturnType<typeof useInstrumentHull>;

const HULL_RESULT_LIMIT = 8;

function hullSizeLabel(hullSize: string): string {
  return hullSize === "CAPITAL_SHIP" ? "capital" : hullSize.replaceAll("_", " ").toLowerCase();
}

function hullMatches(hull: WireframeHull, query: string): boolean {
  const needle = query.trim().toLowerCase();
  if (!needle) return true;
  return (
    hull.name.toLowerCase().includes(needle)
    || hull.id.toLowerCase().includes(needle)
    || hullSizeLabel(hull.hullSize).includes(needle)
  );
}

function findExactHull(hulls: WireframeHull[], value: string): WireframeHull | undefined {
  return hulls.find((hull) =>
    hull.name.localeCompare(value, undefined, { sensitivity: "accent" }) === 0
    || hull.id.localeCompare(value, undefined, { sensitivity: "accent" }) === 0
  );
}

interface HangarHullChooserProps {
  hulls: WireframeHull[];
  selected: WireframeHull;
  onChoose: (id: string) => void;
  catalogStatus: string;
}

function HangarHullChooser({ hulls, selected, onChoose, catalogStatus }: HangarHullChooserProps) {
  const listId = useId();
  const listRef = useRef<HTMLDivElement>(null);
  const [query, setQuery] = useState(selected.name);
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);

  useEffect(() => {
    setQuery(selected.name);
    setActiveIndex(0);
  }, [selected.id, selected.name]);

  const results = useMemo(() => {
    const exact = findExactHull(hulls, query);
    if (exact?.id === selected.id) {
      return [selected, ...hulls.filter((hull) => hull.id !== selected.id)].slice(0, HULL_RESULT_LIMIT);
    }

    const candidates = query.trim()
      ? hulls.filter((hull) => hullMatches(hull, query))
      : hulls;
    return candidates.slice(0, HULL_RESULT_LIMIT);
  }, [hulls, query, selected]);

  const activeHull = results[activeIndex] ?? results[0];

  useEffect(() => {
    if (!open) return;
    listRef.current
      ?.querySelector<HTMLElement>('[data-active="true"]')
      ?.scrollIntoView?.({ block: "nearest" });
  }, [activeIndex, open, results]);

  const choose = (hull: WireframeHull) => {
    if (hull.id !== selected.id) {
      onChoose(hull.id);
    }
    setQuery(hull.name);
    setOpen(false);
    setActiveIndex(0);
  };

  const restoreSelected = () => {
    setQuery(selected.name);
    setOpen(false);
    setActiveIndex(0);
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setOpen(true);
      setActiveIndex((index) => results.length === 0 ? 0 : (index + 1) % results.length);
      return;
    }
    if (event.key === "ArrowUp") {
      event.preventDefault();
      setOpen(true);
      setActiveIndex((index) => results.length === 0 ? 0 : (index - 1 + results.length) % results.length);
      return;
    }
    if (event.key === "Enter") {
      const exact = findExactHull(hulls, query);
      const chosen = open ? activeHull ?? exact : exact;
      if (chosen) {
        event.preventDefault();
        choose(chosen);
      }
      return;
    }
    if (event.key === "Escape") {
      event.preventDefault();
      restoreSelected();
    }
  };

  const activeOptionId = open && activeHull ? `${listId}-option-${activeIndex}` : undefined;

  return (
    <div className="hangar-hull-combobox" data-open={open ? "true" : "false"}>
      <input
        id={`${listId}-input`}
        className="hangar-hull-combobox__input"
        type="text"
        role="combobox"
        aria-label="Display ship"
        aria-autocomplete="list"
        aria-expanded={open}
        aria-controls={listId}
        aria-activedescendant={activeOptionId}
        autoComplete="off"
        spellCheck={false}
        value={query}
        onFocus={(event) => {
          setOpen(true);
          setActiveIndex(0);
          event.currentTarget.select();
        }}
        onClick={() => setOpen(true)}
        onChange={(event) => {
          setQuery(event.target.value);
          setOpen(true);
          setActiveIndex(0);
        }}
        onKeyDown={handleKeyDown}
        onBlur={() => {
          const exact = findExactHull(hulls, query);
          if (exact && exact.id !== selected.id) {
            choose(exact);
          } else if (!exact) {
            restoreSelected();
          } else {
            setOpen(false);
            setActiveIndex(0);
          }
        }}
      />

      {open ? (
        <div
          ref={listRef}
          id={listId}
          className="hangar-hull-combobox__list"
          role="listbox"
          aria-label="Display ships"
        >
          {results.length > 0 ? results.map((hull, index) => (
            <button
              id={`${listId}-option-${index}`}
              key={hull.id}
              className="hangar-hull-combobox__option"
              type="button"
              role="option"
              aria-label={hull.name}
              aria-selected={hull.id === selected.id}
              data-active={index === activeIndex ? "true" : "false"}
              tabIndex={-1}
              onMouseEnter={() => setActiveIndex(index)}
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => choose(hull)}
            >
              <span className="hangar-hull-combobox__name">{hull.name}</span>
              <span className="hangar-hull-combobox__meta">{hullSizeLabel(hull.hullSize)}</span>
            </button>
          )) : (
            <div className="hangar-hull-combobox__empty">No hull matches “{query}”</div>
          )}
        </div>
      ) : null}

      <div className="hangar-identity__meta">
        <span>{hullSizeLabel(selected.hullSize)}</span>
        <span aria-live="polite">{catalogStatus}</span>
      </div>
    </div>
  );
}

interface HangarDialProps {
  label: string;
  displayLabel?: string;
  value: number;
  valueText: string;
  minimum: number;
  maximum: number;
  step: number;
  onChange: (value: number) => void;
}

function HangarDial({ label, displayLabel = label, value, valueText, minimum, maximum, step, onChange }: HangarDialProps) {
  const range = maximum === minimum ? 0 : ((value - minimum) / (maximum - minimum)) * 100;
  const boundedRange = Math.min(100, Math.max(0, range));

  return (
    <label
      className="hangar-dial"
      style={{ "--hangar-range": `${boundedRange}%` } as CSSProperties}
    >
      <span>{displayLabel}</span>
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
  const { motion, direction, setMotion, setDirection } = useInstrumentMotion();
  const motionLabel = motion === "rotate" ? "Pause rotation" : "Resume rotation";
  const directionLabel = direction === "clockwise" ? "Use counter-clockwise" : "Use clockwise";
  const directionTitle = motion === "still"
    ? `${directionLabel} when rotation resumes`
    : direction === "clockwise" ? "Rotate counter-clockwise" : "Rotate clockwise";
  const motionStatus = `${motion === "rotate" ? "Rotating" : "Paused"} · ${direction === "clockwise" ? "CW" : "CCW"}`;
  const catalogStatus = instrumentHull.catalog
    ? `${instrumentHull.catalog.hulls.length.toLocaleString()} installed`
    : instrumentHull.catalogLoaded ? "Included ships" : "Finding installed ships…";

  return (
    <div className="hangar-page">
      <section className="hangar-display hangar-display--minimal" aria-label="Selected display ship">
        <div className="hangar-stage hangar-stage--minimal">
          <div className="hangar-stage__instrument">
            <FlightInstrument hull={instrumentHull.selected} variant="stage" />
          </div>

          <div className="hangar-identity">
            <HangarHullChooser
              hulls={instrumentHull.hulls}
              selected={instrumentHull.selected}
              onChoose={instrumentHull.choose}
              catalogStatus={catalogStatus}
            />
          </div>
        </div>

        <div className="hangar-dock hangar-dock--catalog">
          <div className="hangar-console-rail">
            <span className="hangar-console-heading">Wireframe appearance</span>
            <div
              className="hangar-motion-controls"
              role="group"
              aria-label="Display motion and appearance"
              data-motion={motion}
              data-direction={direction}
            >
              <div className="hangar-motion-status" aria-live="polite">
                <span>Motion</span>
                <span className="hangar-motion-status__state">{motionStatus}</span>
              </div>
              <button
                className="icon-button icon-button--small hangar-motion-action"
                type="button"
                aria-label={motionLabel}
                title={motion === "rotate" ? "Pause decorative hull rotation" : "Resume decorative hull rotation"}
                onClick={() => setMotion(motion === "rotate" ? "still" : "rotate")}
              >
                {motion === "rotate" ? <PauseIcon /> : <PlayIcon />}
              </button>
              <button
                className="icon-button icon-button--small hangar-direction-action"
                type="button"
                aria-label={directionLabel}
                title={directionTitle}
                onClick={() => setDirection(direction === "clockwise" ? "counter-clockwise" : "clockwise")}
              >
                {direction === "clockwise" ? <RotateCounterClockwiseIcon /> : <RotateClockwiseIcon />}
              </button>
              <button
                className="button button--quiet button--compact hangar-reset-action"
                type="button"
                aria-label="Reset appearance"
                title="Reset appearance"
                disabled={!instrumentHull.customized}
                onClick={instrumentHull.resetCustomization}
              >
                <RefreshIcon />
                <span>Reset</span>
              </button>
            </div>
          </div>

          <div className="hangar-dials" role="group" aria-label="Wireframe appearance">
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
              displayLabel="Inner smooth"
              value={instrumentHull.tuning.innerSmooth}
              valueText={instrumentHull.tuning.innerSmooth === 0 ? "None" : instrumentHull.tuning.innerSmooth.toFixed(2)}
              minimum={0}
              maximum={0.9}
              step={0.02}
              onChange={(value) => instrumentHull.customize({ innerSmooth: value })}
            />
            <HangarDial
              label="Interior detail"
              displayLabel="Inner detail"
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
        </div>
      </section>
    </div>
  );
}
