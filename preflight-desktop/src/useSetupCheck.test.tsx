import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import * as bridge from "./bridge";
import type { SetupAnalysisResult } from "./types";
import { useSetupCheck } from "./useSetupCheck";

const cleanResult: SetupAnalysisResult = {
  format: "starsector-preflight-setup-analysis-v1",
  installationIdentity: "install-v1:test",
  profileFingerprint: "profile:test",
  ready: true,
  counts: { blocking: 0, warning: 0, info: 0, unknown: 0 },
  findings: [],
  unavailableProviders: [],
};

afterEach(() => {
  vi.restoreAllMocks();
});

test("runs the deep setup check only when the player asks", async () => {
  const check = vi.spyOn(bridge, "checkSetup").mockResolvedValue(cleanResult);
  const announce = vi.fn();
  const { result } = renderHook(() => useSetupCheck("/game", "alpha", announce));

  expect(check).not.toHaveBeenCalled();
  await act(async () => result.current.run());

  expect(check).toHaveBeenCalledOnce();
  expect(check).toHaveBeenCalledWith("/game");
  expect(result.current.result).toEqual(cleanResult);
  expect(announce).not.toHaveBeenCalled();
});

test("does not launch two checks from repeated clicks", async () => {
  let finish: ((value: SetupAnalysisResult) => void) | undefined;
  const check = vi.spyOn(bridge, "checkSetup").mockImplementation(() => new Promise((resolve) => {
    finish = resolve;
  }));
  const { result } = renderHook(() => useSetupCheck("/game", "alpha", vi.fn()));

  act(() => {
    void result.current.run();
    void result.current.run();
  });
  expect(check).toHaveBeenCalledOnce();

  await act(async () => finish?.(cleanResult));
  await waitFor(() => expect(result.current.checking).toBe(false));
});

test("drops a result when the enabled mod list changes", async () => {
  const check = vi.spyOn(bridge, "checkSetup").mockResolvedValue(cleanResult);
  const { result, rerender } = renderHook(
    ({ setupKey }) => useSetupCheck("/game", setupKey, vi.fn()),
    { initialProps: { setupKey: "alpha" } },
  );

  await act(async () => result.current.run());
  expect(result.current.result).toEqual(cleanResult);

  rerender({ setupKey: "alpha\0beta" });
  expect(result.current.result).toBeNull();
  expect(check).toHaveBeenCalledOnce();
});

test("drops a result when another launcher may have changed the setup", async () => {
  vi.spyOn(bridge, "checkSetup").mockResolvedValue(cleanResult);
  const { result } = renderHook(() => useSetupCheck("/game", "alpha", vi.fn()));

  await act(async () => result.current.run());
  expect(result.current.result).toEqual(cleanResult);

  act(() => window.dispatchEvent(new Event("focus")));
  expect(result.current.result).toBeNull();
});
