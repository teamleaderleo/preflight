import { useEffect, useMemo, useState } from "react";
import { getWireframeHulls } from "./bridge";
import { BUNDLED_DEFAULT_HULL, BUNDLED_WIREFRAME_HULLS } from "./bundledWireframeHulls";
import {
  INSTRUMENT_HULL_ROSTER_STORAGE_KEY,
  INSTRUMENT_HULL_STORAGE_KEY,
  INSTRUMENT_HULL_TUNING_STORAGE_KEY,
} from "./desktopStorage";
import type { WireframeHullCatalog, WireframeTuning } from "./types";
import { DEFAULT_WIREFRAME_TUNING } from "./wireframeHullGeometry";

export const DEFAULT_HULL_ID = "odyssey";
export const FEATURED_HULL_IDS = ["odyssey", "onslaught", "conquest", "paragon", "astral", "hammerhead"] as const;
const LEGACY_COURIER_ID = "preflight-courier";
const CATALOG_IDLE_DELAY_MS = 160;

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
    // for those two new dials take the default. The three fields in the original record remain
    // required so a truncated or partially written record does not silently become valid.
    if (next === undefined) {
      if (key === "innerDetail" || key === "innerSmooth") continue;
      return null;
    }
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

function savedRosters(): Record<string, string[]> {
  try {
    const decoded: unknown = JSON.parse(window.localStorage.getItem(INSTRUMENT_HULL_ROSTER_STORAGE_KEY) ?? "{}");
    if (!decoded || typeof decoded !== "object" || Array.isArray(decoded)) return {};
    const entries = Object.entries(decoded).slice(0, 64).flatMap(([game, value]) => {
      if (game.length > 32_768 || !Array.isArray(value)) return [];
      const ids = [...new Set(value.filter((id): id is string => typeof id === "string" && id.length <= 1_024))]
        .slice(0, 64);
      return ids.length > 0 ? [[game, ids] as const] : [];
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
  const [rosters, setRosters] = useState(savedRosters);
  const catalogLoaded = catalogState !== null && catalogState.game === game;
  const catalog = catalogLoaded ? catalogState.catalog : null;

  useEffect(() => {
    if (!game || !enabled || catalogState?.game === game) return;
    let current = true;
    // Six featured hulls are already bundled, so the first page frame never waits behind hundreds
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

  const catalogHulls = useMemo(
    () => {
      const local = (catalog?.hulls ?? []).filter((hull) => hull.id !== LEGACY_COURIER_ID);
      const featured = FEATURED_HULL_IDS.flatMap((id) => {
        const hull = local.find((candidate) => candidate.id === id)
          ?? BUNDLED_WIREFRAME_HULLS.hulls.find((candidate) => candidate.id === id);
        return hull ? [hull] : [];
      });
      const featuredIds = new Set(featured.map((hull) => hull.id));
      return [...featured, ...local.filter((hull) => !featuredIds.has(hull.id))];
    },
    [catalog],
  );
  const rosterKey = game ?? "bundled";
  const rosterIds = rosters[rosterKey] ?? [
    ...FEATURED_HULL_IDS,
    ...(selectedId && !FEATURED_HULL_IDS.includes(selectedId as typeof FEATURED_HULL_IDS[number])
      ? [selectedId]
      : []),
  ];
  const availableRosterHulls = rosterIds.flatMap((id) => {
    const hull = catalogHulls.find((candidate) => candidate.id === id);
    return hull ? [hull] : [];
  });
  const hulls = availableRosterHulls.length > 0
    ? availableRosterHulls
    : FEATURED_HULL_IDS.flatMap((id) => {
      const hull = catalogHulls.find((candidate) => candidate.id === id);
      return hull ? [hull] : [];
    });
  const selectedBase = hulls.find((hull) => hull.id === selectedId)
    ?? hulls.find((hull) => hull.id === DEFAULT_HULL_ID)
    ?? hulls.find((hull) => hull.featured)
    ?? BUNDLED_DEFAULT_HULL;
  const tuningKey = game ? `${game}::${selectedBase.id}` : selectedBase.id;
  const selected = useMemo(() => {
    const tuning = tunings[tuningKey];
    return tuning ? { ...selectedBase, tuning } : selectedBase;
  }, [selectedBase, tuningKey, tunings]);
  const choose = (id: string) => {
    const next = catalogHulls.some((hull) => hull.id === id) ? id : DEFAULT_HULL_ID;
    setRosters((current) => {
      const currentIds = current[rosterKey] ?? rosterIds;
      return currentIds.includes(next)
        ? current
        : { ...current, [rosterKey]: [...currentIds, next].slice(0, 64) };
    });
    try {
      window.localStorage.setItem(INSTRUMENT_HULL_STORAGE_KEY, next);
    } catch {
      // A denied WebView store should not make a cosmetic preference unusable for this session.
    }
    setSelectedId(next);
  };

  const remove = (id: string) => {
    if (!rosterIds.includes(id) || hulls.length <= 1) return;
    const nextIds = rosterIds.filter((candidate) => candidate !== id);
    setRosters((current) => ({ ...current, [rosterKey]: nextIds }));
    if (selectedBase.id === id) choose(nextIds[0] ?? DEFAULT_HULL_ID);
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

  useEffect(() => {
    const timer = window.setTimeout(() => {
      try {
        window.localStorage.setItem(INSTRUMENT_HULL_ROSTER_STORAGE_KEY, JSON.stringify(rosters));
      } catch {
        // A denied WebView store only makes roster edits session-local.
      }
    }, 180);
    return () => window.clearTimeout(timer);
  }, [rosters]);

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
    catalogHulls,
    hulls,
    selected,
    selectedId: selected.id,
    tuning: selected.tuning ?? DEFAULT_WIREFRAME_TUNING,
    customized: Boolean(selected.tuning),
    choose,
    remove,
    customize,
    resetCustomization,
  };
}
