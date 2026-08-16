import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, expect, test, vi } from "vitest";
import { getWireframeHulls } from "./bridge";
import { INSTRUMENT_HULL_STORAGE_KEY } from "./desktopStorage";
import type { WireframeHullCatalog } from "./types";
import { ORIGINAL_HULL_ID, useInstrumentHull } from "./useInstrumentHull";

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
  }],
};

beforeEach(() => {
  window.localStorage.clear();
  vi.mocked(getWireframeHulls).mockReset();
});

test("uses the original courier until a local catalog is available", async () => {
  vi.mocked(getWireframeHulls).mockResolvedValue(catalog);
  const { result } = renderHook(() => useInstrumentHull("/game"));

  expect(result.current.selectedId).toBe(ORIGINAL_HULL_ID);
  await waitFor(() => expect(result.current.catalog).toEqual(catalog));
  expect(result.current.hulls.map((hull) => hull.id)).toEqual([ORIGINAL_HULL_ID, "hammerhead"]);
});

test("keeps the courier fallback when the local catalog cannot be read", async () => {
  vi.mocked(getWireframeHulls).mockRejectedValue(new Error("unreadable hull directory"));
  const { result } = renderHook(() => useInstrumentHull("/game"));

  await waitFor(() => expect(getWireframeHulls).toHaveBeenCalledWith("/game"));
  expect(result.current.catalog).toBeNull();
  expect(result.current.hulls.map((hull) => hull.id)).toEqual([ORIGINAL_HULL_ID]);
  expect(result.current.selectedId).toBe(ORIGINAL_HULL_ID);
});

test("restores and persists an available local hull", async () => {
  window.localStorage.setItem(INSTRUMENT_HULL_STORAGE_KEY, "hammerhead");
  vi.mocked(getWireframeHulls).mockResolvedValue(catalog);
  const { result } = renderHook(() => useInstrumentHull("/game"));

  await waitFor(() => expect(result.current.selectedId).toBe("hammerhead"));
  act(() => result.current.choose(ORIGINAL_HULL_ID));
  expect(result.current.selectedId).toBe(ORIGINAL_HULL_ID);
  expect(window.localStorage.getItem(INSTRUMENT_HULL_STORAGE_KEY)).toBe(ORIGINAL_HULL_ID);
});

test("keeps the fallback when a saved hull disappeared", async () => {
  window.localStorage.setItem(INSTRUMENT_HULL_STORAGE_KEY, "missing-mod-hull");
  vi.mocked(getWireframeHulls).mockResolvedValue(catalog);
  const { result } = renderHook(() => useInstrumentHull("/game"));

  await waitFor(() => expect(result.current.catalog).toEqual(catalog));
  expect(result.current.selectedId).toBe(ORIGINAL_HULL_ID);
});

test("the bundled courier wins if an installation reuses its reserved id", async () => {
  vi.mocked(getWireframeHulls).mockResolvedValue({
    ...catalog,
    hulls: [{ ...catalog.hulls[0], id: ORIGINAL_HULL_ID }, ...catalog.hulls],
  });
  const { result } = renderHook(() => useInstrumentHull("/game"));

  await waitFor(() => expect(result.current.catalog).not.toBeNull());
  expect(result.current.hulls.map((hull) => hull.id)).toEqual([ORIGINAL_HULL_ID, "hammerhead"]);
});
