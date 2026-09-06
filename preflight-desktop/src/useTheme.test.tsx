import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { PALETTE_STORAGE_KEY, THEME_STORAGE_KEY, useTheme } from "./useTheme";

beforeEach(() => {
  window.localStorage.clear();
  delete document.documentElement.dataset.theme;
  delete document.documentElement.dataset.palette;
  vi.stubGlobal("matchMedia", vi.fn().mockImplementation(() => ({
    matches: true,
    media: "(prefers-color-scheme: dark)",
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })));
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  document.body.classList.remove("theme-changing");
});

test.each(["getter", "methods"])("unavailable storage (%s) preserves session appearance", (failure) => {
  const denied = () => { throw new DOMException("Storage denied", "SecurityError"); };
  if (failure === "getter") {
    vi.spyOn(window, "localStorage", "get").mockImplementation(denied);
  } else {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(denied);
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(denied);
  }
  const { result, unmount } = renderHook(() => useTheme());
  expect(result.current.preference).toBe("system");
  expect(result.current.palette).toBe("blueprint");
  act(() => result.current.setPreference("light"));
  act(() => result.current.setPalette("phosphor"));
  expect(result.current.resolved).toBe("light");
  expect(document.documentElement.dataset.theme).toBe("light");
  expect(document.documentElement.dataset.palette).toBe("phosphor");
  unmount();
  expect(document.body).not.toHaveClass("theme-changing");
});

test("follows the system until an explicit theme is saved", async () => {
  const { result } = renderHook(() => useTheme());

  expect(result.current.preference).toBe("system");
  await waitFor(() => expect(document.documentElement.dataset.theme).toBe("dark"));

  act(() => result.current.setPreference("light"));

  await waitFor(() => expect(result.current.resolved).toBe("light"));
  expect(result.current.preference).toBe("light");
  expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe("light");
  expect(document.documentElement.dataset.theme).toBe("light");
});

test("starts with Blueprint and persists every explicit palette", async () => {
  const { result } = renderHook(() => useTheme());

  expect(result.current.palette).toBe("blueprint");
  await waitFor(() => expect(document.documentElement.dataset.palette).toBe("blueprint"));

  act(() => result.current.setPalette("phosphor"));

  expect(result.current.palette).toBe("phosphor");
  expect(window.localStorage.getItem(PALETTE_STORAGE_KEY)).toBe("phosphor");
  await waitFor(() => expect(document.documentElement.dataset.palette).toBe("phosphor"));
});
