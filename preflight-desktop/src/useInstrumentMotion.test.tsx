import { act, renderHook } from "@testing-library/react";
import { beforeEach, expect, test, vi } from "vitest";
import { INSTRUMENT_HULL_MOTION_STORAGE_KEY } from "./desktopStorage";
import {
  DEFAULT_INSTRUMENT_MOTION,
  readInstrumentMotion,
  resetInstrumentMotion,
  useInstrumentMotion,
  validateInstrumentMotion,
} from "./useInstrumentMotion";

beforeEach(() => {
  window.localStorage.clear();
  vi.restoreAllMocks();
});

test("defaults malformed or missing motion state to clockwise rotation", () => {
  expect(validateInstrumentMotion(null)).toEqual(DEFAULT_INSTRUMENT_MOTION);
  expect(validateInstrumentMotion({ motion: "rotate", direction: "sideways" })).toEqual(DEFAULT_INSTRUMENT_MOTION);
  expect(readInstrumentMotion()).toEqual(DEFAULT_INSTRUMENT_MOTION);

  window.localStorage.setItem(INSTRUMENT_HULL_MOTION_STORAGE_KEY, "still");
  expect(readInstrumentMotion()).toEqual({ motion: "rotate", direction: "clockwise" });

  window.localStorage.setItem(
    INSTRUMENT_HULL_MOTION_STORAGE_KEY,
    JSON.stringify({ motion: "rotate", direction: "counter-clockwise" }),
  );
  expect(readInstrumentMotion()).toEqual({ motion: "rotate", direction: "counter-clockwise" });
});

test("motion and direction changes synchronize every mounted renderer consumer", () => {
  const first = renderHook(() => useInstrumentMotion());
  const second = renderHook(() => useInstrumentMotion());

  act(() => first.result.current.setDirection("counter-clockwise"));
  expect(first.result.current.direction).toBe("counter-clockwise");
  expect(second.result.current.direction).toBe("counter-clockwise");

  act(() => first.result.current.setMotion("still"));
  expect(first.result.current.motion).toBe("still");
  expect(second.result.current.motion).toBe("still");
  expect(JSON.parse(window.localStorage.getItem(INSTRUMENT_HULL_MOTION_STORAGE_KEY) ?? "null"))
    .toEqual({ motion: "rotate", direction: "counter-clockwise" });
});

test("denied persistence still changes the complete preference for this session", () => {
  const setItem = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
    throw new DOMException("denied");
  });
  const first = renderHook(() => useInstrumentMotion());
  const second = renderHook(() => useInstrumentMotion());

  act(() => first.result.current.setDirection("counter-clockwise"));
  act(() => first.result.current.setMotion("still"));

  expect(setItem).toHaveBeenCalled();
  expect(first.result.current.motion).toBe("still");
  expect(first.result.current.direction).toBe("counter-clockwise");
  expect(second.result.current.motion).toBe("still");
  expect(second.result.current.direction).toBe("counter-clockwise");
});

test("all-data reset retires live motion without recreating cleared storage", () => {
  const first = renderHook(() => useInstrumentMotion());
  const second = renderHook(() => useInstrumentMotion());

  act(() => first.result.current.setDirection("counter-clockwise"));
  act(() => first.result.current.setMotion("still"));
  window.localStorage.removeItem(INSTRUMENT_HULL_MOTION_STORAGE_KEY);
  act(() => resetInstrumentMotion());

  expect(first.result.current.motion).toBe("rotate");
  expect(first.result.current.direction).toBe("clockwise");
  expect(second.result.current.motion).toBe("rotate");
  expect(second.result.current.direction).toBe("clockwise");
  expect(window.localStorage.getItem(INSTRUMENT_HULL_MOTION_STORAGE_KEY)).toBeNull();
});
