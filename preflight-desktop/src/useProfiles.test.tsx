import { act, renderHook, waitFor } from "@testing-library/react";
import * as bridge from "./bridge";
import * as profileActivation from "./profileActivationBridge";
import { useProfiles } from "./useProfiles";

function plan(overrides: Partial<profileActivation.ReviewedProfileActivationPlan> = {}) {
  return {
    format: "starsector-preflight-profile-activation-v1" as const,
    name: "Utilities only",
    installRoot: "/Applications/Starsector",
    savedInstallRoot: "/Applications/Starsector",
    sameInstall: true,
    active: false,
    canActivate: true,
    applied: false,
    enable: ["utility"],
    disable: ["campaign"],
    missingMods: [],
    sourceStateSha256: "1".repeat(64),
    sourceChanged: false,
    profileChanged: false,
    reviewChanged: false,
    ...overrides,
  };
}

test("reuses the current installation list across ordinary page navigation", async () => {
  const profiles = vi.spyOn(bridge, "getProfiles");
  const refreshInstallation = vi.fn().mockResolvedValue(true);
  const refreshCache = vi.fn().mockResolvedValue(undefined);
  const announce = vi.fn();
  try {
    const { rerender } = renderHook(
      ({ visible }) => useProfiles(
        "/Applications/Starsector",
        visible,
        refreshInstallation,
        refreshCache,
        announce,
      ),
      { initialProps: { visible: true } },
    );

    await waitFor(() => expect(profiles).toHaveBeenCalledTimes(1));
    rerender({ visible: false });
    rerender({ visible: true });
    await act(async () => undefined);
    expect(profiles).toHaveBeenCalledTimes(1);
  } finally {
    profiles.mockRestore();
  }
});

test("a stale activation becomes a fresh review instead of reporting success", async () => {
  const activate = vi.spyOn(profileActivation, "activateReviewedProfile")
    .mockResolvedValueOnce(plan())
    .mockResolvedValueOnce(plan({
      enable: ["utility", "newly-observed"],
      sourceStateSha256: "2".repeat(64),
      sourceChanged: true,
      reviewChanged: true,
    }));
  const refreshInstallation = vi.fn().mockResolvedValue(true);
  const refreshCache = vi.fn().mockResolvedValue(undefined);
  const announce = vi.fn();
  const { result } = renderHook(() => useProfiles(
    "/Applications/Starsector",
    false,
    refreshInstallation,
    refreshCache,
    announce,
  ));

  await act(async () => result.current.reviewProfile("Utilities only"));
  expect(result.current.activationPlan?.sourceStateSha256).toBe("1".repeat(64));

  await act(async () => result.current.applyProfile());

  expect(activate).toHaveBeenNthCalledWith(1, "/Applications/Starsector", "Utilities only", false);
  expect(activate).toHaveBeenNthCalledWith(2, "/Applications/Starsector", "Utilities only", true);
  await waitFor(() => expect(result.current.activationPlan?.sourceStateSha256).toBe("2".repeat(64)));
  expect(result.current.activationPlan?.enable).toEqual(["utility", "newly-observed"]);
  expect(announce).toHaveBeenCalledWith(
    "The current mod selection changed since you reviewed this switch. Review the updated changes, then apply again.",
    "warning",
  );
  expect(announce).not.toHaveBeenCalledWith(expect.stringContaining("Switched to"), expect.anything());

  activate.mockRestore();
});
