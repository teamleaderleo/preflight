import { invoke } from "@tauri-apps/api/core";
import type {
  CacheSnapshot,
  DesktopSnapshot,
  DiagnosticsExport,
  NamedProfile,
  ProfileActivationPlan,
  ProfileList,
  RunStarted,
} from "./types";

declare global {
  interface Window {
    __TAURI_INTERNALS__?: unknown;
  }
}

const previewSnapshot: DesktopSnapshot = {
  protocol: 1,
  engineVersion: "preview",
  platform: "mac",
  ready: true,
  selected: {
    installRoot: "/Applications/Starsector",
    launcher: "/Applications/Starsector/Starsector.app",
    kind: "executable",
    score: 120,
    source: "preview",
  },
  candidates: [],
  diagnostics: [],
  preflightHome: "~/.starsector-preflight",
  cachePresent: false,
  lastRun: null,
};

const previewProfiles: NamedProfile[] = [
  {
    name: "Heavy campaign",
    installRoot: "/Applications/Starsector",
    enabledMods: ["nexerelin", "graphicslib", "uaf"],
    modCount: 83,
    profileFingerprint: "preview-profile",
    savedAt: "2026-08-06T11:42:00Z",
    sameInstall: true,
    active: true,
    canActivate: true,
    missingMods: [],
    file: "~/.starsector-preflight/profiles/heavy-campaign.json",
  },
  {
    name: "Vanilla plus",
    installRoot: "/Applications/Starsector",
    enabledMods: ["graphicslib"],
    modCount: 1,
    profileFingerprint: "preview-vanilla-plus",
    savedAt: "2026-08-04T09:12:00Z",
    sameInstall: true,
    active: false,
    canActivate: true,
    missingMods: [],
    file: "~/.starsector-preflight/profiles/vanilla-plus.json",
  },
];

export function isDesktopHost(): boolean {
  return Boolean(window.__TAURI_INTERNALS__);
}

export async function getSnapshot(game?: string): Promise<DesktopSnapshot> {
  if (!isDesktopHost()) {
    return previewSnapshot;
  }
  return invoke<DesktopSnapshot>("get_snapshot", { game: game ?? null });
}

export async function startGame(game: string): Promise<RunStarted> {
  if (!isDesktopHost()) {
    await new Promise((resolve) => window.setTimeout(resolve, 350));
    return { pid: 4242 };
  }
  return invoke<RunStarted>("start_game", { game });
}

export async function getCache(game: string): Promise<CacheSnapshot> {
  if (!isDesktopHost()) {
    return {
      format: "starsector-preflight-cache-v1",
      root: "~/.starsector-preflight",
      present: true,
      total: { bytes: 4_831_838_208, files: 31_204 },
      groups: [
        { id: "acceleration", bytes: 3_758_096_384, files: 30_422 },
        { id: "evidence", bytes: 1_073_741_824, files: 782 },
      ],
      currentProfileFingerprint: "preview-profile",
      profiles: [{
        fingerprint: "preview-profile",
        current: true,
        bytes: 2_427_125_760,
        lastModifiedMillis: Date.now(),
      }],
    };
  }
  return invoke<CacheSnapshot>("get_cache", { game });
}

export async function exportDiagnostics(output: string): Promise<DiagnosticsExport> {
  if (!isDesktopHost()) {
    await new Promise((resolve) => window.setTimeout(resolve, 300));
    return {
      format: "starsector-preflight-diagnostics-export-v1",
      output,
      bytes: 184_320,
      sha256: "preview-diagnostics-sha256",
      files: 14,
      runs: 3,
      benchmarks: 2,
      included: [],
      skipped: [],
    };
  }
  return invoke<DiagnosticsExport>("export_diagnostics", { output });
}

export async function getProfiles(game: string): Promise<ProfileList> {
  if (!isDesktopHost()) {
    return {
      format: "starsector-preflight-profile-list-v1",
      installRoot: game,
      enabledMods: previewProfiles[0].enabledMods,
      profiles: previewProfiles,
      diagnostics: [],
    };
  }
  return invoke<ProfileList>("get_profiles", { game });
}

export async function saveProfile(game: string, name: string): Promise<NamedProfile> {
  if (!isDesktopHost()) {
    return {
      ...previewProfiles[0],
      name,
      savedAt: new Date().toISOString(),
    };
  }
  return invoke<NamedProfile>("save_profile", { game, name });
}

export async function activateProfile(
  game: string,
  name: string,
  confirmed: boolean,
): Promise<ProfileActivationPlan> {
  if (!isDesktopHost()) {
    const profile = previewProfiles.find((candidate) => candidate.name === name) ?? previewProfiles[1];
    return {
      format: "starsector-preflight-profile-activation-v1",
      name: profile.name,
      installRoot: game,
      savedInstallRoot: profile.installRoot,
      sameInstall: true,
      active: profile.active,
      canActivate: true,
      applied: confirmed && !profile.active,
      enable: profile.active ? [] : ["graphicslib"],
      disable: profile.active ? [] : ["nexerelin", "uaf"],
      missingMods: [],
      ...(confirmed ? { atomicReplace: true, backup: "~/.starsector-preflight/profile-backups/enabled_mods.json" } : {}),
    };
  }
  return invoke<ProfileActivationPlan>("activate_profile", { game, name, confirmed });
}

export async function startPreparation(
  game: string,
  textureStorage: "balanced" | "fastest",
  workers: number,
  memoryMib: number,
): Promise<RunStarted> {
  if (!isDesktopHost()) {
    await new Promise((resolve) => window.setTimeout(resolve, 500));
    return { pid: 4243 };
  }
  return invoke<RunStarted>("start_preparation", {
    game,
    textureStorage,
    workers,
    memoryMib,
  });
}
