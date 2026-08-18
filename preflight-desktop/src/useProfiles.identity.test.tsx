import { act, renderHook, waitFor } from "@testing-library/react";
import * as bridge from "./bridge";
import * as profileActivation from "./profileActivationBridge";
import type { NamedProfile, ProfileList } from "./types";
import { useProfiles } from "./useProfiles";

function profile(name: string, fingerprint: string, active: boolean): NamedProfile {
  return {
    name,
    installRoot: "/Applications/Starsector",
    enabledMods: [],
    modCount: 0,
    profileFingerprint: fingerprint,
    savedAt: "2026-08-18T00:00:00Z",
    sameInstall: true,
    active,
    canActivate: true,
    missingMods: [],
    file: `/profiles/${name}.json`,
  };
}

const initialProfiles: ProfileList = {
  format: "starsector-preflight-profile-list-v1",
  installRoot: "/Applications/Starsector",
  enabledMods: ["campaign"],
  profiles: [
    profile("Main campaign", "old-fingerprint", true),
    profile("Utilities only", "new-fingerprint", false),
  ],
  diagnostics: [],
};

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

test("a successful switch retires the old active name even when the profile refresh fails", async () => {
  const profiles = vi.spyOn(bridge, "getProfiles")
    .mockResolvedValueOnce(initialProfiles)
    .mockRejectedValueOnce(new Error("profile refresh failed"));
  const activate = vi.spyOn(profileActivation, "activateReviewedProfile")
    .mockResolvedValueOnce(plan())
    .mockResolvedValueOnce(plan({ applied: true }));
  const refreshInstallation = vi.fn().mockResolvedValue(true);
  const refreshCache = vi.fn().mockResolvedValue(undefined);
  const announce = vi.fn();

  try {
    const { result } = renderHook(() => useProfiles(
      "/Applications/Starsector",
      true,
      refreshInstallation,
      refreshCache,
      announce,
    ));

    await waitFor(() => expect(result.current.profiles).toEqual(initialProfiles));
    await act(async () => result.current.reviewProfile("Utilities only"));
    await act(async () => result.current.applyProfile());

    expect(result.current.profiles?.profiles.filter((candidate) => candidate.active).map((candidate) => candidate.name))
      .toEqual(["Utilities only"]);
    expect(announce).toHaveBeenCalledWith(expect.stringContaining("profile refresh failed"), "error");
    expect(announce).toHaveBeenCalledWith(expect.stringContaining("Switched to “Utilities only”"));
  } finally {
    profiles.mockRestore();
    activate.mockRestore();
  }
});
