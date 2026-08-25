import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { vi } from "vitest";
import App from "./App";
import * as bridge from "./bridge";
import type { CacheSnapshot, NamedProfile, ProfileActivationPlan, ProfileList } from "./types";

vi.mock("@tauri-apps/plugin-dialog", () => ({ open: vi.fn(), save: vi.fn() }));
vi.mock("@tauri-apps/api/event", () => ({ listen: vi.fn() }));

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function profile(name: string, fingerprint: string, active: boolean): NamedProfile {
  return {
    name,
    installRoot: "/Applications/Starsector",
    enabledMods: [],
    modCount: 1,
    profileFingerprint: fingerprint,
    savedAt: "2026-08-18T00:00:00Z",
    sameInstall: true,
    active,
    canActivate: true,
    missingMods: [],
    file: `/profiles/${name}.json`,
  };
}

function profiles(mainActive: boolean): ProfileList {
  return {
    format: "starsector-preflight-profile-list-v1",
    installRoot: "/Applications/Starsector",
    enabledMods: mainActive ? ["campaign"] : ["utility"],
    profiles: [
      profile("Main campaign", "old-fingerprint", mainActive),
      profile("Utilities only", "new-fingerprint", !mainActive),
    ],
    diagnostics: [],
  };
}

function externalProfiles(): ProfileList {
  return {
    format: "starsector-preflight-profile-list-v1",
    installRoot: "/Applications/Starsector",
    enabledMods: ["external"],
    profiles: [
      profile("Main campaign", "old-fingerprint", false),
      profile("Utilities only", "new-fingerprint", false),
    ],
    diagnostics: [],
  };
}

function cache(fingerprint: string, prepared: boolean): CacheSnapshot {
  return {
    format: "starsector-preflight-cache-v1",
    root: "~/.starsector-preflight",
    present: true,
    total: { bytes: 1024, files: 3 },
    groups: [],
    uncategorizedBytes: 0,
    currentProfileFingerprint: fingerprint,
    profiles: prepared ? [{
      fingerprint,
      current: true,
      bytes: 1024,
      indexBytes: 128,
      manifestBytes: 256,
      lastModifiedMillis: Date.now(),
    }] : [],
  };
}

function activation(confirmed: boolean): ProfileActivationPlan {
  return {
    format: "starsector-preflight-profile-activation-v1",
    name: "Utilities only",
    installRoot: "/Applications/Starsector",
    savedInstallRoot: "/Applications/Starsector",
    sameInstall: true,
    active: false,
    canActivate: true,
    applied: confirmed,
    enable: ["utility"],
    disable: ["campaign"],
    missingMods: [],
    ...(confirmed ? {
      atomicReplace: true,
      backup: "~/.starsector-preflight/profile-backups/enabled_mods.json",
    } : {}),
  };
}

test("Home reflects the switched profile when its new mod set needs preparation", async () => {
  window.localStorage.clear();
  const getProfiles = vi.spyOn(bridge, "getProfiles")
    .mockResolvedValueOnce(profiles(true))
    .mockResolvedValueOnce(profiles(false));
  const getCache = vi.spyOn(bridge, "getCache")
    .mockResolvedValueOnce(cache("old-fingerprint", true))
    .mockResolvedValueOnce(cache("new-fingerprint", false));
  const activate = vi.spyOn(bridge, "activateProfile")
    .mockImplementation(async (_game, _name, confirmed) => activation(confirmed));

  try {
    const user = userEvent.setup();
    render(<App />);

    await screen.findByRole(
      "button",
      { name: "Launch Starsector" },
      { timeout: 3_000 },
    );
    await user.click(screen.getByRole("button", { name: "Mods" }));
    await screen.findByText("Utilities only");
    await user.click(screen.getByRole("button", { name: "Switch…" }));
    await screen.findByRole("heading", { name: "Switch to Utilities only?" });
    await user.click(screen.getByRole("button", { name: "Apply switch" }));
    await screen.findByText(/Switched to “Utilities only”/);

    await user.click(screen.getByRole("button", { name: "Home" }));

    expect(await screen.findByText("Utilities only", { selector: ".home-launch-identity strong" }))
      .toBeInTheDocument();
    expect(screen.getByLabelText("Installation /Applications/Starsector"))
      .toHaveAttribute("title", "/Applications/Starsector");
    await waitFor(() => expect(screen.getByText("Fast launch")).toBeInTheDocument());
  } finally {
    getProfiles.mockRestore();
    getCache.mockRestore();
    activate.mockRestore();
  }
});

test("refocus revalidates profiles in the background without blanking Home", async () => {
  window.localStorage.clear();
  const profileRefresh = deferred<ProfileList>();
  const cacheRefresh = deferred<CacheSnapshot>();
  const getProfiles = vi.spyOn(bridge, "getProfiles")
    .mockResolvedValueOnce(profiles(true))
    .mockReturnValueOnce(profileRefresh.promise);
  const getCache = vi.spyOn(bridge, "getCache")
    .mockResolvedValueOnce(cache("old-fingerprint", true))
    .mockReturnValueOnce(cacheRefresh.promise);

  try {
    const user = userEvent.setup();
    render(<App />);

    expect(await screen.findByText("Main campaign", { selector: ".home-launch-identity strong" }))
      .toBeInTheDocument();
    expect(screen.getByLabelText("Installation /Applications/Starsector")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Settings" }));

    act(() => window.dispatchEvent(new Event("focus")));
    await waitFor(() => expect(getProfiles).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(getCache).toHaveBeenCalledTimes(2));

    await user.click(screen.getByRole("button", { name: "Home" }));
    expect(screen.getByText("Main campaign", { selector: ".home-launch-identity strong" }))
      .toBeInTheDocument();
    expect(screen.getByLabelText("Installation /Applications/Starsector")).toBeInTheDocument();
    expect(getProfiles).toHaveBeenCalledTimes(2);

    profileRefresh.resolve(externalProfiles());
    cacheRefresh.resolve(cache("external-fingerprint", false));

    await waitFor(() => expect(screen.getByText("Fast launch")).toBeInTheDocument());
    expect(getProfiles).toHaveBeenCalledTimes(2);
    expect(getCache).toHaveBeenCalledTimes(2);
  } finally {
    getProfiles.mockRestore();
    getCache.mockRestore();
  }
});

test("failed refocus revalidation keeps the last usable profile identity", async () => {
  window.localStorage.clear();
  const getProfiles = vi.spyOn(bridge, "getProfiles")
    .mockResolvedValueOnce(profiles(true))
    .mockRejectedValueOnce(new Error("synthetic profile refresh failure"));
  const getCache = vi.spyOn(bridge, "getCache")
    .mockResolvedValueOnce(cache("old-fingerprint", true))
    .mockRejectedValueOnce(new Error("synthetic cache refresh failure"));

  try {
    render(<App />);

    expect(await screen.findByText("Main campaign", { selector: ".home-launch-identity strong" }))
      .toBeInTheDocument();
    expect(screen.getByLabelText("Installation /Applications/Starsector")).toBeInTheDocument();

    act(() => window.dispatchEvent(new Event("focus")));

    await waitFor(() => expect(getProfiles).toHaveBeenCalledTimes(2));
    expect(screen.getByText("Main campaign", { selector: ".home-launch-identity strong" }))
      .toBeInTheDocument();
    expect(screen.getByLabelText("Installation /Applications/Starsector")).toBeInTheDocument();
    await waitFor(() => expect(getCache).toHaveBeenCalledTimes(2));
  } finally {
    getProfiles.mockRestore();
    getCache.mockRestore();
  }
});
