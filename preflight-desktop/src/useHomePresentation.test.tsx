import { act, renderHook } from "@testing-library/react";
import { beforeEach, expect, test, vi } from "vitest";
import { HOME_PRESENTATION_STORAGE_KEY } from "./desktopStorage";
import {
  DEFAULT_HOME_PRESENTATION,
  initializeHomePresentation,
  readHomePresentation,
  useHomePresentation,
} from "./useHomePresentation";

beforeEach(() => {
  window.localStorage.clear();
  delete document.documentElement.dataset.homePlaytime;
});

test("missing and malformed stored preferences fall back to showing playtime", () => {
  expect(readHomePresentation()).toEqual(DEFAULT_HOME_PRESENTATION);

  window.localStorage.setItem(HOME_PRESENTATION_STORAGE_KEY, JSON.stringify({ showPlaytime: "no" }));
  expect(readHomePresentation()).toEqual(DEFAULT_HOME_PRESENTATION);

  window.localStorage.setItem(HOME_PRESENTATION_STORAGE_KEY, "{");
  expect(readHomePresentation()).toEqual(DEFAULT_HOME_PRESENTATION);
});

test("startup applies the stored preference before Home renders", () => {
  window.localStorage.setItem(HOME_PRESENTATION_STORAGE_KEY, JSON.stringify({ showPlaytime: false }));

  expect(initializeHomePresentation()).toEqual({ showPlaytime: false });
  expect(document.documentElement.dataset.homePlaytime).toBe("hidden");
});

test("changing visibility persists and synchronizes mounted consumers", () => {
  const first = renderHook(() => useHomePresentation());
  const second = renderHook(() => useHomePresentation());

  act(() => first.result.current.setShowPlaytime(false));

  expect(first.result.current.showPlaytime).toBe(false);
  expect(second.result.current.showPlaytime).toBe(false);
  expect(document.documentElement.dataset.homePlaytime).toBe("hidden");
  expect(JSON.parse(window.localStorage.getItem(HOME_PRESENTATION_STORAGE_KEY) ?? "null"))
    .toEqual({ showPlaytime: false });
});

test("denied persistence still changes every mounted consumer for this session", () => {
  const first = renderHook(() => useHomePresentation());
  const second = renderHook(() => useHomePresentation());
  const setItem = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
    throw new Error("denied");
  });

  act(() => first.result.current.setShowPlaytime(false));

  expect(first.result.current.showPlaytime).toBe(false);
  expect(second.result.current.showPlaytime).toBe(false);
  expect(document.documentElement.dataset.homePlaytime).toBe("hidden");
  setItem.mockRestore();
});
