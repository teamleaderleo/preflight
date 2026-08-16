import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, expect, test, vi } from "vitest";
import { getWireframeHulls } from "./bridge";
import { INSTRUMENT_HULL_STORAGE_KEY } from "./desktopStorage";
import type { WireframeHullCatalog } from "./types";
import { DEFAULT_HULL_ID, ORIGINAL_HULL_ID, useInstrumentHull } from "./useInstrumentHull";

vi.mock("./bridge", () => ({ getWireframeHulls: vi.fn() }));

const catalog: WireframeHullCatalog = {
  format: "preflight-wireframe-hulls-v1",
  skipped: 0,
  hulls: [{
    id: "hammerhead",
    name: "Hammerhead",
    hullSize: "DESTROYER",
    style: "MIDLINE",
    featured: true,
    bounds: [{ x: 10, y: 0 }, { x: -5, y: 8 }, { x: -5, y: -8 }],
    engines: [],
    mounts: [],
  }, {
    id: DEFAULT_HULL_ID,
    name: "Odyssey",
    hullSize: "CAPITAL_SHIP",
    style: "HIGH_TECH",
    featured: true,
    bounds: [{ x: 12, y: 0 }, { x: -6, y: 9 }, { x: -6, y: -9 }],
    engines: [],
    mounts: [],
  }],
};

beforeEach(() => {
  window.localStorage.clear();
  vi.mocked(getWireframeHulls).mockReset();
});

test("pre-discovery state stays on the courier without requesting local hulls", () => {
  const { result } = renderHook(() => useInstrumentHull(undefined, false));

  expect(getWireframeHulls).not.toHaveBeenCalled();
  expect(result.current.catalog).toBeNull();
  expect(result.current.catalogLoaded).toBe(false);
  expect(result.current.hulls.map((hull) => hull.id)).toEqual([ORIGINAL_HULL_ID]);
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
  expect(result.current.hulls.map((hull) => hull.id)).toEqual([ORIGINAL_HULL_ID, "hammerhead", DEFAULT_HULL_ID]);
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

test("keeps the courier fallback when the local catalog cannot be read", async () => {
  vi.mocked(getWireframeHulls).mockRejectedValue(new Error("unreadable hull directory"));
  const { result, rerender } = renderHook(
    ({ enabled }) => useInstrumentHull("/game", enabled),
    { initialProps: { enabled: true } },
  );

  await waitFor(() => expect(getWireframeHulls).toHaveBeenCalledWith("/game"));
  expect(result.current.catalog).toBeNull();
  expect(result.current.catalogLoaded).toBe(true);
  expect(result.current.hulls.map((hull) => hull.id)).toEqual([ORIGINAL_HULL_ID]);
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

test("uses the first featured hull when Odyssey is unavailable", async () => {
  vi.mocked(getWireframeHulls).mockResolvedValue({ ...catalog, hulls: [catalog.hulls[0]] });
  const { result } = renderHook(() => useInstrumentHull("/game", true));

  await waitFor(() => expect(result.current.catalog).not.toBeNull());
  expect(result.current.selectedId).toBe("hammerhead");
});

test("the bundled courier wins if an installation reuses its reserved id", async () => {
  vi.mocked(getWireframeHulls).mockResolvedValue({
    ...catalog,
    hulls: [{ ...catalog.hulls[0], id: ORIGINAL_HULL_ID }, ...catalog.hulls],
  });
  const { result } = renderHook(() => useInstrumentHull("/game", true));

  await waitFor(() => expect(result.current.catalog).not.toBeNull());
  expect(result.current.hulls.map((hull) => hull.id)).toEqual([ORIGINAL_HULL_ID, "hammerhead", DEFAULT_HULL_ID]);
});
