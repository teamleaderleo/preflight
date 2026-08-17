import { useMemo, useState } from "react";
import type { WireframeHull } from "../types";

/**
 * A dropdown is the wrong control for this list.
 *
 * The featured six are chips because there are six of them. Behind those sits every hull the
 * installation has, which is 200 on a stock copy and considerably more with mods, and a native
 * select gives that no filtering, no size beside the name, and a popup list nothing in the app can
 * style. Typing two letters is how anyone actually finds a hull in a list that long.
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

export function HullPicker({ hulls, selectedId, onChoose }: HullPickerProps) {
  const [query, setQuery] = useState("");
  const found = useMemo(() => hulls.filter((hull) => matches(hull, query)), [hulls, query]);
  const shown = useMemo(() => {
    if (found.length <= VISIBLE_LIMIT) return found;
    const selectedIndex = found.findIndex((hull) => hull.id === selectedId);
    if (selectedIndex >= VISIBLE_LIMIT) {
      return [...found.slice(0, VISIBLE_LIMIT - 1), found[selectedIndex]!];
    }
    return found.slice(0, VISIBLE_LIMIT);
  }, [found, selectedId]);
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
            ? `${found.length.toLocaleString()} of ${hulls.length.toLocaleString()}`
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
              <small>{hull.hullSize.replaceAll("_", " ").toLowerCase()}</small>
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

