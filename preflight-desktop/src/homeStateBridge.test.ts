import { invoke } from "@tauri-apps/api/core";
import type { CacheInspection, DesktopHomeState, DesktopSnapshot, LaunchSettings, ProfileList } from "./types";

vi.mock("@tauri-apps/api/core", () => ({ invoke: vi.fn() }));

const cacheInspection = { format: "starsector-preflight-cache-inspection-v1" } as CacheInspection;
const profiles = { format: "starsector-preflight-profile-list-v1" } as ProfileList;
const launchSettings = { format: "starsector-preflight-launch-settings-v1" } as LaunchSettings;

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

test("desktop bootstrap primes the first screen without a second engine process", async () => {
  const state: DesktopHomeState = {
    format: "starsector-preflight-desktop-home-state-v1",
    installRoot: "/game",
    cacheInspection,
    profiles,
    launchSettings,
    errors: {},
  };
  const snapshot = {
    selected: { installRoot: "/game" },
  } as DesktopSnapshot;
  vi.mocked(invoke).mockResolvedValue({
    format: "starsector-preflight-desktop-bootstrap-v1",
    snapshot,
    homeState: state,
    homeStateError: null,
  });
  const bridge = await import("./bridge");

  await expect(bridge.getBootstrapSnapshot()).resolves.toBe(snapshot);
  await expect(Promise.all([
    bridge.getCacheInspection("/game"),
    bridge.getProfiles("/game"),
    bridge.getLaunchSettings("/game"),
  ])).resolves.toEqual([cacheInspection, profiles, launchSettings]);

  expect(invoke).toHaveBeenCalledTimes(1);
  expect(invoke).toHaveBeenCalledWith("get_bootstrap", { game: null });
});

test("concurrent bootstrap reads share one engine process", async () => {
  const snapshot = { selected: null } as DesktopSnapshot;
  vi.mocked(invoke).mockResolvedValue({
    format: "starsector-preflight-desktop-bootstrap-v1",
    snapshot,
    homeState: null,
    homeStateError: null,
  });
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
  vi.mocked(invoke).mockImplementation((_command, args) => {
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
    errors: {},
  };
  const newerSnapshot = { selected: { installRoot: "/newer" } } as DesktopSnapshot;
  newer.resolve({
    format: "starsector-preflight-desktop-bootstrap-v1",
    snapshot: newerSnapshot,
    homeState: newerState,
    homeStateError: null,
  });
  await expect(newRequest).resolves.toBe(newerSnapshot);

  const olderSnapshot = { selected: { installRoot: "/older" } } as DesktopSnapshot;
  older.resolve({
    format: "starsector-preflight-desktop-bootstrap-v1",
    snapshot: olderSnapshot,
    homeState: { ...newerState, installRoot: "/older" },
    homeStateError: null,
  });
  await expect(oldRequest).resolves.toBe(olderSnapshot);
  await expect(bridge.getProfiles("/newer")).resolves.toBe(profiles);

  expect(invoke).toHaveBeenCalledTimes(2);
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
