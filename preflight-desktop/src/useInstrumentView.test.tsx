import { act, renderHook } from "@testing-library/react";
import { beforeEach, expect, test } from "vitest";
import { INSTRUMENT_HULL_VIEW_STORAGE_KEY } from "./desktopStorage";
import {
  DEFAULT_INSTRUMENT_VIEW,
  MAX_INSTRUMENT_PITCH,
  MIN_INSTRUMENT_PITCH,
  resetInstrumentView,
  useInstrumentView,
  validateInstrumentView,
} from "./useInstrumentView";

beforeEach(() => window.localStorage.clear());

test("persists a shared bounded ship view", () => {
  const first = renderHook(() => useInstrumentView());
  const second = renderHook(() => useInstrumentView());

  act(() => first.result.current.setView({ yaw: -0.7, pitch: 1.2, zoom: 1.25 }));
  expect(second.result.current).toEqual(expect.objectContaining({ yaw: -0.7, pitch: 1.2, zoom: 1.25 }));
  expect(JSON.parse(window.localStorage.getItem(INSTRUMENT_HULL_VIEW_STORAGE_KEY) ?? "null"))
    .toEqual({ yaw: -0.7, pitch: 1.2, zoom: 1.25 });
});

test("repairs invalid values and resets mounted consumers", () => {
  expect(validateInstrumentView({ yaw: 99, pitch: -4 })).toEqual({
    yaw: Math.PI,
    pitch: MIN_INSTRUMENT_PITCH,
    zoom: DEFAULT_INSTRUMENT_VIEW.zoom,
  });
  expect(validateInstrumentView({ yaw: "bad", pitch: 99 })).toEqual({
    yaw: DEFAULT_INSTRUMENT_VIEW.yaw,
    pitch: MAX_INSTRUMENT_PITCH,
    zoom: DEFAULT_INSTRUMENT_VIEW.zoom,
  });

  const { result } = renderHook(() => useInstrumentView());
  act(() => result.current.setView({ yaw: 1, pitch: 1, zoom: 1.4 }));
  act(() => resetInstrumentView());
  expect(result.current).toEqual(expect.objectContaining(DEFAULT_INSTRUMENT_VIEW));
});
