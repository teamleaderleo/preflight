import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { FRAME_PACING_STORAGE_KEY, SMOOTH_FRAME_PACING_STORAGE_KEY } from "./desktopStorage";
import { useFramePacingPreference } from "./useFramePacingPreference";

describe("useFramePacingPreference", () => {
  beforeEach(() => window.localStorage.clear());

  it("is opt-in and remembers the choice", () => {
    const first = renderHook(() => useFramePacingPreference());
    expect(first.result.current.recordFramePacing).toBe(false);
    act(() => first.result.current.setRecordFramePacing(true));
    expect(window.localStorage.getItem(FRAME_PACING_STORAGE_KEY)).toBe("on");
    first.unmount();
    expect(renderHook(() => useFramePacingPreference()).result.current.recordFramePacing).toBe(true);
  });

  it("treats malformed and future stored values as off", () => {
    window.localStorage.setItem(FRAME_PACING_STORAGE_KEY, "yes-forever");
    expect(renderHook(() => useFramePacingPreference()).result.current.recordFramePacing).toBe(false);
  });

  it("keeps smooth presentation pacing as a separate opt-in", () => {
    const preference = renderHook(() => useFramePacingPreference());
    expect(preference.result.current.smoothFramePacing).toBe(false);
    act(() => preference.result.current.setSmoothFramePacing(true));
    expect(window.localStorage.getItem(SMOOTH_FRAME_PACING_STORAGE_KEY)).toBe("on");
    expect(preference.result.current.recordFramePacing).toBe(false);
  });
});
