import { invoke } from "@tauri-apps/api/core";
import desktopPackage from "../package.json";
import type {
  CacheHealth,
  CacheRepair,
  CacheSnapshot,
  CacheCleanupPlan,
  DesktopSnapshot,
  DiagnosticsExport,
  DesktopSmokeProbe,
  LaunchSettings,
  LaunchSettingsUpdate,
  NamedProfile,
  OptimizationDomain,
  OptimizationPreset,
  OperationSnapshot,
  ProfileActivationPlan,
  ProfileList,
  ProfileMutationPlan,
  PreparationStoragePlan,
  TextureStorage,
  RemovalPlan,
  RemovalScope,
  ReportDeletion,
  ReportIntakeStatus,
  ReportReceipt,
  RunStarted,
  UpdateStatus,
} from "./types";

declare global {
  interface Window {
    __TAURI_INTERNALS__?: unknown;
  }
}

export type BrowserPreviewScenario =
  | "ready"
  | "setup"
  | "low-disk"
  | "cache-repair"
  | "profile-mismatch"
  | "benchmark-unavailable"
  | "update-error"
  | "report-error"
  | "run-failure";

const browserPreviewScenarios = new Set<BrowserPreviewScenario>([
  "ready",
  "setup",
  "low-disk",
  "cache-repair",
  "profile-mismatch",
  "benchmark-unavailable",
  "update-error",
  "report-error",
  "run-failure",
]);

export function browserPreviewScenario(): BrowserPreviewScenario {
  const requested = new URLSearchParams(window.location.search).get("scenario") as BrowserPreviewScenario | null;
  return requested && browserPreviewScenarios.has(requested) ? requested : "ready";
}

const previewSnapshot: DesktopSnapshot = {
  protocol: 1,
  engineVersion: desktopPackage.version,
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
    name: "Main campaign",
    installRoot: "/Applications/Starsector",
    enabledMods: ["nexerelin", "graphicslib", "uaf"],
    modCount: 83,
    profileFingerprint: "preview-profile",
    savedAt: "2026-08-06T11:42:00Z",
    sameInstall: true,
    active: true,
    canActivate: true,
    missingMods: [],
    file: "~/.starsector-preflight/profiles/main-campaign.json",
  },
  {
    name: "Utilities only",
    installRoot: "/Applications/Starsector",
    enabledMods: ["graphicslib"],
    modCount: 1,
    profileFingerprint: "preview-vanilla-plus",
    savedAt: "2026-08-04T09:12:00Z",
    sameInstall: true,
    active: false,
    canActivate: true,
    missingMods: [],
    file: "~/.starsector-preflight/profiles/utilities-only.json",
  },
];

export function isDesktopHost(): boolean {
  return Boolean(window.__TAURI_INTERNALS__);
}

export async function getSnapshot(game?: string): Promise<DesktopSnapshot> {
  if (!isDesktopHost()) {
    if (browserPreviewScenario() === "setup") {
      return {
        ...previewSnapshot,
        ready: false,
        selected: null,
        diagnostics: [
          "No launcher found. Set STARSECTOR_HOME or use --game/--launcher.",
          "Searched /Applications/Starsector.app (not present)",
          "Searched ~/Applications/Starsector.app (not present)",
          "Searched ~/Games/Starsector.app (no launcher in it)",
        ],
      };
    }
    return previewSnapshot;
  }
  return invoke<DesktopSnapshot>("get_snapshot", { game: game ?? null });
}

export async function getOperationState(): Promise<OperationSnapshot> {
  if (!isDesktopHost()) {
    return {
      format: "preflight-operation-state-v1",
      gamePid: null,
      desktopSmokePid: null,
      desktopSmokeRunDirectory: null,
      preparationPid: null,
      reportUploadId: null,
      reportUploadTotalBytes: null,
      updateInstalling: false,
    };
  }
  return invoke<OperationSnapshot>("get_operation_state");
}

export async function getDesktopSmokeProbe(): Promise<DesktopSmokeProbe> {
  if (!isDesktopHost()) {
    if (browserPreviewScenario() === "benchmark-unavailable") {
      return {
        protocol: 1,
        probe: {
          ready: false,
          driver: null,
          diagnostics: ["This build can’t run the startup benchmark. Reinstall Preflight or create a support ZIP below."],
        },
      };
    }
    return {
      protocol: 1,
      probe: {
        ready: true,
        driver: {
          id: "runtime-semantic-state",
          version: 1,
          platform: "preview",
          capabilities: ["process-control", "semantic-state"],
        },
        diagnostics: [],
      },
    };
  }
  return invoke<DesktopSmokeProbe>("get_desktop_smoke_probe");
}

export async function startDesktopSmoke(game: string): Promise<RunStarted> {
  if (!isDesktopHost()) {
    await new Promise((resolve) => window.setTimeout(resolve, 350));
    return { pid: 4244 };
  }
  return invoke<RunStarted>("start_desktop_smoke", { game });
}

export async function cancelDesktopSmoke(): Promise<boolean> {
  if (!isDesktopHost()) return true;
  return invoke<boolean>("cancel_desktop_smoke");
}

/*
 * The run-failure card is the app's recovery path, and it is driven by a desktop-only `run-state`
 * event -- so until this scenario existed it could not be opened in the browser preview and no
 * test could reach it. The one screen a player sees on their worst day was the one screen nobody
 * could look at.
 */
export function previewRunFailure(): { summary: string; detail?: string } | null {
  if (isDesktopHost() || browserPreviewScenario() !== "run-failure") return null;
  return {
    summary: "Starsector stopped before reaching the main menu.",
    detail: "java.lang.RuntimeException: preview failure detail",
  };
}

export async function startGame(
  game: string,
  optimizationPreset: OptimizationPreset,
  disabledOptimizationDomains: OptimizationDomain[],
): Promise<RunStarted> {
  if (!isDesktopHost()) {
    await new Promise((resolve) => window.setTimeout(resolve, 350));
    return { pid: 4242 };
  }
  return invoke<RunStarted>("start_game", { game, optimizationPreset, disabledOptimizationDomains });
}

export async function getCache(game: string): Promise<CacheSnapshot> {
  if (!isDesktopHost()) {
    if (browserPreviewScenario() === "low-disk") {
      return {
        format: "starsector-preflight-cache-v1",
        root: "~/.starsector-preflight",
        present: true,
        total: { bytes: 536_870_912, files: 1_204 },
        groups: [{ id: "acceleration", bytes: 536_870_912, files: 1_204 }],
        uncategorizedBytes: 0,
        currentProfileFingerprint: "preview-profile",
        profiles: [],
      };
    }
    return {
      format: "starsector-preflight-cache-v1",
      root: "~/.starsector-preflight",
      present: true,
      total: { bytes: 4_831_838_208, files: 31_204 },
      groups: [
        { id: "acceleration", bytes: 3_758_096_384, files: 30_422 },
        { id: "evidence", bytes: 1_073_741_824, files: 782 },
      ],
      uncategorizedBytes: 0,
      currentProfileFingerprint: "preview-profile",
      profiles: [{
        fingerprint: "preview-profile",
        current: true,
        bytes: 2_427_125_760,
        indexBytes: 18_874_368,
        manifestBytes: 7_340_032,
        lastModifiedMillis: Date.now(),
      }],
    };
  }
  return invoke<CacheSnapshot>("get_cache", { game });
}

export async function getCacheHealth(game: string): Promise<CacheHealth> {
  if (!isDesktopHost()) {
    if (browserPreviewScenario() === "cache-repair") {
      return {
        format: "starsector-preflight-cache-health-v1",
        status: "repair-needed",
        profileFingerprint: "preview-profile",
        issues: [{
          artifact: "prepared-textures",
          summary: "Prepared texture metadata is incomplete.",
          path: "~/.starsector-preflight/cache/manifests/preview-profile.json",
        }],
        repairBytes: 18_874_368,
        repairFiles: 3,
      };
    }
    return {
      format: "starsector-preflight-cache-health-v1",
      status: "ready",
      profileFingerprint: "preview-profile",
      issues: [],
      repairBytes: 0,
      repairFiles: 0,
    };
  }
  return invoke<CacheHealth>("get_cache_health", { game });
}

export async function repairCache(game: string, expectedProfile: string): Promise<CacheRepair> {
  if (!isDesktopHost()) {
    return {
      format: "starsector-preflight-cache-repair-v1",
      safe: true,
      applied: true,
      status: "cold",
      profileFingerprint: "preview-profile",
      bytes: 0,
      files: 0,
      targets: [],
    };
  }
  return invoke<CacheRepair>("repair_cache", { game, expectedProfile });
}

export async function exportDiagnostics(output: string): Promise<DiagnosticsExport> {
  if (!isDesktopHost()) {
    await new Promise((resolve) => window.setTimeout(resolve, 300));
    return {
      format: "starsector-preflight-diagnostics-export-v1",
      output,
      bytes: 184_320,
      sha256: "4bd6db450a131978b8f8b79d5f08d6e75670ba7e75288bb50f9a742a6d996d8d",
      files: 14,
      runs: 3,
      benchmarks: 2,
      included: [
        { entry: "runs/1/run.json", bytes: 1_024, sha256: "1".repeat(64) },
        { entry: "runs/1/adapter-health.json", bytes: 512, sha256: "2".repeat(64) },
        { entry: "benchmarks/1/results.jsonl", bytes: 2_048, sha256: "3".repeat(64) },
      ],
      skipped: [],
    };
  }
  return invoke<DiagnosticsExport>("export_diagnostics", { output });
}

export async function getReportIntakeStatus(): Promise<ReportIntakeStatus> {
  if (!isDesktopHost()) {
    return { configured: true, origin: "https://reports.preview.invalid", reason: null };
  }
  return invoke<ReportIntakeStatus>("get_report_intake_status");
}

export async function sendRunReport(report: DiagnosticsExport): Promise<ReportReceipt> {
  if (!isDesktopHost()) {
    if (browserPreviewScenario() === "report-error") {
      throw new Error("The preview report service is temporarily unavailable.");
    }
    await new Promise((resolve) => window.setTimeout(resolve, 500));
    const caseId = "ed6ca0c8-0417-45e5-864f-557680b00590";
    return {
      protocolVersion: 1,
      caseId,
      objectKey: `accepted/${caseId}.zip`,
      bytes: report.bytes,
      sha256: report.sha256,
      productVersion: "preview",
      receivedAt: "2026-08-07T06:30:00.000Z",
      retentionDeadline: "2026-08-22T06:30:00.000Z",
      deletion: {
        method: "DELETE",
        url: `https://reports.preview.invalid/v1/cases/${caseId}`,
        token: "preview.deletion",
      },
      signature: "preview-signature",
    };
  }
  return invoke<ReportReceipt>("send_run_report", {
    report: { output: report.output, bytes: report.bytes, sha256: report.sha256 },
  });
}

export async function cancelRunReport(): Promise<boolean> {
  if (!isDesktopHost()) return true;
  return invoke<boolean>("cancel_run_report");
}

export async function deleteRunReport(deletion: ReportDeletion): Promise<boolean> {
  if (!isDesktopHost()) {
    await new Promise((resolve) => window.setTimeout(resolve, 200));
    return true;
  }
  return invoke<boolean>("delete_run_report", { deletion });
}

export async function getLaunchSettings(game: string): Promise<LaunchSettings> {
  if (!isDesktopHost()) {
    return {
      format: "starsector-preflight-launch-settings-v1",
      directLaunchAvailable: true,
      reason: null,
      settings: {
        resolution: "1440x932",
        fullscreen: false,
        sound: true,
        javaOptions: [],
      },
      preferences: {
        resolution: "1440x932",
        fullscreen: false,
        sound: true,
        antialiasingSamples: 0,
        uiScale: 1,
        battleSize: 400,
        diagnostics: [],
      },
      limits: {
        antialiasingSamples: [0, 2, 4, 8, 12, 16],
        uiScaleMin: 1,
        uiScaleMax: 3,
        uiScaleStep: 0.05,
        battleSizeMin: 200,
        battleSizeDefault: 400,
        battleSizeMax: 400,
        battleSizeExtendedMax: 2000,
        diagnostics: [],
      },
      memory: {
        available: true,
        editable: true,
        maxHeapMiB: 6144,
        initialHeapMiB: 6144,
        source: "/Applications/Starsector.app/Contents/MacOS/starsector_mac.sh",
        sourceKind: "launcher",
        reason: null,
        diagnostics: [],
        backup: null,
      },
      changed: false,
      backup: null,
    };
  }
  return invoke<LaunchSettings>("get_launch_settings", { game });
}

export async function updateLaunchSettings(
  game: string,
  settings: LaunchSettingsUpdate,
): Promise<LaunchSettings> {
  if (!isDesktopHost()) {
    const current = await getLaunchSettings(game);
    return {
      ...current,
      preferences: { ...current.preferences, ...settings },
      memory: {
        ...current.memory,
        maxHeapMiB: settings.memoryMiB ?? current.memory.maxHeapMiB,
        initialHeapMiB: settings.memoryMiB ?? current.memory.initialHeapMiB,
        backup: settings.memoryMiB === current.memory.maxHeapMiB
          ? null
          : "~/.starsector-preflight/launcher-file-backups/preview-starsector_mac.sh",
      },
      changed: true,
      backup: "~/.starsector-preflight/launcher-preference-backups/preview.json",
    };
  }
  return invoke<LaunchSettings>("update_launch_settings", { game, settings });
}

export async function getProfiles(game: string): Promise<ProfileList> {
  if (!isDesktopHost()) {
    const profiles = browserPreviewScenario() === "profile-mismatch"
      ? previewProfiles.map((profile) => profile.name === "Utilities only"
        ? { ...profile, canActivate: false, missingMods: ["graphicslib"] }
        : profile)
      : previewProfiles;
    return {
      format: "starsector-preflight-profile-list-v1",
      installRoot: game,
      enabledMods: previewProfiles[0].enabledMods,
      profiles,
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

export async function renameProfile(
  game: string,
  name: string,
  newName: string,
  expectedProfile: string | null,
  confirmed: boolean,
): Promise<ProfileMutationPlan> {
  if (!isDesktopHost()) {
    const profile = previewProfiles.find((candidate) => candidate.name === name) ?? previewProfiles[0];
    return {
      format: "starsector-preflight-profile-mutation-v1",
      operation: "rename",
      name: profile.name,
      targetName: newName,
      profileFingerprint: profile.profileFingerprint,
      active: profile.active,
      modCount: profile.modCount,
      applied: confirmed && expectedProfile === profile.profileFingerprint,
      preparedDataKept: true,
    };
  }
  return invoke<ProfileMutationPlan>("rename_profile", {
    game,
    name,
    newName,
    expectedProfile,
    confirmed,
  });
}

export async function deleteProfile(
  game: string,
  name: string,
  expectedProfile: string | null,
  confirmed: boolean,
): Promise<ProfileMutationPlan> {
  if (!isDesktopHost()) {
    const profile = previewProfiles.find((candidate) => candidate.name === name) ?? previewProfiles[0];
    return {
      format: "starsector-preflight-profile-mutation-v1",
      operation: "delete",
      name: profile.name,
      targetName: null,
      profileFingerprint: profile.profileFingerprint,
      active: profile.active,
      modCount: profile.modCount,
      applied: confirmed && expectedProfile === profile.profileFingerprint,
      preparedDataKept: true,
      ...(confirmed ? { backup: "~/.starsector-preflight/profile-backups/deleted-profile.json" } : {}),
    };
  }
  return invoke<ProfileMutationPlan>("delete_profile", {
    game,
    name,
    expectedProfile,
    confirmed,
  });
}

export async function startPreparation(
  game: string,
  textureStorage: TextureStorage,
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

export async function cancelPreparation(): Promise<boolean> {
  if (!isDesktopHost()) return true;
  return invoke<boolean>("cancel_preparation");
}

export async function getPreparationPlan(
  game: string,
  textureStorage: Exclude<TextureStorage, "minimal">,
  workers: number,
): Promise<PreparationStoragePlan> {
  if (!isDesktopHost()) {
    await new Promise((resolve) => window.setTimeout(resolve, 100));
    const lowDisk = browserPreviewScenario() === "low-disk";
    return {
      format: "preflight-preparation-storage-plan-v1",
      profileFingerprint: "preview-profile",
      textureStorage,
      cacheDirectory: "~/.starsector-preflight/cache",
      packPath: "~/.starsector-preflight/cache/packs/preview.spfp",
      candidateEntries: 32_920,
      hashedEntries: 32_920,
      uniqueContent: 30_639,
      supportedContent: 30_639,
      unsupportedContent: 0,
      failedContent: 0,
      uniqueSourceBytes: 1_344_722_319,
      uniquePixelBytes: 5_331_135_254,
      reusableLooseBytes: 0,
      predictedLooseBytes: textureStorage === "balanced" ? 2_255_699_674 : 5_331_200_000,
      upperLooseBytes: textureStorage === "balanced" ? 5_600_000_000 : 5_331_200_000,
      predictedPackBytes: textureStorage === "balanced" ? 2_258_964_304 : 5_335_000_000,
      upperPackBytes: 5_600_000_000,
      predictedMetadataBytes: 33_554_432,
      upperMetadataBytes: 134_217_728,
      predictedAdditionalBytes: textureStorage === "balanced" ? 4_548_218_410 : 10_699_754_432,
      upperBoundAdditionalBytes: textureStorage === "balanced" ? 11_334_217_728 : 11_065_217_728,
      safetyReserveBytes: 1_133_421_772,
      requiredFreeBytes: 12_467_639_500,
      usableBytes: lowDisk ? 2_147_483_648 : 82_000_000_000,
      remainingAfterUpperBoundBytes: lowDisk ? -9_186_734_080 : 70_665_782_272,
      packHit: false,
      complete: true,
      safeToPrepare: !lowDisk,
      refusalReason: lowDisk ? "Preparation needs up to 11.6 GB; only 2.0 GB is available after the safety reserve." : null,
      diagnostics: [],
      durationMs: 740,
    };
  }
  return invoke<PreparationStoragePlan>("get_preparation_plan", {
    game,
    textureStorage,
    workers,
  });
}

export async function getCacheCleanup(game: string): Promise<CacheCleanupPlan> {
  if (!isDesktopHost()) {
    await new Promise((resolve) => window.setTimeout(resolve, 120));
    return {
      format: "starsector-preflight-cache-prune-v1",
      safe: true,
      applied: false,
      currentProfileFingerprint: "preview-profile",
      survivingProfileFingerprints: ["preview-profile"],
      bytes: 1_842_884_608,
      files: 8_914,
      reachableTextureBlobs: 30_639,
      reachablePreparedAudioBlobs: 412,
      refusals: [],
      groups: [
        { reason: "unused profile artifact", bytes: 1_245_118_464, files: 5 },
        { reason: "unreferenced blob", bytes: 597_766_144, files: 8_909 },
      ],
      removals: [],
      removalsTruncated: false,
    };
  }
  return invoke<CacheCleanupPlan>("get_cache_cleanup", { game });
}

export async function applyCacheCleanup(game: string): Promise<CacheCleanupPlan> {
  if (!isDesktopHost()) {
    return { ...(await getCacheCleanup(game)), applied: true };
  }
  return invoke<CacheCleanupPlan>("apply_cache_cleanup", { game });
}

export async function getRemovalPlan(scope: RemovalScope): Promise<RemovalPlan> {
  if (!isDesktopHost()) {
    await new Promise((resolve) => window.setTimeout(resolve, 100));
    const allData = scope === "all-data";
    return {
      format: "preflight-removal-v1",
      scope,
      safe: true,
      applied: false,
      bytes: allData ? 4_831_838_208 : 8_388_608,
      files: allData ? 31_204 : 4,
      targets: allData
        ? [{ kind: "preflight-data", label: "Preflight data", path: "~/.starsector-preflight", bytes: 4_831_838_208, files: 31_204 }]
        : [{ kind: "launch-integration", label: "macOS launcher app", path: "~/Applications/Preflight.app", bytes: 8_388_608, files: 4 }],
      refusals: [],
      preserves: ["Starsector installation", "mods", "saves", "game-owned settings"],
    };
  }
  return invoke<RemovalPlan>("get_removal_plan", { scope });
}

export async function applyRemoval(scope: RemovalScope): Promise<RemovalPlan> {
  if (!isDesktopHost()) return { ...(await getRemovalPlan(scope)), applied: true };
  return invoke<RemovalPlan>("apply_removal", { scope });
}

export type ProjectLink = "project" | "getting-started" | "privacy" | "capabilities" | "report-issue" | "support";

/**
 * Opens one of Preflight's own pages in the system browser.
 *
 * The host takes a key rather than a URL and holds the addresses itself, so nothing reachable from
 * the page can widen what this opens. In the browser preview there is no host, and a new tab is the
 * honest equivalent.
 */
export async function openProjectLink(link: ProjectLink): Promise<void> {
  if (!isDesktopHost()) {
    window.open(PREVIEW_PROJECT_LINKS[link], "_blank", "noopener,noreferrer");
    return;
  }
  await invoke<void>("open_project_link", { link });
}

const PREVIEW_PROJECT_LINKS: Record<ProjectLink, string> = {
  project: "https://github.com/teamleaderleo/preflight",
  "getting-started": "https://github.com/teamleaderleo/preflight/blob/main/docs/getting-started.md",
  privacy: "https://github.com/teamleaderleo/preflight/blob/main/docs/privacy.md",
  capabilities: "https://github.com/teamleaderleo/preflight/blob/main/docs/capability-receipt.md",
  "report-issue": "https://github.com/teamleaderleo/preflight/issues/new",
  support: "https://www.patreon.com/cw/teamleaderleo",
};

export async function checkForUpdate(): Promise<UpdateStatus> {
  if (!isDesktopHost()) {
    await new Promise((resolve) => window.setTimeout(resolve, 120));
    if (browserPreviewScenario() === "update-error") {
      throw new Error("The preview update service is temporarily unavailable.");
    }
    return {
      format: "preflight-update-v1",
      configured: false,
      currentVersion: "0.1.0",
      available: false,
      version: null,
      date: null,
      notes: null,
      reason: "Browser preview has no verified update channel.",
    };
  }
  return invoke<UpdateStatus>("check_for_update");
}

export async function installUpdate(version: string): Promise<void> {
  if (!isDesktopHost()) return;
  return invoke<void>("install_update", { requestedVersion: version });
}
