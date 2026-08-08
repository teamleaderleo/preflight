import { act, renderHook, waitFor } from "@testing-library/react";
import * as bridge from "./bridge";
import { useCacheCleanup } from "./useCacheCleanup";
import { useLauncherSettings } from "./useLauncherSettings";
import { usePreparation } from "./usePreparation";
import { useProfiles } from "./useProfiles";
import type { CacheCleanupPlan, CacheSnapshot, LaunchSettings, ProfileList } from "./types";

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((finish) => {
    resolve = finish;
  });
  return { promise, resolve };
}

function cacheFor(root: string): CacheSnapshot {
  return {
    format: "starsector-preflight-cache-v1",
    root: `${root}/cache`,
    present: true,
    total: { bytes: 1024, files: 2 },
    groups: [],
    uncategorizedBytes: 0,
    currentProfileFingerprint: "current",
    profiles: [{
      fingerprint: "current",
      current: true,
      bytes: 1024,
      indexBytes: 128,
      manifestBytes: 256,
      lastModifiedMillis: 1,
    }],
  };
}

function profilesFor(root: string): ProfileList {
  return {
    format: "starsector-preflight-profile-list-v1",
    installRoot: root,
    enabledMods: [],
    profiles: [],
    diagnostics: [],
  };
}

test("an older cache read cannot replace the newly selected installation", async () => {
  const first = deferred<CacheSnapshot>();
  const second = deferred<CacheSnapshot>();
  const cache = vi.spyOn(bridge, "getCache")
    .mockImplementationOnce(() => first.promise)
    .mockImplementationOnce(() => second.promise);
  const announce = vi.fn();
  const launch = vi.fn(async () => undefined);
  const { result, rerender } = renderHook(
    ({ game }) => usePreparation(game, false, "off", launch, announce),
    { initialProps: { game: "/game-a" } },
  );

  await waitFor(() => expect(cache).toHaveBeenCalledWith("/game-a"));
  rerender({ game: "/game-b" });
  await waitFor(() => expect(cache).toHaveBeenCalledWith("/game-b"));
  await act(async () => second.resolve(cacheFor("/game-b")));
  await waitFor(() => expect(result.current.cache?.root).toBe("/game-b/cache"));
  await act(async () => first.resolve(cacheFor("/game-a")));

  expect(result.current.cache?.root).toBe("/game-b/cache");
  expect(announce).not.toHaveBeenCalled();
  cache.mockRestore();
});

test("an older profile read cannot flash into the newly selected installation", async () => {
  const first = deferred<ProfileList>();
  const second = deferred<ProfileList>();
  const profiles = vi.spyOn(bridge, "getProfiles")
    .mockImplementationOnce(() => first.promise)
    .mockImplementationOnce(() => second.promise);
  const refreshInstallation = vi.fn(async () => undefined);
  const refreshCache = vi.fn(async () => undefined);
  const announce = vi.fn();
  const { result, rerender } = renderHook(
    ({ game }) => useProfiles(game, true, refreshInstallation, refreshCache, announce),
    { initialProps: { game: "/game-a" } },
  );

  await waitFor(() => expect(profiles).toHaveBeenCalledWith("/game-a"));
  rerender({ game: "/game-b" });
  expect(result.current.profiles).toBeNull();
  await waitFor(() => expect(profiles).toHaveBeenCalledWith("/game-b"));
  await act(async () => second.resolve(profilesFor("/game-b")));
  await waitFor(() => expect(result.current.profiles?.installRoot).toBe("/game-b"));
  await act(async () => first.resolve(profilesFor("/game-a")));

  expect(result.current.profiles?.installRoot).toBe("/game-b");
  expect(announce).not.toHaveBeenCalled();
  profiles.mockRestore();
});

test("launcher settings load once across home and detail views and fence an older installation", async () => {
  const baseline = await bridge.getLaunchSettings("/game-a");
  const settings = vi.spyOn(bridge, "getLaunchSettings").mockResolvedValue(baseline);
  const announce = vi.fn();
  const { result, rerender } = renderHook(
    ({ game, active }) => useLauncherSettings(game, active, announce),
    { initialProps: { game: "/game-a", active: true } },
  );
  await waitFor(() => expect(result.current.settings).not.toBeNull());
  expect(settings).toHaveBeenCalledOnce();
  rerender({ game: "/game-a", active: true });
  expect(settings).toHaveBeenCalledOnce();
  rerender({ game: "/game-a", active: false });
  rerender({ game: "/game-a", active: true });
  expect(settings).toHaveBeenCalledOnce();

  const first = deferred<LaunchSettings>();
  const second = deferred<LaunchSettings>();
  settings.mockClear();
  settings.mockImplementationOnce(() => first.promise)
    .mockImplementationOnce(() => second.promise);
  act(() => {
    void result.current.refresh();
  });
  await waitFor(() => expect(settings).toHaveBeenCalledTimes(1));
  rerender({ game: "/game-b", active: true });
  expect(result.current.settings).toBeNull();
  await waitFor(() => expect(settings).toHaveBeenCalledTimes(2));

  await act(async () => second.resolve({
    ...baseline,
    preferences: { ...baseline.preferences, resolution: "1920x1080" },
  }));
  await waitFor(() => expect(result.current.draft?.resolution).toBe("1920x1080"));
  await act(async () => first.resolve({
    ...baseline,
    preferences: { ...baseline.preferences, resolution: "800x600" },
  }));

  expect(result.current.draft?.resolution).toBe("1920x1080");
  expect(announce).not.toHaveBeenCalled();
  settings.mockRestore();
});

test("a completed settings save preserves edits made while it was in flight", async () => {
  const baseline = await bridge.getLaunchSettings("/game-a");
  const settings = vi.spyOn(bridge, "getLaunchSettings").mockResolvedValue(baseline);
  const saved = deferred<LaunchSettings>();
  const update = vi.spyOn(bridge, "updateLaunchSettings").mockImplementation(() => saved.promise);
  const announce = vi.fn();
  const { result } = renderHook(() => useLauncherSettings("/game-a", true, announce));
  await waitFor(() => expect(result.current.draft).not.toBeNull());

  act(() => result.current.changeDraft({ battleSize: 300 }));
  act(() => {
    void result.current.save();
  });
  await waitFor(() => expect(update).toHaveBeenCalledWith("/game-a", expect.objectContaining({ battleSize: 300 })));
  act(() => result.current.changeDraft({ resolution: "1920x1080" }));
  await act(async () => saved.resolve({
    ...baseline,
    preferences: { ...baseline.preferences, battleSize: 300 },
  }));

  await waitFor(() => expect(result.current.saving).toBe(false));
  expect(result.current.draft?.resolution).toBe("1920x1080");
  expect(result.current.settings?.preferences.battleSize).toBe(300);
  expect(result.current.dirty).toBe(true);
  expect(announce).toHaveBeenLastCalledWith("Game settings saved. Vanilla and Preflight launches will use the same values.");
  settings.mockRestore();
  update.mockRestore();
});

test("a cleanup preview belongs only to the installation that produced it", async () => {
  const baseline = await bridge.getCacheCleanup("/game-a");
  const first = deferred<CacheCleanupPlan>();
  const second = deferred<CacheCleanupPlan>();
  const cleanup = vi.spyOn(bridge, "getCacheCleanup")
    .mockImplementationOnce(() => first.promise)
    .mockImplementationOnce(() => second.promise);
  const announce = vi.fn();
  const refreshCache = vi.fn(async () => undefined);
  const invalidatePlan = vi.fn();
  const { result, rerender } = renderHook(
    ({ game }) => useCacheCleanup(game, announce, refreshCache, invalidatePlan),
    { initialProps: { game: "/game-a" } },
  );

  act(() => {
    void result.current.review();
  });
  await waitFor(() => expect(cleanup).toHaveBeenCalledWith("/game-a"));
  rerender({ game: "/game-b" });
  await waitFor(() => expect(result.current.busy).toBe(false));
  await act(async () => first.resolve(baseline));
  expect(result.current.plan).toBeNull();
  expect(announce).not.toHaveBeenCalled();

  act(() => {
    void result.current.review();
  });
  await waitFor(() => expect(cleanup).toHaveBeenCalledWith("/game-b"));
  await act(async () => second.resolve({ ...baseline, bytes: 42, files: 1 }));
  await waitFor(() => expect(result.current.plan?.bytes).toBe(42));
  expect(announce).toHaveBeenCalledWith("Cleanup is ready to review. Nothing has been removed.");
  cleanup.mockRestore();
});
