import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, expect, test, vi } from "vitest";
import { getWireframeHulls } from "./bridge";
import { INSTRUMENT_HULL_STORAGE_KEY } from "./desktopStorage";
import type { WireframeHullCatalog } from "./types";
import { CURATED_HULL_IDS } from "./curatedWireframeHulls";
import { DEFAULT_HULL_ID, ORIGINAL_HULL_ID, useInstrumentHull } from "./useInstrumentHull";

/* The shipped six are bundled, so they are on the list before anything is scanned, and the
   courier is last: it is the fallback for before an installation exists, not a choice. */
const shipped = [...CURATED_HULL_IDS];

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

test("pre-discovery state stays on the courier without requesting local hulls", () => {
  const { result } = renderHook(() => useInstrumentHull(undefined, false));

  expect(getWireframeHulls).not.toHaveBeenCalled();
  expect(result.current.catalog).toBeNull();
  expect(result.current.hulls.map((hull) => hull.id)).toEqual([...shipped, ORIGINAL_HULL_ID]);
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
  // The installation also has a Hammerhead. The traced one shadows it: both describe the same
  // ship, and only one of them knows what is inside it.
  expect(result.current.hulls.map((hull) => hull.id)).toEqual([...shipped, ORIGINAL_HULL_ID]);

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

test("keeps the courier fallback when the local catalog cannot be read", async () => {
  vi.mocked(getWireframeHulls).mockRejectedValue(new Error("unreadable hull directory"));
  const { result, rerender } = renderHook(
    ({ enabled }) => useInstrumentHull("/game", enabled),
    { initialProps: { enabled: true } },
  );

  await waitFor(() => expect(getWireframeHulls).toHaveBeenCalledWith("/game"));
  expect(result.current.catalog).toBeNull();
  expect(result.current.hulls.map((hull) => hull.id)).toEqual([...shipped, ORIGINAL_HULL_ID]);
  expect(result.current.selectedId).toBe(DEFAULT_HULL_ID);

  rerender({ enabled: false });
  rerender({ enabled: true });
  expect(getWireframeHulls).toHaveBeenCalledTimes(1);
});

test("restores and persists an available local hull", async () => {
  window.localStorage.setItem(INSTRUMENT_HULL_STORAGE_KEY, "conquest");
  vi.mocked(getWireframeHulls).mockResolvedValue(catalog);
  const { result } = renderHook(() => useInstrumentHull("/game", true));

  await waitFor(() => expect(result.current.selectedId).toBe("conquest"));
  act(() => result.current.choose(ORIGINAL_HULL_ID));
  expect(result.current.selectedId).toBe(ORIGINAL_HULL_ID);
  expect(window.localStorage.getItem(INSTRUMENT_HULL_STORAGE_KEY)).toBe(ORIGINAL_HULL_ID);
});

test("falls back to the shipped default when a saved hull disappeared", async () => {
  window.localStorage.setItem(INSTRUMENT_HULL_STORAGE_KEY, "missing-mod-hull");
  vi.mocked(getWireframeHulls).mockResolvedValue(catalog);
  const { result } = renderHook(() => useInstrumentHull("/game", true));

  await waitFor(() => expect(result.current.catalog).toEqual(catalog));
  expect(result.current.selectedId).toBe(DEFAULT_HULL_ID);
});

test("the shipped hulls are on the list before any installation has been read", () => {
  const { result } = renderHook(() => useInstrumentHull(undefined, false));

  // The idle screen shows a ship on the first frame, so the six cannot wait behind a scan.
  expect(getWireframeHulls).not.toHaveBeenCalled();
  expect(result.current.selected.curated).toBeDefined();
  expect(result.current.selected.id).toBe(DEFAULT_HULL_ID);
});

test("the bundled courier wins if an installation reuses its reserved id", async () => {
  vi.mocked(getWireframeHulls).mockResolvedValue({
    ...catalog,
    hulls: [{ ...catalog.hulls[0], id: ORIGINAL_HULL_ID }, ...catalog.hulls],
  });
  const { result } = renderHook(() => useInstrumentHull("/game", true));

  await waitFor(() => expect(result.current.catalog).not.toBeNull());
  expect(result.current.hulls.map((hull) => hull.id)).toEqual([...shipped, ORIGINAL_HULL_ID]);
});
