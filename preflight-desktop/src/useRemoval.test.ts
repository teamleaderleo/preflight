import { act, renderHook } from "@testing-library/react";
import { expect, test, vi } from "vitest";
import * as bridge from "./bridge";
import type { RemovalPlan } from "./types";
import { useRemoval } from "./useRemoval";

function plan(overrides: Partial<RemovalPlan> = {}): RemovalPlan {
  return {
    format: "preflight-removal-v1",
    scope: "all-data",
    safe: true,
    applied: false,
    bytes: 1024,
    files: 1,
    targets: [{
      kind: "preflight-data",
      label: "Preflight data",
      path: "/tmp/preflight-data",
      bytes: 1024,
      files: 1,
    }],
    refusals: [],
    preserves: [],
    ...overrides,
  };
}

test("unsafe removal plan is retained for review and announced as blocked", async () => {
  const refusal = "Preflight home directory is a symlink or alias. All-data removal is refused.";
  const getRemovalPlan = vi.spyOn(bridge, "getRemovalPlan").mockResolvedValue(plan({
    safe: false,
    refusals: [refusal],
  }));
  const announce = vi.fn();
  const { result } = renderHook(() => useRemoval("mac", announce, vi.fn(), vi.fn()));

  await act(async () => {
    await result.current.review("all-data");
  });

  expect(result.current.plan?.safe).toBe(false);
  expect(result.current.plan?.refusals).toEqual([refusal]);
  expect(announce).toHaveBeenCalledWith("Removal can’t continue. Review the reason below.", "warning");
  getRemovalPlan.mockRestore();
});

test("partial removal plan announces skipped items for review", async () => {
  const getRemovalPlan = vi.spyOn(bridge, "getRemovalPlan").mockResolvedValue(plan({
    refusals: ["One launcher integration changed and was left alone."],
  }));
  const announce = vi.fn();
  const { result } = renderHook(() => useRemoval("mac", announce, vi.fn(), vi.fn()));

  await act(async () => {
    await result.current.review("all-data");
  });

  expect(result.current.plan?.safe).toBe(true);
  expect(announce).toHaveBeenCalledWith("Removal is ready to review. Some items could not be included.", "warning");
  getRemovalPlan.mockRestore();
});
