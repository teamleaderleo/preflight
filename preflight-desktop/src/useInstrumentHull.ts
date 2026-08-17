import { useEffect, useMemo, useState } from "react";
import { getWireframeHulls } from "./bridge";
import { INSTRUMENT_HULL_STORAGE_KEY, INSTRUMENT_HULL_TUNING_STORAGE_KEY } from "./desktopStorage";
import type { WireframeHull, WireframeHullCatalog, WireframeTuning } from "./types";
import { DEFAULT_WIREFRAME_TUNING } from "./wireframeHullGeometry";

export const ORIGINAL_HULL_ID = "preflight-courier";
export const DEFAULT_HULL_ID = "odyssey";
export const FEATURED_HULL_IDS = ["odyssey", "onslaught", "conquest", "paragon", "astral", "hammerhead"] as const;
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

const TUNING_LIMITS: Record<keyof WireframeTuning, readonly [number, number]> = {
  outerDetail: [0, 0.06],
  outerSmooth: [0, 0.9],
  innerDetail: [0, 0.06],
  innerSmooth: [0, 0.9],
  height: [0.2, 2.2],
};

export function validateWireframeTuning(value: unknown): WireframeTuning | null {
  if (!value || typeof value !== "object") return null;
  const candidate = value as Record<string, unknown>;
  const tuning = { ...DEFAULT_WIREFRAME_TUNING } as WireframeTuning;
  for (const key of Object.keys(TUNING_LIMITS) as Array<keyof WireframeTuning>) {
    const next = candidate[key];
    // A tuning saved before the inner dials existed is still that person's tuning. Missing keys
    // take the default; only a key that is present and out of range rejects the whole record.
    if (next === undefined) continue;
    const [minimum, maximum] = TUNING_LIMITS[key];
    if (typeof next !== "number" || !Number.isFinite(next) || next < minimum || next > maximum) return null;
    tuning[key] = next;
  }
  return tuning;
}

function savedTunings(): Record<string, WireframeTuning> {
  try {
    const decoded: unknown = JSON.parse(window.localStorage.getItem(INSTRUMENT_HULL_TUNING_STORAGE_KEY) ?? "{}");
    if (!decoded || typeof decoded !== "object" || Array.isArray(decoded)) return {};
    const entries = Object.entries(decoded).slice(0, 256).flatMap(([id, value]) => {
      const tuning = id.length <= 1_024 ? validateWireframeTuning(value) : null;
      return tuning ? [[id, tuning] as const] : [];
    });
    return Object.fromEntries(entries);
  } catch {
    return {};
  }
}

/** Loads installation-owned cosmetic geometry only while a page that can use it is visible. */
export function useInstrumentHull(game: string | undefined, enabled: boolean) {
  const [catalogState, setCatalogState] = useState<CatalogState | null>(null);
  const [selectedId, setSelectedId] = useState(savedHullId);
  const [tunings, setTunings] = useState(savedTunings);
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
    () => {
      const local = (catalog?.hulls ?? []).filter((hull) => hull.id !== ORIGINAL_HULL_ID);
      const featured = FEATURED_HULL_IDS.flatMap((id) => {
        const hull = local.find((candidate) => candidate.id === id);
        return hull ? [hull] : [];
      });
      const featuredIds = new Set(featured.map((hull) => hull.id));
      return [...featured, ...local.filter((hull) => !featuredIds.has(hull.id)), ORIGINAL_HULL];
    },
    [catalog],
  );
  const selectedBase = hulls.find((hull) => hull.id === selectedId)
    ?? hulls.find((hull) => hull.id === DEFAULT_HULL_ID)
    ?? hulls.find((hull) => hull.featured && hull.id !== ORIGINAL_HULL_ID)
    ?? ORIGINAL_HULL;
  const tuningKey = game ? `${game}::${selectedBase.id}` : selectedBase.id;
  const selected = useMemo(() => {
    const tuning = tunings[tuningKey];
    return tuning ? { ...selectedBase, tuning } : selectedBase;
  }, [selectedBase, tuningKey, tunings]);
  const choose = (id: string) => {
    const next = hulls.some((hull) => hull.id === id) ? id : ORIGINAL_HULL_ID;
    try {
      window.localStorage.setItem(INSTRUMENT_HULL_STORAGE_KEY, next);
    } catch {
      // A denied WebView store should not make a cosmetic preference unusable for this session.
    }
    setSelectedId(next);
  };

  useEffect(() => {
    const timer = window.setTimeout(() => {
      try {
        window.localStorage.setItem(INSTRUMENT_HULL_TUNING_STORAGE_KEY, JSON.stringify(tunings));
      } catch {
        // Cosmetic editing remains usable for this session when WebView storage is denied.
      }
    }, 180);
    return () => window.clearTimeout(timer);
  }, [tunings]);

  const customize = (patch: Partial<WireframeTuning>) => {
    const next = validateWireframeTuning({
      ...DEFAULT_WIREFRAME_TUNING,
      ...tunings[tuningKey],
      ...patch,
    });
    if (next) setTunings((current) => ({ ...current, [tuningKey]: next }));
  };
  const resetCustomization = () => {
    setTunings((current) => {
      const next = { ...current };
      delete next[tuningKey];
      return next;
    });
  };

  return {
    catalog,
    catalogLoaded,
    hulls,
    selected,
    selectedId: selected.id,
    tuning: selected.tuning ?? DEFAULT_WIREFRAME_TUNING,
    customized: Boolean(selected.tuning),
    choose,
    customize,
    resetCustomization,
  };
}
