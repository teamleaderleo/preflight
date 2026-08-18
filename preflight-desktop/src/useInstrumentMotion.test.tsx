import { act, renderHook } from "@testing-library/react";
import { beforeEach, expect, test, vi } from "vitest";
import { INSTRUMENT_HULL_MOTION_STORAGE_KEY } from "./desktopStorage";
import { readInstrumentMotion, useInstrumentMotion, validateInstrumentMotion } from "./useInstrumentMotion";

beforeEach(() => {
  window.localStorage.clear();
  vi.restoreAllMocks();
});

test("defaults malformed or missing motion state to rotation", () => {
  expect(validateInstrumentMotion(null)).toBe("rotate");
  expect(validateInstrumentMotion("clockwise")).toBe("rotate");
  expect(readInstrumentMotion()).toBe("rotate");

  window.localStorage.setItem(INSTRUMENT_HULL_MOTION_STORAGE_KEY, "still");
  expect(readInstrumentMotion()).toBe("still");
});

test("one motion change synchronizes every mounted renderer consumer", () => {
  const first = renderHook(() => useInstrumentMotion());
  const second = renderHook(() => useInstrumentMotion());

  act(() => first.result.current.setMotion("still"));

  expect(first.result.current.motion).toBe("still");
  expect(second.result.current.motion).toBe("still");
  expect(window.localStorage.getItem(INSTRUMENT_HULL_MOTION_STORAGE_KEY)).toBe("still");
});

test("denied persistence still changes motion for the current session", () => {
  const setItem = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
    throw new DOMException("denied");
  });
  const first = renderHook(() => useInstrumentMotion());
  const second = renderHook(() => useInstrumentMotion());

  act(() => first.result.current.setMotion("still"));

  expect(setItem).toHaveBeenCalledWith(INSTRUMENT_HULL_MOTION_STORAGE_KEY, "still");
  expect(first.result.current.motion).toBe("still");
  expect(second.result.current.motion).toBe("still");
});
