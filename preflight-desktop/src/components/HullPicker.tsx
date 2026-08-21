import { useMemo, useState } from "react";
import type { WireframeHull } from "../types";

/**
 * Installed and modded catalogs are too large for one native dropdown. Keep the visible list
 * bounded and make filtering the normal way to reach deep entries.
 */
interface HullPickerProps {
  hulls: WireframeHull[];
  selectedId: string;
  onChoose: (id: string) => void;
}

/** Enough to scroll through and recognise something; past this, filtering is the faster route. */
const VISIBLE_LIMIT = 60;

function matches(hull: WireframeHull, query: string): boolean {
  if (!query) return true;
  const needle = query.toLowerCase();
  return hull.name.toLowerCase().includes(needle) || hull.id.toLowerCase().includes(needle);
}

function boundedSelection(found: WireframeHull[], selectedId: string): WireframeHull[] {
  const first = found.slice(0, VISIBLE_LIMIT);
  if (first.some((hull) => hull.id === selectedId)) return first;

  const selected = found.find((hull) => hull.id === selectedId);
  if (!selected || first.length < VISIBLE_LIMIT) return first;

  // Keep the cap hard and deterministic: the selected matching row owns the final visible slot.
  return [...first.slice(0, VISIBLE_LIMIT - 1), selected];
}

function hullSizeLabel(hullSize: string): string {
  return hullSize === "CAPITAL_SHIP" ? "capital" : hullSize.replaceAll("_", " ").toLowerCase();
}

export function HullPicker({ hulls, selectedId, onChoose }: HullPickerProps) {
  const [query, setQuery] = useState("");
  const found = useMemo(() => hulls.filter((hull) => matches(hull, query)), [hulls, query]);
  const shown = useMemo(() => boundedSelection(found, selectedId), [found, selectedId]);
  const hidden = found.length - shown.length;

  return (
    <div className="hull-picker">
      <div className="hull-picker__search">
        <input
          type="search"
          aria-label="Filter installed hulls"
          placeholder="Filter hulls"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
        <small aria-live="polite">
          {query
            ? `${found.length.toLocaleString()} of ${hulls.length.toLocaleString()} additional hulls`
            : `${hulls.length.toLocaleString()} additional hulls`}
        </small>
      </div>
      {shown.length > 0 ? (
        <div className="hull-picker__list" role="group" aria-label="Installed hulls">
          {shown.map((hull) => (
            <button
              key={hull.id}
              className={hull.id === selectedId ? "hull-picker__hull hull-picker__hull--selected" : "hull-picker__hull"}
              type="button"
              aria-pressed={hull.id === selectedId}
              onClick={() => onChoose(hull.id)}
            >
              <span>{hull.name}</span>
              <small>{hullSizeLabel(hull.hullSize)}</small>
            </button>
          ))}
        </div>
      ) : (
        <p className="hull-picker__empty">No hull matches “{query}”.</p>
      )}
      {hidden > 0 ? (
        <p className="hull-picker__more">{hidden.toLocaleString()} more — keep typing to narrow it.</p>
      ) : null}
    </div>
  );
}
