import { invoke } from "@tauri-apps/api/core";
import type {
  CacheInspection,
  DesktopHomeState,
  DesktopSnapshot,
  LaunchSettings,
  ModReadiness,
  ProfileList,
} from "./types";

vi.mock("@tauri-apps/api/core", () => ({ invoke: vi.fn() }));

const cacheInspection = { format: "starsector-preflight-cache-inspection-v1" } as CacheInspection;
const profiles = { format: "starsector-preflight-profile-list-v1" } as ProfileList;
const launchSettings = { format: "starsector-preflight-launch-settings-v1" } as LaunchSettings;
const modReadiness: ModReadiness = {
  format: "starsector-preflight-mod-readiness-v1",
  ready: true,
  counts: { blocking: 0, warning: 0, info: 0, unknown: 0 },
  findings: [],
  modDirectories: 83,
  metadataBytes: 131_072,
  elapsedMillis: 6,
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((next) => { resolve = next; });
  return { promise, resolve };
}

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

test("the first home reads share one engine process", async () => {
  const state: DesktopHomeState = {
    format: "starsector-preflight-desktop-home-state-v1",
    installRoot: "/game",
    cacheInspection,
    profiles,
    launchSettings,
    modReadiness,
    errors: {},
  };
  vi.mocked(invoke).mockResolvedValue(state);
  const bridge = await import("./bridge");

  await expect(Promise.all([
    bridge.getCacheInspection("/game"),
    bridge.getProfiles("/game"),
    bridge.getLaunchSettings("/game"),
    bridge.getModReadiness("/game"),
  ])).resolves.toEqual([cacheInspection, profiles, launchSettings, modReadiness]);

  expect(invoke).toHaveBeenCalledTimes(1);
  expect(invoke).toHaveBeenCalledWith("get_home_state", { game: "/game" });
});

test("installation confirmation does not wait for heavier Home data", async () => {
  const state: DesktopHomeState = {
    format: "starsector-preflight-desktop-home-state-v1",
    installRoot: "/game",
    cacheInspection,
    profiles,
    launchSettings,
    modReadiness,
    errors: {},
  };
  const snapshot = {
    selected: { installRoot: "/game" },
  } as DesktopSnapshot;
  const pendingHome = deferred<DesktopHomeState>();
  vi.mocked(invoke).mockImplementation((command) =>
    (command === "get_snapshot" ? Promise.resolve(snapshot) : pendingHome.promise) as never);
  const bridge = await import("./bridge");

  await expect(bridge.getBootstrapSnapshot()).resolves.toBe(snapshot);
  expect(invoke).toHaveBeenCalledTimes(1);
  expect(invoke).toHaveBeenCalledWith("get_snapshot", { game: null });
  const home = Promise.all([
    bridge.getCacheInspection("/game"),
    bridge.getProfiles("/game"),
    bridge.getLaunchSettings("/game"),
    bridge.getModReadiness("/game"),
  ]);
  pendingHome.resolve(state);
  await expect(home).resolves.toEqual([cacheInspection, profiles, launchSettings, modReadiness]);

  expect(invoke).toHaveBeenCalledTimes(2);
  expect(invoke).toHaveBeenCalledWith("get_home_state", { game: "/game" });
});

test("concurrent bootstrap reads share one engine process", async () => {
  const snapshot = { selected: null } as DesktopSnapshot;
  vi.mocked(invoke).mockResolvedValue(snapshot);
  const bridge = await import("./bridge");

  await expect(Promise.all([
    bridge.getBootstrapSnapshot(),
    bridge.getBootstrapSnapshot(),
  ])).resolves.toEqual([snapshot, snapshot]);

  expect(invoke).toHaveBeenCalledTimes(1);
});

test("a stale bootstrap cannot replace a newer installation's home state", async () => {
  const older = deferred<unknown>();
  const newer = deferred<unknown>();
  vi.mocked(invoke).mockImplementation((command, args) => {
    if (command === "get_home_state") return Promise.resolve(newerState) as never;
    const game = (args as { game: string }).game;
    return (game === "/older" ? older.promise : newer.promise) as never;
  });
  const bridge = await import("./bridge");
  const oldRequest = bridge.getBootstrapSnapshot("/older");
  const newRequest = bridge.getBootstrapSnapshot("/newer");
  const newerState: DesktopHomeState = {
    format: "starsector-preflight-desktop-home-state-v1",
    installRoot: "/newer",
    cacheInspection,
    profiles,
    launchSettings,
    modReadiness,
    errors: {},
  };
  const newerSnapshot = { selected: { installRoot: "/newer" } } as DesktopSnapshot;
  newer.resolve(newerSnapshot);
  await expect(newRequest).resolves.toBe(newerSnapshot);

  const olderSnapshot = { selected: { installRoot: "/older" } } as DesktopSnapshot;
  older.resolve(olderSnapshot);
  await expect(oldRequest).resolves.toBe(olderSnapshot);
  await expect(bridge.getProfiles("/newer")).resolves.toBe(profiles);

  expect(invoke).toHaveBeenCalledTimes(3);
});

test("later refreshes keep their narrow read contracts", async () => {
  const state: DesktopHomeState = {
    format: "starsector-preflight-desktop-home-state-v1",
    installRoot: "/game",
    cacheInspection,
    profiles,
    launchSettings,
    modReadiness,
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
    bridge.getModReadiness("/game"),
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
    modReadiness,
    errors: { profiles: "enabled_mods.json is unreadable" },
  };
  vi.mocked(invoke).mockResolvedValue(state);
  const bridge = await import("./bridge");

  await expect(bridge.getProfiles("/broken")).rejects.toThrow("enabled_mods.json is unreadable");
});
