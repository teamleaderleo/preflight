import { invoke } from "@tauri-apps/api/core";
import type { CacheInspection, DesktopHomeState, LaunchSettings, ProfileList } from "./types";

vi.mock("@tauri-apps/api/core", () => ({ invoke: vi.fn() }));

const cacheInspection = { format: "starsector-preflight-cache-inspection-v1" } as CacheInspection;
const profiles = { format: "starsector-preflight-profile-list-v1" } as ProfileList;
const launchSettings = { format: "starsector-preflight-launch-settings-v1" } as LaunchSettings;

beforeEach(() => {
  vi.resetModules();
  vi.mocked(invoke).mockReset();
  Object.defineProperty(window, "__TAURI_INTERNALS__", {
    configurable: true,
    value: {},
  });
});

afterEach(() => {
  Reflect.deleteProperty(window, "__TAURI_INTERNALS__");
});

test("the first three home reads share one engine process", async () => {
  const state: DesktopHomeState = {
    format: "starsector-preflight-desktop-home-state-v1",
    installRoot: "/game",
    cacheInspection,
    profiles,
    launchSettings,
    errors: {},
  };
  vi.mocked(invoke).mockResolvedValue(state);
  const bridge = await import("./bridge");

  await expect(Promise.all([
    bridge.getCacheInspection("/game"),
    bridge.getProfiles("/game"),
    bridge.getLaunchSettings("/game"),
  ])).resolves.toEqual([cacheInspection, profiles, launchSettings]);

  expect(invoke).toHaveBeenCalledTimes(1);
  expect(invoke).toHaveBeenCalledWith("get_home_state", { game: "/game" });
});

test("later refreshes keep their narrow read contracts", async () => {
  const state: DesktopHomeState = {
    format: "starsector-preflight-desktop-home-state-v1",
    installRoot: "/game",
    cacheInspection,
    profiles,
    launchSettings,
    errors: {},
  };
  vi.mocked(invoke)
    .mockResolvedValueOnce(state)
    .mockResolvedValueOnce(cacheInspection);
  const bridge = await import("./bridge");
  await Promise.all([
    bridge.getCacheInspection("/game"),
    bridge.getProfiles("/game"),
    bridge.getLaunchSettings("/game"),
  ]);

  await bridge.getCacheInspection("/game");

  expect(invoke).toHaveBeenLastCalledWith("get_cache_inspection", { game: "/game" });
});

test("a failed family reports its own reason", async () => {
  const state: DesktopHomeState = {
    format: "starsector-preflight-desktop-home-state-v1",
    installRoot: "/broken",
    cacheInspection,
    profiles: null,
    launchSettings,
    errors: { profiles: "enabled_mods.json is unreadable" },
  };
  vi.mocked(invoke).mockResolvedValue(state);
  const bridge = await import("./bridge");

  await expect(bridge.getProfiles("/broken")).rejects.toThrow("enabled_mods.json is unreadable");
});
