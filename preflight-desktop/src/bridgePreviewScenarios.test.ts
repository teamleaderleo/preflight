import { afterEach, expect, test } from "vitest";
import {
  browserPreviewScenario,
  checkForUpdate,
  deleteProfile,
  getCache,
  getCacheHealth,
  getDesktopSmokeProbe,
  getPreparationPlan,
  getProfiles,
  getSnapshot,
  renameProfile,
} from "./bridge";

afterEach(() => {
  window.history.replaceState(null, "", "/");
});

function useScenario(scenario: string) {
  window.history.replaceState(null, "", `/?scenario=${scenario}`);
}

test("unknown browser scenarios fail back to the normal ready preview", async () => {
  useScenario("invented");
  expect(browserPreviewScenario()).toBe("ready");
  expect((await getSnapshot()).ready).toBe(true);
});

test("setup, low-disk, and cache-repair previews expose safe failure states", async () => {
  useScenario("setup");
  expect(await getSnapshot()).toMatchObject({ ready: false, selected: null });

  useScenario("low-disk");
  expect((await getCache("preview")).profiles).toHaveLength(0);
  expect(await getPreparationPlan("preview", "balanced", 4)).toMatchObject({
    safeToPrepare: false,
    usableBytes: 2_147_483_648,
  });

  useScenario("cache-repair");
  expect(await getCacheHealth("preview")).toMatchObject({
    status: "repair-needed",
    repairFiles: 3,
  });
});

test("profile, benchmark, and update previews remain explicit and bounded", async () => {
  useScenario("profile-mismatch");
  expect((await getProfiles("preview")).profiles.find((profile) => profile.name === "Utilities only")).toMatchObject({
    canActivate: false,
    missingMods: ["graphicslib"],
  });

  useScenario("benchmark-unavailable");
  expect((await getDesktopSmokeProbe()).probe).toMatchObject({ ready: false, driver: null });

  useScenario("update-error");
  await expect(checkForUpdate()).rejects.toThrow("preview update service");
});

test("profile mutation previews remain inert until the reviewed fingerprint is confirmed", async () => {
  const renamed = await renameProfile("preview", "Main campaign", "Long campaign", null, false);
  expect(renamed).toMatchObject({
    operation: "rename",
    targetName: "Long campaign",
    applied: false,
    preparedDataKept: true,
  });
  expect(await renameProfile(
    "preview",
    "Main campaign",
    "Long campaign",
    renamed.profileFingerprint,
    true,
  )).toMatchObject({ applied: true });

  const deleted = await deleteProfile("preview", "Main campaign", null, false);
  expect(deleted).toMatchObject({ operation: "delete", applied: false, preparedDataKept: true });
  expect(await deleteProfile(
    "preview",
    "Main campaign",
    deleted.profileFingerprint,
    true,
  )).toMatchObject({ applied: true, backup: expect.stringContaining("profile-backups") });
});
