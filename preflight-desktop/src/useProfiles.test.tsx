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

test("duplicate profile workflow reviews then applies duplicate mutation", async () => {
  const getProfilesSpy = vi.spyOn(bridge, "getProfiles").mockResolvedValue({
    format: "starsector-preflight-profile-list-v1",
    installRoot: "/Applications/Starsector",
    enabledMods: ["alpha"],
    profiles: [{
      name: "Main",
      installRoot: "/Applications/Starsector",
      enabledMods: ["alpha"],
      modCount: 1,
      profileFingerprint: "a".repeat(64),
      savedAt: new Date().toISOString(),
      sameInstall: true,
      active: true,
      canActivate: true,
      missingMods: [],
      file: "/home/profiles/a.json",
    }],
    diagnostics: [],
  });

  const duplicateSpy = vi.spyOn(bridge, "duplicateProfile")
    .mockResolvedValueOnce({
      format: "starsector-preflight-profile-mutation-v1",
      operation: "duplicate",
      name: "Main",
      targetName: "Experiment",
      profileFingerprint: "a".repeat(64),
      active: false,
      modCount: 1,
      applied: false,
      preparedDataKept: true,
    })
    .mockResolvedValueOnce({
      format: "starsector-preflight-profile-mutation-v1",
      operation: "duplicate",
      name: "Main",
      targetName: "Experiment",
      profileFingerprint: "a".repeat(64),
      active: false,
      modCount: 1,
      applied: true,
      preparedDataKept: true,
    });

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

  act(() => {
    result.current.beginDuplicate("Main");
  });
  expect(result.current.duplicateTarget).toBe("Main");
  expect(result.current.duplicateDraft).toBe("Main (Copy)");

  act(() => {
    result.current.setDuplicateDraft("Experiment");
  });
  expect(result.current.duplicateDraft).toBe("Experiment");

  await act(async () => {
    result.current.submitDuplicate();
  });

  expect(duplicateSpy).toHaveBeenNthCalledWith(
    1,
    "/Applications/Starsector",
    "Main",
    "Experiment",
    null,
    false,
  );
  expect(result.current.duplicateTarget).toBeNull();
  expect(result.current.mutationPlan).toMatchObject({
    operation: "duplicate",
    name: "Main",
    targetName: "Experiment",
    applied: false,
  });

  await act(async () => {
    await result.current.applyProfileMutation();
  });

  expect(duplicateSpy).toHaveBeenNthCalledWith(
    2,
    "/Applications/Starsector",
    "Main",
    "Experiment",
    "a".repeat(64),
    true,
  );
  expect(result.current.mutationPlan).toBeNull();
  expect(announce).toHaveBeenCalledWith("Duplicated “Main” as “Experiment”.", "success");

  getProfilesSpy.mockRestore();
  duplicateSpy.mockRestore();
});
