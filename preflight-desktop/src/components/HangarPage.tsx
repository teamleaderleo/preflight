import { useEffect, useId, useLayoutEffect, useMemo, useRef, useState, type CSSProperties, type KeyboardEvent } from "react";
import {
  OrbitClockwiseIcon,
  OrbitCounterClockwiseIcon,
  PauseIcon,
  PlayIcon,
  RefreshIcon,
} from "../icons";
import type { useInstrumentHull } from "../useInstrumentHull";
import type { WireframeHull } from "../types";
import { useInstrumentMotion } from "../useInstrumentMotion";
import {
  DEFAULT_INSTRUMENT_VIEW,
  MAX_INSTRUMENT_PITCH,
  MAX_INSTRUMENT_ZOOM,
  MIN_INSTRUMENT_PITCH,
  MIN_INSTRUMENT_ZOOM,
  restoreInstrumentView,
  useInstrumentView,
} from "../useInstrumentView";
import { FlightInstrument } from "./FlightInstrument";

type InstrumentHullState = ReturnType<typeof useInstrumentHull>;

const HULL_POPUP_GAP = 9;

type HullPopupDirection = "up" | "down";

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

function keepActiveHullVisible(list: HTMLDivElement, activeOption: HTMLElement) {
  const optionTop = activeOption.offsetTop;
  const optionBottom = optionTop + activeOption.offsetHeight;
  const visibleTop = list.scrollTop;
  const visibleBottom = visibleTop + list.clientHeight;

  if (optionTop < visibleTop) {
    list.scrollTop = optionTop;
  } else if (optionBottom > visibleBottom) {
    list.scrollTop = optionBottom - list.clientHeight;
  }
}

function hullPopupHeightCap(): number {
  const viewportHeight = window.innerHeight || document.documentElement.clientHeight || 0;
  if (viewportHeight <= 0) return 250;
  return viewportHeight <= 600
    ? Math.min(196, viewportHeight * 0.35)
    : Math.min(250, viewportHeight * 0.48);
}

interface HangarHullChooserProps {
  catalogHulls: WireframeHull[];
  catalogLoaded: boolean;
  catalogAvailable: boolean;
  rosterIds: Set<string>;
  selected: WireframeHull;
  onChoose: (id: string) => void;
  onRemove: (id: string) => void;
  onReloadCatalog: () => void;
  canRemove: boolean;
}

function HangarHullChooser({ catalogHulls, catalogLoaded, catalogAvailable, rosterIds, selected, onChoose, onRemove, onReloadCatalog, canRemove }: HangarHullChooserProps) {
  const listId = useId();
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const [query, setQuery] = useState(selected.name);
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const [popupDirection, setPopupDirection] = useState<HullPopupDirection>("up");
  const [popupMaxHeight, setPopupMaxHeight] = useState<number | null>(null);
  const [removeArmed, setRemoveArmed] = useState(false);
  const [adding, setAdding] = useState(false);

  useEffect(() => {
    setQuery(selected.name);
    setActiveIndex(0);
    setRemoveArmed(false);
    setAdding(false);
  }, [selected.id, selected.name]);

  const eligibleHulls = useMemo(
    () => adding
      ? catalogHulls.filter((hull) => !rosterIds.has(hull.id))
      : catalogHulls,
    [adding, catalogHulls, rosterIds],
  );

  const results = useMemo(() => {
    const exact = findExactHull(eligibleHulls, query);
    if (exact?.id === selected.id) {
      return [selected, ...eligibleHulls.filter((hull) => hull.id !== selected.id)];
    }

    return query.trim()
      ? eligibleHulls.filter((hull) => hullMatches(hull, query))
      : eligibleHulls;
  }, [eligibleHulls, query, selected]);

  const activeHull = results[activeIndex] ?? results[0];

  useLayoutEffect(() => {
    if (!open) return;
    const input = inputRef.current;
    const list = listRef.current;
    const workspace = input?.closest<HTMLElement>(".page-viewport");
    if (!input || !list || !workspace) return;

    const placePopup = () => {
      const inputRect = input.getBoundingClientRect();
      const anchorRect = input.closest<HTMLElement>(".hangar-hull-combobox")?.getBoundingClientRect() ?? inputRect;
      const workspaceRect = workspace.getBoundingClientRect();
      const listRect = list.getBoundingClientRect();
      const desiredHeight = Math.min(
        list.scrollHeight > 0 ? list.scrollHeight : listRect.height,
        hullPopupHeightCap(),
      );
      const availableAbove = Math.max(0, anchorRect.top - workspaceRect.top - HULL_POPUP_GAP);
      const availableBelow = Math.max(0, workspaceRect.bottom - anchorRect.bottom - HULL_POPUP_GAP);
      const nextDirection: HullPopupDirection = availableAbove >= desiredHeight
        ? "up"
        : availableBelow >= desiredHeight
          ? "down"
          : availableBelow > availableAbove
            ? "down"
            : "up";
      const availableHeight = nextDirection === "up" ? availableAbove : availableBelow;
      const nextMaxHeight = Math.max(0, Math.min(desiredHeight, availableHeight));

      setPopupDirection((current) => current === nextDirection ? current : nextDirection);
      setPopupMaxHeight((current) => current === nextMaxHeight ? current : nextMaxHeight);
    };

    placePopup();
    workspace.addEventListener("scroll", placePopup, { passive: true });
    window.addEventListener("resize", placePopup);
    return () => {
      workspace.removeEventListener("scroll", placePopup);
      window.removeEventListener("resize", placePopup);
    };
  }, [open, results.length]);

  useEffect(() => {
    if (!open) return;
    const list = listRef.current;
    const activeOption = list?.querySelector<HTMLElement>('[data-active="true"]');
    if (list && activeOption) {
      keepActiveHullVisible(list, activeOption);
    }
  }, [activeIndex, open, results]);

  const choose = (hull: WireframeHull) => {
    if (hull.id !== selected.id) {
      onChoose(hull.id);
    }
    setQuery(hull.name);
    setOpen(false);
    setActiveIndex(0);
    setAdding(false);
  };

  const restoreSelected = () => {
    setQuery(selected.name);
    setOpen(false);
    setActiveIndex(0);
    setAdding(false);
  };

  const beginAdding = () => {
    if (catalogLoaded && !catalogAvailable) onReloadCatalog();
    setQuery("");
    setAdding(true);
    setOpen(true);
    setActiveIndex(0);
    inputRef.current?.focus();
    inputRef.current?.select();
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
      const exact = findExactHull(eligibleHulls, query);
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
  const popupStyle = popupMaxHeight === null ? undefined : {
    maxHeight: `${popupMaxHeight}px`,
    top: popupDirection === "down" ? `calc(100% + ${HULL_POPUP_GAP}px)` : "auto",
    bottom: popupDirection === "up" ? `calc(100% + ${HULL_POPUP_GAP}px)` : "auto",
  };

  return (
    <div
      className="hangar-hull-combobox"
      data-open={open ? "true" : "false"}
      data-placement={popupDirection}
    >
      <input
        ref={inputRef}
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
        placeholder="Search ships"
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
          const exact = findExactHull(eligibleHulls, query);
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
          aria-label={adding ? "Ships to add" : "Display ships"}
          style={popupStyle}
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
              <span className="hangar-hull-combobox__meta">
                {hullSizeLabel(hull.hullSize)} · {rosterIds.has(hull.id) ? "Home" : "Add to Home"}
              </span>
            </button>
          )) : !catalogLoaded ? (
            <div className="hangar-hull-combobox__empty">Loading ships…</div>
          ) : adding && !query.trim() ? (
            <div className="hangar-hull-combobox__empty">All installed ships are already on Home</div>
          ) : (
            <div className="hangar-hull-combobox__empty">No hull matches “{query}”</div>
          )}
        </div>
      ) : null}

      <div className="hangar-identity__meta">
        <span className="hangar-identity__size">{hullSizeLabel(selected.hullSize)}</span>
        <button type="button" aria-label="Add a display ship" onClick={beginAdding}>
          + Add ship
        </button>
        {canRemove ? removeArmed ? (
          <span className="hangar-roster-actions">
            <button
              type="button"
              aria-label={`Remove ${selected.name} from Home ships`}
              onClick={() => onRemove(selected.id)}
            >
              Remove
            </button>
            <button type="button" aria-label="Keep ship on Home" onClick={() => setRemoveArmed(false)}>
              Keep
            </button>
          </span>
        ) : (
          <button
            type="button"
            aria-label={`Remove ${selected.name} from Home ships`}
            title="Remove from Home"
            onClick={() => setRemoveArmed(true)}
          >
            Remove
          </button>
        ) : null}
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
  const { motion, direction, setDirection, setMotion } = useInstrumentMotion();
  const instrumentView = useInstrumentView();
  const rosterIds = useMemo(() => new Set(instrumentHull.hulls.map((hull) => hull.id)), [instrumentHull.hulls]);
  const detailMaximum = 0.06;
  const detailValue = (tolerance: number) => Number((detailMaximum - tolerance).toFixed(3));
  const detailText = (value: number) => `${Math.round(value / detailMaximum * 100)}%`;
  const detailTolerance = (value: number) => Number((detailMaximum - value).toFixed(3));
  const viewCustomized = Math.abs(instrumentView.pitch - DEFAULT_INSTRUMENT_VIEW.pitch) > 0.001
    || Math.abs(instrumentView.zoom - DEFAULT_INSTRUMENT_VIEW.zoom) > 0.001;
  const resetHangar = () => {
    instrumentHull.resetCustomization();
    restoreInstrumentView();
  };

  return (
    <div className="hangar-page">
      <section className="hangar-display hangar-display--minimal" aria-label="Selected display ship">
        <div className="hangar-stage hangar-stage--minimal">
          <div className="hangar-stage__instrument">
            <FlightInstrument hull={instrumentHull.selected} variant="stage" interactive framing={1.16} />
          </div>

          <div
            className="hangar-stage-controls"
            role="group"
            aria-label="Ship rotation"
            data-motion={motion}
            data-direction={direction}
          >
            <button
              className="hangar-stage-action"
              type="button"
              aria-label="Reverse rotation"
              title={direction === "clockwise" ? "Rotate counter-clockwise" : "Rotate clockwise"}
              onClick={() => setDirection(direction === "clockwise" ? "counter-clockwise" : "clockwise")}
            >
              {direction === "clockwise" ? <OrbitClockwiseIcon /> : <OrbitCounterClockwiseIcon />}
            </button>
            <button
              className="hangar-stage-action"
              type="button"
              aria-label={motion === "rotate" ? "Pause ship rotation" : "Resume ship rotation"}
              title={motion === "rotate" ? "Pause rotation" : "Resume rotation"}
              aria-pressed={motion === "still"}
              onClick={() => setMotion(motion === "rotate" ? "still" : "rotate")}
            >
              {motion === "rotate" ? <PauseIcon /> : <PlayIcon />}
            </button>
            <button
              className="hangar-stage-action hangar-reset-action"
              type="button"
              aria-label="Reset ship appearance and view"
              title="Reset ship appearance and view"
              disabled={!instrumentHull.customized && !viewCustomized}
              onClick={resetHangar}
            >
              <RefreshIcon />
            </button>
          </div>

          <div className="hangar-identity">
            <HangarHullChooser
              catalogHulls={instrumentHull.catalogHulls}
              catalogLoaded={instrumentHull.catalogLoaded}
              catalogAvailable={instrumentHull.catalog !== null}
              rosterIds={rosterIds}
              selected={instrumentHull.selected}
              onChoose={instrumentHull.choose}
              onRemove={instrumentHull.remove}
              onReloadCatalog={instrumentHull.reloadCatalog}
              canRemove={instrumentHull.hulls.length > 1}
            />
          </div>
        </div>

        <div className="hangar-dock hangar-dock--catalog">
          <div className="hangar-control-groups" role="group" aria-label="Wireframe appearance">
            <fieldset className="hangar-control-group">
              <legend>Detail</legend>
              <HangarDial
                label="Outline detail"
                displayLabel="Outline"
                value={detailValue(instrumentHull.tuning.outerDetail)}
                valueText={detailText(detailValue(instrumentHull.tuning.outerDetail))}
                minimum={0}
                maximum={detailMaximum}
                step={0.001}
                onChange={(value) => instrumentHull.customize({ outerDetail: detailTolerance(value) })}
              />
              <HangarDial
                label="Interior detail"
                displayLabel="Interior"
                value={detailValue(instrumentHull.tuning.innerDetail)}
                valueText={detailText(detailValue(instrumentHull.tuning.innerDetail))}
                minimum={0}
                maximum={detailMaximum}
                step={0.001}
                onChange={(value) => instrumentHull.customize({ innerDetail: detailTolerance(value) })}
              />
            </fieldset>
            <fieldset className="hangar-control-group">
              <legend>Smoothing</legend>
              <HangarDial
                label="Outline smoothing"
                displayLabel="Outline"
                value={instrumentHull.tuning.outerSmooth}
                valueText={instrumentHull.tuning.outerSmooth === 0 ? "None" : instrumentHull.tuning.outerSmooth.toFixed(2)}
                minimum={0}
                maximum={0.9}
                step={0.02}
                onChange={(value) => instrumentHull.customize({ outerSmooth: value })}
              />
              <HangarDial
                label="Interior smoothing"
                displayLabel="Interior"
                value={instrumentHull.tuning.innerSmooth}
                valueText={instrumentHull.tuning.innerSmooth === 0 ? "None" : instrumentHull.tuning.innerSmooth.toFixed(2)}
                minimum={0}
                maximum={0.9}
                step={0.02}
                onChange={(value) => instrumentHull.customize({ innerSmooth: value })}
              />
            </fieldset>
            <fieldset className="hangar-control-group hangar-control-group--view">
              <legend>Form and view</legend>
              <HangarDial
                label="Wireframe height"
                displayLabel="Height"
                value={instrumentHull.tuning.height}
                valueText={`${instrumentHull.tuning.height.toFixed(2)}×`}
                minimum={0.2}
                maximum={2.2}
                step={0.05}
                onChange={(value) => instrumentHull.customize({ height: value })}
              />
              <HangarDial
                label="View angle"
                displayLabel="Angle"
                value={instrumentView.pitch}
                valueText={`${Math.round((instrumentView.pitch - MIN_INSTRUMENT_PITCH) / (MAX_INSTRUMENT_PITCH - MIN_INSTRUMENT_PITCH) * 100)}%`}
                minimum={MIN_INSTRUMENT_PITCH}
                maximum={MAX_INSTRUMENT_PITCH}
                step={0.02}
                onChange={(pitch) => instrumentView.setView({ ...instrumentView, pitch })}
              />
              <HangarDial
                label="Ship zoom"
                displayLabel="Zoom"
                value={instrumentView.zoom}
                valueText={`${Math.round(instrumentView.zoom * 100)}%`}
                minimum={MIN_INSTRUMENT_ZOOM}
                maximum={MAX_INSTRUMENT_ZOOM}
                step={0.05}
                onChange={(zoom) => instrumentView.setView({ ...instrumentView, zoom })}
              />
            </fieldset>
          </div>
        </div>
      </section>
    </div>
  );
}
