import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, expect, test, vi } from "vitest";
import { getWireframeHulls } from "./bridge";
import {
  INSTRUMENT_HULL_ROSTER_STORAGE_KEY,
  INSTRUMENT_HULL_STORAGE_KEY,
  INSTRUMENT_HULL_TUNING_STORAGE_KEY,
} from "./desktopStorage";
import type { WireframeHullCatalog } from "./types";
import {
  DEFAULT_HULL_ID,
  FEATURED_HULL_IDS,
  useInstrumentHull,
  validateWireframeTuning,
} from "./useInstrumentHull";

vi.mock("./bridge", () => ({ getWireframeHulls: vi.fn() }));

function hull(id: string, name: string, featured = true): WireframeHullCatalog["hulls"][number] {
  return {
    id,
    name,
    hullSize: id === "hammerhead" ? "DESTROYER" : "CAPITAL_SHIP",
    style: "MIDLINE",
    featured,
    bounds: [{ x: 10, y: 0 }, { x: -5, y: 8 }, { x: -5, y: -8 }],
    engines: [],
    mounts: [],
  };
}

const catalog: WireframeHullCatalog = {
  format: "preflight-wireframe-hulls-v1",
  skipped: 0,
  hulls: [
    hull("odyssey", "Odyssey"),
    hull("onslaught", "Onslaught"),
    hull("conquest", "Conquest"),
    hull("paragon", "Paragon"),
    hull("astral", "Astral"),
    hull("hammerhead", "Hammerhead"),
  ],
};

beforeEach(() => {
  window.localStorage.clear();
  vi.mocked(getWireframeHulls).mockReset();
});

test("pre-discovery state uses the six included hulls without requesting local hulls", () => {
  const { result } = renderHook(() => useInstrumentHull(undefined, false));

  expect(getWireframeHulls).not.toHaveBeenCalled();
  expect(result.current.catalog).toBeNull();
  expect(result.current.catalogLoaded).toBe(false);
  expect(result.current.hulls.map((candidate) => candidate.id)).toEqual([
    "odyssey", "onslaught", "conquest", "paragon", "astral", "hammerhead",
  ]);
  expect(result.current.selectedId).toBe(DEFAULT_HULL_ID);
});

test("loads the current installation only when a hull UI is visible and reuses that result", async () => {
  vi.mocked(getWireframeHulls).mockResolvedValue(catalog);
  const { result, rerender } = renderHook(
    ({ game, enabled }) => useInstrumentHull(game, enabled),
    { initialProps: { game: "/game", enabled: false } },
  );

  expect(getWireframeHulls).not.toHaveBeenCalled();
  expect(result.current.catalog).toBeNull();
  expect(result.current.selectedId).toBe(DEFAULT_HULL_ID);

  rerender({ game: "/game", enabled: true });
  await waitFor(() => expect(result.current.catalog).toEqual(catalog));
  expect(getWireframeHulls).toHaveBeenCalledTimes(1);
  expect(result.current.hulls.map((candidate) => candidate.id)).toEqual([
    "odyssey", "onslaught", "conquest", "paragon", "astral", "hammerhead",
  ]);
  expect(result.current.selectedId).toBe(DEFAULT_HULL_ID);

  rerender({ game: "/game", enabled: false });
  rerender({ game: "/game", enabled: true });
  expect(getWireframeHulls).toHaveBeenCalledTimes(1);

  rerender({ game: "/other-game", enabled: false });
  expect(result.current.catalog).toBeNull();
  expect(result.current.selectedId).toBe(DEFAULT_HULL_ID);
  expect(getWireframeHulls).toHaveBeenCalledTimes(1);

  rerender({ game: "/other-game", enabled: true });
  await waitFor(() => expect(getWireframeHulls).toHaveBeenCalledWith("/other-game"));
  expect(getWireframeHulls).toHaveBeenCalledTimes(2);
});

test("restores a saved installed hull on Home without making every Home load scan the catalog", async () => {
  const modded = hull("uaf-solvernia", "Solvernia", false);
  window.localStorage.setItem(INSTRUMENT_HULL_STORAGE_KEY, modded.id);
  window.localStorage.setItem(INSTRUMENT_HULL_ROSTER_STORAGE_KEY, JSON.stringify({
    "/game": [...FEATURED_HULL_IDS, modded.id],
  }));
  vi.mocked(getWireframeHulls).mockResolvedValue({ ...catalog, hulls: [...catalog.hulls, modded] });

  const { result } = renderHook(() => useInstrumentHull("/game", false));

  await waitFor(() => expect(result.current.selectedId).toBe(modded.id));
  expect(getWireframeHulls).toHaveBeenCalledTimes(1);
  expect(result.current.hulls.some((candidate) => candidate.id === modded.id)).toBe(true);
});

test("keeps the six included hulls when the local catalog cannot be read", async () => {
  vi.mocked(getWireframeHulls).mockRejectedValue(new Error("unreadable hull directory"));
  const { result, rerender } = renderHook(
    ({ enabled }) => useInstrumentHull("/game", enabled),
    { initialProps: { enabled: true } },
  );

  await waitFor(() => expect(result.current.catalogLoaded).toBe(true));
  expect(getWireframeHulls).toHaveBeenCalledWith("/game");
  expect(result.current.catalog).toBeNull();
  expect(result.current.hulls).toHaveLength(6);
  expect(result.current.selectedId).toBe(DEFAULT_HULL_ID);

  rerender({ enabled: false });
  rerender({ enabled: true });
  expect(getWireframeHulls).toHaveBeenCalledTimes(1);
});

test("an explicit catalog retry recovers after a transient lookup failure", async () => {
  vi.mocked(getWireframeHulls)
    .mockRejectedValueOnce(new Error("installation was busy"))
    .mockResolvedValueOnce(catalog);
  const { result } = renderHook(() => useInstrumentHull("/game", true));

  await waitFor(() => expect(result.current.catalogLoaded).toBe(true));
  expect(result.current.catalog).toBeNull();

  act(() => result.current.reloadCatalog());
  expect(result.current.catalogLoading).toBe(true);
  await waitFor(() => expect(result.current.catalog).toEqual(catalog));
  expect(getWireframeHulls).toHaveBeenCalledTimes(2);
});

test("restores and persists an available local hull", async () => {
  window.localStorage.setItem(INSTRUMENT_HULL_STORAGE_KEY, "hammerhead");
  vi.mocked(getWireframeHulls).mockResolvedValue(catalog);
  const { result } = renderHook(() => useInstrumentHull("/game", true));

  await waitFor(() => expect(result.current.selectedId).toBe("hammerhead"));
  act(() => result.current.choose("onslaught"));
  expect(result.current.selectedId).toBe("onslaught");
  expect(window.localStorage.getItem(INSTRUMENT_HULL_STORAGE_KEY)).toBe("onslaught");
});

test("adds installed hulls to a small saved roster and removes them again", async () => {
  const modded = hull("uaf-solvernia", "Solvernia", false);
  vi.mocked(getWireframeHulls).mockResolvedValue({ ...catalog, hulls: [...catalog.hulls, modded] });
  const { result } = renderHook(() => useInstrumentHull("/game", true));

  await waitFor(() => expect(result.current.catalogHulls.some((candidate) => candidate.id === modded.id)).toBe(true));
  expect(result.current.hulls.some((candidate) => candidate.id === modded.id)).toBe(false);

  act(() => result.current.choose(modded.id));
  expect(result.current.selectedId).toBe(modded.id);
  expect(result.current.hulls.some((candidate) => candidate.id === modded.id)).toBe(true);
  await waitFor(() => expect(JSON.parse(window.localStorage.getItem(INSTRUMENT_HULL_ROSTER_STORAGE_KEY) ?? "{}")["/game"])
    .toContain(modded.id));

  act(() => result.current.remove(modded.id));
  expect(result.current.hulls.some((candidate) => candidate.id === modded.id)).toBe(false);
  expect(result.current.selectedId).toBe(DEFAULT_HULL_ID);
});

test("drops a legacy courier preference after the local catalog loads", async () => {
  window.localStorage.setItem(INSTRUMENT_HULL_STORAGE_KEY, "preflight-courier");
  vi.mocked(getWireframeHulls).mockResolvedValue(catalog);
  const { result } = renderHook(() => useInstrumentHull("/game", true));

  await waitFor(() => expect(result.current.catalog).toEqual(catalog));
  expect(result.current.selectedId).toBe(DEFAULT_HULL_ID);
});

test("falls back to Odyssey when a saved hull disappeared", async () => {
  window.localStorage.setItem(INSTRUMENT_HULL_STORAGE_KEY, "missing-mod-hull");
  vi.mocked(getWireframeHulls).mockResolvedValue(catalog);
  const { result } = renderHook(() => useInstrumentHull("/game", true));

  await waitFor(() => expect(result.current.catalog).toEqual(catalog));
  expect(result.current.selectedId).toBe(DEFAULT_HULL_ID);
  expect(window.localStorage.getItem(INSTRUMENT_HULL_STORAGE_KEY)).toBe(DEFAULT_HULL_ID);
});

test("repairs a roster whose modded hulls all disappeared", async () => {
  window.localStorage.setItem(INSTRUMENT_HULL_ROSTER_STORAGE_KEY, JSON.stringify({
    "/game": ["missing-one", "missing-two"],
  }));
  vi.mocked(getWireframeHulls).mockResolvedValue(catalog);
  const { result } = renderHook(() => useInstrumentHull("/game", true));

  await waitFor(() => expect(result.current.catalog).toEqual(catalog));
  expect(result.current.hulls.map((candidate) => candidate.id)).toEqual([...FEATURED_HULL_IDS]);
  expect(result.current.selectedId).toBe(DEFAULT_HULL_ID);
});

test("a legacy courier id from an installation never reappears", async () => {
  vi.mocked(getWireframeHulls).mockResolvedValue({
    ...catalog,
    hulls: [{ ...catalog.hulls[0], id: "preflight-courier" }, ...catalog.hulls],
  });
  const { result } = renderHook(() => useInstrumentHull("/game", true));

  await waitFor(() => expect(result.current.catalog).not.toBeNull());
  expect(result.current.hulls.map((candidate) => candidate.id)).toEqual([
    "odyssey", "onslaught", "conquest", "paragon", "astral", "hammerhead",
  ]);
});

test("persists bounded customization per installation and hull, then resets it", async () => {
  vi.mocked(getWireframeHulls).mockResolvedValue(catalog);
  const { result } = renderHook(() => useInstrumentHull("/game", true));

  await waitFor(() => expect(result.current.selectedId).toBe(DEFAULT_HULL_ID));
  expect(result.current.customized).toBe(false);
  act(() => result.current.customize({ height: 1.35, outerSmooth: 0.2 }));
  expect(result.current.customized).toBe(true);
  expect(result.current.tuning).toEqual(expect.objectContaining({ height: 1.35, outerSmooth: 0.2 }));
  await waitFor(() => expect(JSON.parse(window.localStorage.getItem(INSTRUMENT_HULL_TUNING_STORAGE_KEY) ?? "{}")["/game::odyssey"])
    .toEqual(expect.objectContaining({ height: 1.35, outerSmooth: 0.2 })));

  act(() => result.current.choose("onslaught"));
  expect(result.current.customized).toBe(false);
  act(() => result.current.choose(DEFAULT_HULL_ID));
  expect(result.current.tuning.height).toBe(1.35);

  act(() => result.current.resetCustomization());
  expect(result.current.customized).toBe(false);
  await waitFor(() => expect(JSON.parse(window.localStorage.getItem(INSTRUMENT_HULL_TUNING_STORAGE_KEY) ?? "{}")["/game::odyssey"]).toBeUndefined());
});

test("drops malformed, out-of-range, and oversized saved customization", () => {
  window.localStorage.setItem(INSTRUMENT_HULL_TUNING_STORAGE_KEY, JSON.stringify({
    odyssey: { height: 99 },
    ["x".repeat(1_025)]: { outerDetail: 0.012, outerSmooth: 0, height: 1 },
  }));
  const { result } = renderHook(() => useInstrumentHull(undefined, false));

  expect(result.current.customized).toBe(false);
  expect(result.current.tuning.height).toBe(1);
});

test("migrates the original three-field tuning without accepting a partial record", () => {
  expect(validateWireframeTuning({
    outerDetail: 0.02,
    outerSmooth: 0.3,
    height: 1.4,
  })).toEqual({
    outerDetail: 0.02,
    outerSmooth: 0.3,
    innerDetail: 0.016,
    innerSmooth: 0,
    height: 1.4,
  });
  expect(validateWireframeTuning({ height: 1.4 })).toBeNull();
});
