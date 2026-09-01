import { afterEach, expect, test } from "vitest";
import {
  browserPreviewScenario,
  checkSetup,
  checkForUpdate,
  deleteProfile,
  getCache,
  getCacheHealth,
  getWireframeHulls,
  getDesktopSmokeProbe,
  getPreparationPlan,
  getProfiles,
  getSnapshot,
  openProjectLink,
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

test("the browser preview draws the same traced hulls the desktop build derives", async () => {
  const catalog = await getWireframeHulls("/Applications/Starsector");
  expect(catalog.format).toBe("preflight-wireframe-hulls-v1");
  expect(catalog.hulls.map((hull) => hull.id)).toEqual([
    "odyssey",
    "onslaught",
    "conquest",
    "paragon",
    "astral",
    "hammerhead",
  ]);
  expect(catalog.hulls.every((hull) => hull.featured)).toBe(true);
  /*
   * The point of this fixture is that it is a trace and not a stand-in for one.
   *
   * The version before it fabricated a "trace" by scaling the collision bounds to 0.52 toward
   * the centre, which drew every ship as a smaller copy of itself floating inside itself. It was
   * labelled as a layout aid, but it meant every visual judgement made in a browser was made
   * against geometry the product never draws. A silhouette that coarse cannot pass these.
   */
  for (const hull of catalog.hulls) {
    expect(hull.bounds.length).toBeGreaterThan(80);
    expect(hull.trace?.inner.length ?? 0).toBeGreaterThan(0);
  }
});

test("running is an explicit preview state", () => {
  useScenario("running");
  expect(browserPreviewScenario()).toBe("running");
});

test("every quiet support link has one fixed destination", async () => {
  const opened = vi.spyOn(window, "open").mockImplementation(() => null);
  const destinations = {
    "tip-patreon": "https://www.patreon.com/cw/teamleaderleo",
  } as const;
  for (const [link, url] of Object.entries(destinations)) {
    await openProjectLink(link as keyof typeof destinations);
    expect(opened).toHaveBeenCalledWith(url, "_blank", "noopener,noreferrer");
  }
  expect(opened).toHaveBeenCalledTimes(Object.keys(destinations).length);
  opened.mockRestore();
});

test("setup, low-disk, and cache recovery previews expose safe failure states", async () => {
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

  useScenario("cache-unsafe");
  expect(await getCacheHealth("preview")).toMatchObject({
    status: "unsafe",
    repairFiles: 0,
  });
});

test("the first-run preview is a detected installation with no prepared profile", async () => {
  useScenario("first-run");

  const snapshot = await getSnapshot();
  const cache = await getCache("/Applications/Starsector");
  const health = await getCacheHealth("/Applications/Starsector");
  const profiles = await getProfiles("/Applications/Starsector");

  expect(snapshot.ready).toBe(true);
  expect(snapshot.selected?.installRoot).toBe("/Applications/Starsector");
  expect(snapshot.playtime).toMatchObject({ totalMillis: 0, launches: 0 });
  expect(cache).toMatchObject({ present: false, total: { bytes: 0, files: 0 }, profiles: [] });
  expect(health).toMatchObject({ status: "cold", preparedTextures: false });
  expect(profiles.profiles).toEqual([]);
});

test("setup analysis previews cover clean and broken mod sets", async () => {
  useScenario("ready");
  expect(await checkSetup("preview")).toMatchObject({
    format: "starsector-preflight-setup-analysis-v1",
    ready: true,
    findings: [],
  });

  useScenario("mod-problems");
  expect(await checkSetup("preview")).toMatchObject({
    ready: false,
    counts: { blocking: 1 },
    findings: [{ code: "mod-metadata.required-dependency-disabled" }],
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
