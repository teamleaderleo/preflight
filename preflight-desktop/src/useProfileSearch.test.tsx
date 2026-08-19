import { act, renderHook } from "@testing-library/react";
import { beforeEach, expect, test, vi } from "vitest";
import { PROFILE_SEARCH_STORAGE_KEY } from "./desktopStorage";
import {
  filterProfileNames,
  readProfileSearch,
  resetProfileSearch,
  useProfileSearch,
  validateProfileSearch,
} from "./useProfileSearch";

beforeEach(() => {
  window.localStorage.clear();
  vi.restoreAllMocks();
});

test("reads only a bounded stored profile query", () => {
  expect(validateProfileSearch(null)).toBe("");
  expect(validateProfileSearch("Main")).toBe("Main");
  expect(validateProfileSearch("x".repeat(101))).toBe("");
  expect(readProfileSearch()).toBe("");

  window.localStorage.setItem(PROFILE_SEARCH_STORAGE_KEY, "Campaign");
  expect(readProfileSearch()).toBe("Campaign");
});

test("filters saved profile names case-insensitively and preserves list order", () => {
  const profiles = [{ name: "Main Campaign" }, { name: "Vanilla Test" }, { name: "Heavy Mods" }];
  expect(filterProfileNames(profiles, "main")).toEqual([{ name: "Main Campaign" }]);
  expect(filterProfileNames(profiles, "  MODS ")).toEqual([{ name: "Heavy Mods" }]);
  expect(filterProfileNames(profiles, "")).toEqual(profiles);
});

test("query changes synchronize mounted consumers and clear empty persistence", () => {
  const first = renderHook(() => useProfileSearch());
  const second = renderHook(() => useProfileSearch());

  act(() => first.result.current.setQuery("Test"));
  expect(first.result.current.query).toBe("Test");
  expect(second.result.current.query).toBe("Test");
  expect(window.localStorage.getItem(PROFILE_SEARCH_STORAGE_KEY)).toBe("Test");

  act(() => first.result.current.setQuery(""));
  expect(second.result.current.query).toBe("");
  expect(window.localStorage.getItem(PROFILE_SEARCH_STORAGE_KEY)).toBeNull();
});

test("denied persistence still keeps search useful for the session", () => {
  vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
    throw new DOMException("denied");
  });
  const first = renderHook(() => useProfileSearch());
  const second = renderHook(() => useProfileSearch());

  act(() => first.result.current.setQuery("Heavy"));
  expect(first.result.current.query).toBe("Heavy");
  expect(second.result.current.query).toBe("Heavy");
});

test("all-data reset clears mounted search without recreating storage", () => {
  const first = renderHook(() => useProfileSearch());
  const second = renderHook(() => useProfileSearch());

  act(() => first.result.current.setQuery("Campaign"));
  window.localStorage.removeItem(PROFILE_SEARCH_STORAGE_KEY);
  act(() => resetProfileSearch());

  expect(first.result.current.query).toBe("");
  expect(second.result.current.query).toBe("");
  expect(window.localStorage.getItem(PROFILE_SEARCH_STORAGE_KEY)).toBeNull();
});
