import { act, renderHook } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import { clearPreflightLocalStorage, PREFLIGHT_LOCAL_STORAGE_KEYS, readLastInstallRoot, rememberLastInstallRoot } from "./desktopStorage";
import { useHomePresentation } from "./useHomePresentation";
import { useInstrumentMotion } from "./useInstrumentMotion";
import { useProfileSearch } from "./useProfileSearch";
import { readSpeedRecord } from "./useSpeedRecord";

afterEach(() => vi.restoreAllMocks());

test("a denied storage getter cannot interrupt startup, session controls, or cleanup reporting", () => {
  vi.spyOn(window, "localStorage", "get").mockImplementation(() => {
    throw new DOMException("Storage denied", "SecurityError");
  });
  expect(readLastInstallRoot()).toBeNull();
  expect(() => rememberLastInstallRoot("/game")).not.toThrow();
  expect(readSpeedRecord()).toBeNull();
  expect(clearPreflightLocalStorage()).toEqual([...PREFLIGHT_LOCAL_STORAGE_KEYS]);
  const motion = renderHook(() => useInstrumentMotion());
  const search = renderHook(() => useProfileSearch());
  const home = renderHook(() => useHomePresentation());
  expect(home.result.current.mode).toBe("hangar");
  act(() => motion.result.current.setDirection("counter-clockwise"));
  act(() => search.result.current.setQuery("My profile"));
  expect(motion.result.current.direction).toBe("counter-clockwise");
  expect(search.result.current.query).toBe("My profile");
});
