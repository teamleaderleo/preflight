import { act, renderHook } from "@testing-library/react";
import { useWorkflowNotices } from "./useWorkflowNotices";

test("keeps unrelated workflow notices independent", () => {
  const { result } = renderHook(() => useWorkflowNotices());

  act(() => result.current.announce("game-settings", "Settings write refused", "error"));
  act(() => result.current.announce("updates", "Preflight is current", "success"));

  expect(result.current.latest(["game-settings"])).toMatchObject({
    message: "Settings write refused",
    tone: "error",
  });
  expect(result.current.latest(["updates"])).toMatchObject({
    message: "Preflight is current",
    tone: "success",
  });
});

test("selects the newest notice among workflows visible on the same page", () => {
  const { result } = renderHook(() => useWorkflowNotices());

  act(() => result.current.announce("installation", "Starsector found", "success"));
  act(() => result.current.announce("game", "Opening Starsector…"));

  expect(result.current.latest(["installation", "game"])?.message).toBe("Opening Starsector…");
});

test("clearing a workflow reveals an older notice without disturbing it", () => {
  const { result } = renderHook(() => useWorkflowNotices());

  act(() => result.current.announce("benchmark", "Benchmark unavailable", "error"));
  act(() => result.current.announce("support", "Support ZIP ready", "success"));
  act(() => result.current.clear("support"));

  expect(result.current.latest(["benchmark", "support"])?.message).toBe("Benchmark unavailable");
  expect(result.current.latest(["support"])).toBeNull();
});
