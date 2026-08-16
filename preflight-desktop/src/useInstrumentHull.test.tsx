import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, expect, test, vi } from "vitest";
import { getWireframeHulls } from "./bridge";
import { INSTRUMENT_HULL_STORAGE_KEY, INSTRUMENT_HULL_TUNING_STORAGE_KEY } from "./desktopStorage";
import type { WireframeHullCatalog } from "./types";
import { DEFAULT_HULL_ID, ORIGINAL_HULL_ID, useInstrumentHull } from "./useInstrumentHull";

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

test("pre-discovery state stays on the original fallback without requesting local hulls", () => {
  const { result } = renderHook(() => useInstrumentHull(undefined, false));

  expect(getWireframeHulls).not.toHaveBeenCalled();
  expect(result.current.catalog).toBeNull();
  expect(result.current.catalogLoaded).toBe(false);
  expect(result.current.hulls.map((candidate) => candidate.id)).toEqual([ORIGINAL_HULL_ID]);
  expect(result.current.selectedId).toBe(ORIGINAL_HULL_ID);
});

test("loads the current installation only when a hull UI is visible and reuses that result", async () => {
  vi.mocked(getWireframeHulls).mockResolvedValue(catalog);
  const { result, rerender } = renderHook(
    ({ game, enabled }) => useInstrumentHull(game, enabled),
    { initialProps: { game: "/game", enabled: false } },
  );

  expect(getWireframeHulls).not.toHaveBeenCalled();
  expect(result.current.catalog).toBeNull();
  expect(result.current.selectedId).toBe(ORIGINAL_HULL_ID);

  rerender({ game: "/game", enabled: true });
  await waitFor(() => expect(result.current.catalog).toEqual(catalog));
  expect(getWireframeHulls).toHaveBeenCalledTimes(1);
  expect(result.current.hulls.map((candidate) => candidate.id)).toEqual([
    "odyssey", "onslaught", "conquest", "paragon", "astral", "hammerhead", ORIGINAL_HULL_ID,
  ]);
  expect(result.current.selectedId).toBe(DEFAULT_HULL_ID);

  rerender({ game: "/game", enabled: false });
  rerender({ game: "/game", enabled: true });
  expect(getWireframeHulls).toHaveBeenCalledTimes(1);

  rerender({ game: "/other-game", enabled: false });
  expect(result.current.catalog).toBeNull();
  expect(result.current.selectedId).toBe(ORIGINAL_HULL_ID);
  expect(getWireframeHulls).toHaveBeenCalledTimes(1);

  rerender({ game: "/other-game", enabled: true });
  await waitFor(() => expect(getWireframeHulls).toHaveBeenCalledWith("/other-game"));
  expect(getWireframeHulls).toHaveBeenCalledTimes(2);
});

test("keeps the original fallback when the local catalog cannot be read", async () => {
  vi.mocked(getWireframeHulls).mockRejectedValue(new Error("unreadable hull directory"));
  const { result, rerender } = renderHook(
    ({ enabled }) => useInstrumentHull("/game", enabled),
    { initialProps: { enabled: true } },
  );

  await waitFor(() => expect(getWireframeHulls).toHaveBeenCalledWith("/game"));
  expect(result.current.catalog).toBeNull();
  expect(result.current.catalogLoaded).toBe(true);
  expect(result.current.hulls).toHaveLength(1);
  expect(result.current.selectedId).toBe(ORIGINAL_HULL_ID);

  rerender({ enabled: false });
  rerender({ enabled: true });
  expect(getWireframeHulls).toHaveBeenCalledTimes(1);
});

test("restores and persists an available local hull", async () => {
  window.localStorage.setItem(INSTRUMENT_HULL_STORAGE_KEY, "hammerhead");
  vi.mocked(getWireframeHulls).mockResolvedValue(catalog);
  const { result } = renderHook(() => useInstrumentHull("/game", true));

  await waitFor(() => expect(result.current.selectedId).toBe("hammerhead"));
  act(() => result.current.choose(ORIGINAL_HULL_ID));
  expect(result.current.selectedId).toBe(ORIGINAL_HULL_ID);
  expect(window.localStorage.getItem(INSTRUMENT_HULL_STORAGE_KEY)).toBe(ORIGINAL_HULL_ID);
});

test("keeps an explicit courier choice after the local catalog loads", async () => {
  window.localStorage.setItem(INSTRUMENT_HULL_STORAGE_KEY, ORIGINAL_HULL_ID);
  vi.mocked(getWireframeHulls).mockResolvedValue(catalog);
  const { result } = renderHook(() => useInstrumentHull("/game", true));

  await waitFor(() => expect(result.current.catalog).toEqual(catalog));
  expect(result.current.selectedId).toBe(ORIGINAL_HULL_ID);
});

test("falls back to Odyssey when a saved hull disappeared", async () => {
  window.localStorage.setItem(INSTRUMENT_HULL_STORAGE_KEY, "missing-mod-hull");
  vi.mocked(getWireframeHulls).mockResolvedValue(catalog);
  const { result } = renderHook(() => useInstrumentHull("/game", true));

  await waitFor(() => expect(result.current.catalog).toEqual(catalog));
  expect(result.current.selectedId).toBe(DEFAULT_HULL_ID);
});

test("the courier wins if an installation reuses its reserved id", async () => {
  vi.mocked(getWireframeHulls).mockResolvedValue({
    ...catalog,
    hulls: [{ ...catalog.hulls[0], id: ORIGINAL_HULL_ID }, ...catalog.hulls],
  });
  const { result } = renderHook(() => useInstrumentHull("/game", true));

  await waitFor(() => expect(result.current.catalog).not.toBeNull());
  expect(result.current.hulls.map((candidate) => candidate.id)).toEqual([
    "odyssey", "onslaught", "conquest", "paragon", "astral", "hammerhead", ORIGINAL_HULL_ID,
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
