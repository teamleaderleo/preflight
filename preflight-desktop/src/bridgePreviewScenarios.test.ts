import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
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

test("every quiet support link has one fixed destination", async () => {
  const opened = vi.spyOn(window, "open").mockImplementation(() => null);
  const destinations = {
    "tip-coffee": "https://buymeacoffee.com/teamleaderleo",
    "tip-kofi": "https://ko-fi.com/teamleaderleo",
    "tip-patreon": "https://www.patreon.com/cw/teamleaderleo",
  } as const;
  for (const [link, url] of Object.entries(destinations)) {
    await openProjectLink(link as keyof typeof destinations);
    expect(opened).toHaveBeenCalledWith(url, "_blank", "noopener,noreferrer");
  }
  expect(opened).toHaveBeenCalledTimes(3);
  opened.mockRestore();
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

/*
 * Every preview fixture in bridge.ts sits behind a hand-written `isDesktopHost()` check, thirty-odd
 * times over. Miss one and the shipped app serves an invented answer to a real person: a game
 * installation that was never found, a benchmark that was never run, a cleanup plan measured from
 * nothing. Nothing in the type system or the runtime says that guard is required, and the failure
 * is silent in exactly the build where it matters, because the desktop host is the one place the
 * browser preview never runs.
 *
 * So the requirement is checked structurally instead of remembered: any function reaching for a
 * preview scenario has to establish it is not the desktop host first.
 */
test("no preview fixture can be reached without first ruling out the desktop host", async () => {
  // vitest serves this module over http, so import.meta.url is not a file path here.
  const source = await readFile(resolve(process.cwd(), "src/bridge.ts"), "utf8");
  // Top-level declarations only: nested helpers inherit their parent's guard.
  const declarations = [...source.matchAll(/^export (?:async )?function (\w+)/gm)];
  expect(declarations.length).toBeGreaterThan(20);

  const unguarded: string[] = [];
  for (const [index, declaration] of declarations.entries()) {
    const name = declaration[1];
    // `browserPreviewScenario` is the accessor itself, so it cannot be asked to guard against it.
    if (name === "browserPreviewScenario" || name === "isDesktopHost") continue;
    const start = declaration.index;
    const end = declarations[index + 1]?.index ?? source.length;
    const body = source.slice(start, end);
    const scenario = body.indexOf("browserPreviewScenario(");
    if (scenario === -1) continue;
    const guard = body.indexOf("isDesktopHost()");
    if (guard === -1 || guard > scenario) unguarded.push(name);
  }

  expect(unguarded).toEqual([]);
});
