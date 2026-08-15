import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { AFTER_LAUNCH_BEHAVIOR_STORAGE_KEY } from "./desktopStorage";
import { useAfterLaunchBehavior } from "./useAfterLaunchBehavior";

describe("useAfterLaunchBehavior", () => {
  beforeEach(() => window.localStorage.clear());

  it("minimizes by default and remembers an explicit choice", () => {
    const first = renderHook(() => useAfterLaunchBehavior());
    expect(first.result.current.afterLaunchBehavior).toBe("minimize");
    act(() => first.result.current.setAfterLaunchBehavior("quit"));
    expect(window.localStorage.getItem(AFTER_LAUNCH_BEHAVIOR_STORAGE_KEY)).toBe("quit");
    first.unmount();
    expect(renderHook(() => useAfterLaunchBehavior()).result.current.afterLaunchBehavior).toBe("quit");
  });

  it("ignores malformed and future stored values", () => {
    window.localStorage.setItem(AFTER_LAUNCH_BEHAVIOR_STORAGE_KEY, "vanish");
    expect(renderHook(() => useAfterLaunchBehavior()).result.current.afterLaunchBehavior).toBe("minimize");
  });
});
