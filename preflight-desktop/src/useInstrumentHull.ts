import { useEffect, useMemo, useState } from "react";
import { getWireframeHulls } from "./bridge";
import { INSTRUMENT_HULL_STORAGE_KEY } from "./desktopStorage";
import type { WireframeHull, WireframeHullCatalog } from "./types";

export const ORIGINAL_HULL_ID = "preflight-courier";

/** The bundled fallback is original Preflight artwork and exists before Starsector is selected. */
export const ORIGINAL_HULL: WireframeHull = {
  id: ORIGINAL_HULL_ID,
  name: "Preflight courier",
  hullSize: "UTILITY",
  style: "PREFLIGHT",
  featured: true,
  bounds: [
    { x: 100, y: 0 },
    { x: 72, y: 18 },
    { x: 42, y: 28 },
    { x: 20, y: 48 },
    { x: -10, y: 52 },
    { x: -24, y: 35 },
    { x: -70, y: 38 },
    { x: -58, y: 13 },
    { x: -82, y: 0 },
    { x: -58, y: -13 },
    { x: -70, y: -38 },
    { x: -24, y: -35 },
    { x: -10, y: -52 },
    { x: 20, y: -48 },
    { x: 42, y: -28 },
    { x: 72, y: -18 },
  ],
  engines: [
    { x: -68, y: 24, angle: 180, width: 12, length: 24 },
    { x: -68, y: -24, angle: 180, width: 12, length: 24 },
  ],
  mounts: [],
};

function savedHullId(): string {
  try {
    return window.localStorage.getItem(INSTRUMENT_HULL_STORAGE_KEY) || ORIGINAL_HULL_ID;
  } catch {
    return ORIGINAL_HULL_ID;
  }
}

export function useInstrumentHull(game?: string) {
  const [catalog, setCatalog] = useState<WireframeHullCatalog | null>(null);
  const [selectedId, setSelectedId] = useState(savedHullId);

  useEffect(() => {
    let current = true;
    setCatalog(null);
    if (!game) return () => { current = false; };
    void getWireframeHulls(game)
      .then((next) => {
        if (current) setCatalog(next);
      })
      .catch(() => {
        // Local hulls are cosmetic. Keep the same truthful courier fallback used before a catalog
        // exists instead of turning a read failure into a successful-looking empty catalog.
      });
    return () => { current = false; };
  }, [game]);

  const hulls = useMemo(
    () => [ORIGINAL_HULL, ...(catalog?.hulls ?? []).filter((hull) => hull.id !== ORIGINAL_HULL_ID)],
    [catalog],
  );
  const selected = hulls.find((hull) => hull.id === selectedId) ?? ORIGINAL_HULL;
  const choose = (id: string) => {
    const next = hulls.some((hull) => hull.id === id) ? id : ORIGINAL_HULL_ID;
    try {
      window.localStorage.setItem(INSTRUMENT_HULL_STORAGE_KEY, next);
    } catch {
      // A denied WebView store should not make a cosmetic preference unusable for this session.
    }
    setSelectedId(next);
  };

  return { catalog, hulls, selected, selectedId: selected.id, choose };
}
