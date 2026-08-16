import { useEffect, useMemo, useState } from "react";
import { getWireframeHulls } from "./bridge";
import { INSTRUMENT_HULL_STORAGE_KEY } from "./desktopStorage";
import type { WireframeHull, WireframeHullCatalog } from "./types";

export const ORIGINAL_HULL_ID = "preflight-courier";
export const DEFAULT_HULL_ID = "odyssey";
const CATALOG_IDLE_DELAY_MS = 160;

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

interface CatalogState {
  game: string;
  catalog: WireframeHullCatalog | null;
}

function savedHullId(): string | null {
  try {
    return window.localStorage.getItem(INSTRUMENT_HULL_STORAGE_KEY);
  } catch {
    return null;
  }
}

/** Loads installation-owned cosmetic geometry only while a page that can use it is visible. */
export function useInstrumentHull(game: string | undefined, enabled: boolean) {
  const [catalogState, setCatalogState] = useState<CatalogState | null>(null);
  const [selectedId, setSelectedId] = useState(savedHullId);
  const catalogLoaded = catalogState !== null && catalogState.game === game;
  const catalog = catalogLoaded ? catalogState.catalog : null;

  useEffect(() => {
    if (!game || !enabled || catalogState?.game === game) return;
    let current = true;
    // The courier is already bundled, so the first page frame never needs to wait behind hundreds
    // of optional hull files. Start that cosmetic scan just after the page transition settles.
    const timer = window.setTimeout(() => {
      void getWireframeHulls(game)
        .then((next) => {
          if (current) setCatalogState({ game, catalog: next });
        })
        .catch(() => {
          if (current) {
            // A failed cosmetic lookup is still an attempted lookup for this installation. Cache
            // the fallback so page navigation cannot turn an unreadable catalog into a retry loop.
            setCatalogState({ game, catalog: null });
          }
        });
    }, CATALOG_IDLE_DELAY_MS);
    return () => {
      current = false;
      window.clearTimeout(timer);
    };
  }, [catalogState?.game, enabled, game]);

  const hulls = useMemo(
    () => [ORIGINAL_HULL, ...(catalog?.hulls ?? []).filter((hull) => hull.id !== ORIGINAL_HULL_ID)],
    [catalog],
  );
  const selected = hulls.find((hull) => hull.id === selectedId)
    ?? hulls.find((hull) => hull.id === DEFAULT_HULL_ID)
    ?? hulls.find((hull) => hull.featured && hull.id !== ORIGINAL_HULL_ID)
    ?? ORIGINAL_HULL;
  const choose = (id: string) => {
    const next = hulls.some((hull) => hull.id === id) ? id : ORIGINAL_HULL_ID;
    try {
      window.localStorage.setItem(INSTRUMENT_HULL_STORAGE_KEY, next);
    } catch {
      // A denied WebView store should not make a cosmetic preference unusable for this session.
    }
    setSelectedId(next);
  };

  return { catalog, catalogLoaded, hulls, selected, selectedId: selected.id, choose };
}
