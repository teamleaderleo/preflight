import { act, renderHook, waitFor } from "@testing-library/react";
import * as bridge from "./bridge";
import { usePreparation } from "./usePreparation";
import { useProfiles } from "./useProfiles";
import type { CacheSnapshot, ProfileList } from "./types";

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
