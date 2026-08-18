import { launchProfileNameFor } from "./launchProfileIdentity";
import type { NamedProfile, ProfileList } from "./types";

function profile(name: string, fingerprint: string, active = true): NamedProfile {
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

function profiles(...items: NamedProfile[]): ProfileList {
  return {
    format: "starsector-preflight-profile-list-v1",
    installRoot: "/Applications/Starsector",
    enabledMods: [],
    profiles: items,
    diagnostics: [],
  };
}

test("names one active profile only when it agrees with current mod-set identity", () => {
  expect(launchProfileNameFor(profiles(profile("Exploration", "new")), "new")).toBe("Exploration");
  expect(launchProfileNameFor(profiles(profile("Old campaign", "old")), "new")).toBeNull();
});

test("equivalent duplicate active profiles remain matching context instead of a fake selection", () => {
  expect(launchProfileNameFor(profiles(
    profile("Exploration", "same"),
    profile("Exploration copy", "same"),
  ), "same")).toBeNull();
});

test("a missing cache fingerprint can still use the one freshly observed active profile", () => {
  expect(launchProfileNameFor(profiles(profile("Exploration", "new")), null)).toBe("Exploration");
});
