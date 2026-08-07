import { useCallback, useEffect, useRef, useState } from "react";
import { open, save as saveFile } from "@tauri-apps/plugin-dialog";
import { listen } from "@tauri-apps/api/event";
import {
  activateProfile,
  applyCacheCleanup,
  applyRemoval,
  cancelDesktopSmoke,
  cancelPreparation,
  cancelRunReport,
  checkForUpdate,
  deleteRunReport,
  exportDiagnostics,
  getCache,
  getCacheCleanup,
  getDesktopSmokeProbe,
  getLaunchSettings,
  getPreparationPlan,
  getRemovalPlan,
  getProfiles,
  getReportIntakeStatus,
  getSnapshot,
  isDesktopHost,
  installUpdate,
  openDesktopAccessibilitySettings,
  saveProfile,
  sendRunReport,
  startGame,
  startDesktopSmoke,
  startPreparation,
  updateLaunchSettings,
} from "./bridge";
import {
  ArrowIcon,
  CheckIcon,
  FolderIcon,
  HomeIcon,
  LayersIcon,
  PlayIcon,
  RefreshIcon,
  SettingsIcon,
  ShieldIcon,
  SparklesIcon,
} from "./icons";
import Logo from "./Logo";
import type {
  AppStatus,
  CacheSnapshot,
  CacheCleanupPlan,
  DesktopSnapshot,
  DiagnosticsExport,
  DesktopSmokeProbe,
  DesktopSmokeStateEvent,
  LaunchSettings,
  LaunchSettingsUpdate,
  OptimizationPreset,
  PreparationStoragePlan,
  PreparationStateEvent,
  PreparationProgressEvent,
  ProfileActivationPlan,
  ProfileList,
  RemovalPlan,
  RemovalScope,
  ReportIntakeStatus,
  ReportReceipt,
  ReportUploadStateEvent,
  RunStateEvent,
  UpdateProgressEvent,
  UpdateStatus,
} from "./types";

type Page = "home" | "launch" | "prepare" | "profiles" | "settings";
type TextureStorage = "balanced" | "fastest";

const optimizationPresets: Array<{
  id: OptimizationPreset;
  label: string;
  description: string;
  badge: string;
}> = [
  {
    id: "recommended",
    label: "Recommended",
    description: "Every live-gated startup and gameplay improvement, including true-size textures.",
    badge: "Default",
  },
  {
    id: "conservative",
    label: "Conservative",
    description: "Portable startup caches with padded textures; no gameplay or mod-specific plans.",
    badge: "Fallback",
  },
  {
    id: "off",
    label: "Off",
    description: "No transforms or profiling. Keep only the wrapper and bounded process report.",
    badge: "Troubleshoot",
  },
];

const REPORT_RECEIPT_STORAGE_KEY = "preflight.reportReceipt";

function savedRunReportReceipt(): ReportReceipt | null {
  try {
    const raw = window.localStorage.getItem(REPORT_RECEIPT_STORAGE_KEY);
    if (!raw) return null;
    const receipt = JSON.parse(raw) as Partial<ReportReceipt>;
    const deadline = typeof receipt.retentionDeadline === "string"
      ? Date.parse(receipt.retentionDeadline)
      : Number.NaN;
    const valid = receipt.protocolVersion === 1
      && typeof receipt.caseId === "string"
      && receipt.caseId.length > 0
      && receipt.objectKey === `accepted/${receipt.caseId}.zip`
      && typeof receipt.bytes === "number"
      && Number.isSafeInteger(receipt.bytes)
      && receipt.bytes > 0
      && typeof receipt.sha256 === "string"
      && /^[0-9a-f]{64}$/.test(receipt.sha256)
      && typeof receipt.productVersion === "string"
      && typeof receipt.receivedAt === "string"
      && Number.isFinite(Date.parse(receipt.receivedAt))
      && Number.isFinite(deadline)
      && deadline > Date.now()
      && receipt.deletion?.method === "DELETE"
      && typeof receipt.deletion.url === "string"
      && typeof receipt.deletion.token === "string"
      && receipt.deletion.token.length > 0
      && typeof receipt.signature === "string"
      && receipt.signature.length > 0;
    if (valid) return receipt as ReportReceipt;
    window.localStorage.removeItem(REPORT_RECEIPT_STORAGE_KEY);
  } catch {
    // A malformed or inaccessible local receipt never becomes a deletion request.
  }
  return null;
}

function savedOptimizationPreset(): OptimizationPreset {
  try {
    const saved = window.localStorage.getItem("preflight.optimizationPreset");
    if (saved === "recommended" || saved === "conservative" || saved === "off") return saved;
  } catch {
    // A locked-down webview may deny storage; the safe product default still applies.
  }
  return "recommended";
}

const resourcePresets = {
  gentle: { workers: 2, memoryMib: 128, label: "Low" },
  balanced: { workers: 4, memoryMib: 256, label: "Balanced" },
  eager: { workers: 8, memoryMib: 512, label: "High" },
} as const;

function pageTitle(page: Page, status: AppStatus, preparing: boolean, isReady: boolean): string {
  if (page === "launch") return "Launch settings";
  if (page === "prepare") return preparing ? "Preparing…" : "Prepare";
  if (page === "profiles") return "Profiles";
  if (page === "settings") return "Settings";
  if (preparing) return "Preparing…";
  if (status === "loading") return "Checking installation…";
  if (status === "running") return "Starsector is running";
  return isReady ? "Ready to launch" : "Choose your Starsector installation";
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes;
  let unit = -1;
  do {
    value /= 1024;
    unit += 1;
  } while (value >= 1024 && unit < units.length - 1);
  return `${value.toFixed(value >= 10 ? 1 : 2)} ${units[unit]}`;
}

function shortPath(path: string): string {
  const normalized = path.replaceAll("\\", "/");
  const parts = normalized.split("/").filter(Boolean);
  if (parts.length <= 3) return path;
  return `…/${parts.slice(-3).join("/")}`;
}

function friendlyPlatform(platform: DesktopSnapshot["platform"]): string {
  return { mac: "macOS", windows: "Windows", linux: "Linux", other: "Desktop" }[platform];
}

function maximumUiScale(resolution: string): number | null {
  const match = /^(\d+)x(\d+)$/.exec(resolution);
  if (!match) return null;
  const width = Number(match[1]);
  const height = Number(match[2]);
  if (width <= 0 || height <= 0) return null;
  return Math.max(1, Math.floor(Math.min(height / 768, width / 1280) * 20) / 20);
}

export function isCurrentProfilePrepared(cache: CacheSnapshot | null): boolean {
  if (!cache?.currentProfileFingerprint) return false;
  return cache.profiles.some((profile) =>
    profile.current
    && profile.fingerprint === cache.currentProfileFingerprint
    && profile.indexBytes > 0
    && profile.manifestBytes > 0);
}

export default function App() {
  const [snapshot, setSnapshot] = useState<DesktopSnapshot | null>(null);
  const [status, setStatus] = useState<AppStatus>("loading");
  const [message, setMessage] = useState("");
  const [page, setPage] = useState<Page>("home");
  const [cache, setCache] = useState<CacheSnapshot | null>(null);
  const [cacheLoading, setCacheLoading] = useState(false);
  const [cleanupPlan, setCleanupPlan] = useState<CacheCleanupPlan | null>(null);
  const [cleanupBusy, setCleanupBusy] = useState(false);
  const [cacheInstallRoot, setCacheInstallRoot] = useState<string | null>(null);
  const [preparing, setPreparing] = useState(false);
  const [preparationCancelling, setPreparationCancelling] = useState(false);
  const [preparationProgress, setPreparationProgress] = useState<PreparationProgressEvent | null>(null);
  const completedPreparationPhases = useRef(new Set<string>());
  const [preparationPlan, setPreparationPlan] = useState<PreparationStoragePlan | null>(null);
  const [preparationPlanLoading, setPreparationPlanLoading] = useState(false);
  const launchAfterPreparation = useRef(false);
  const [textureStorage, setTextureStorage] = useState<TextureStorage>("balanced");
  const [resourcePreset, setResourcePreset] = useState<keyof typeof resourcePresets>("balanced");
  const [optimizationPreset, setOptimizationPreset] = useState<OptimizationPreset>(savedOptimizationPreset);
  const [profiles, setProfiles] = useState<ProfileList | null>(null);
  const [profilesLoading, setProfilesLoading] = useState(false);
  const [profileName, setProfileName] = useState("");
  const [profileBusy, setProfileBusy] = useState(false);
  const [activationPlan, setActivationPlan] = useState<ProfileActivationPlan | null>(null);
  const [diagnosticsBusy, setDiagnosticsBusy] = useState(false);
  const [diagnosticsExport, setDiagnosticsExport] = useState<DiagnosticsExport | null>(null);
  const [reportIntake, setReportIntake] = useState<ReportIntakeStatus | null>(null);
  const [reportReview, setReportReview] = useState(false);
  const [reportUploading, setReportUploading] = useState(false);
  const [reportFinalizing, setReportFinalizing] = useState(false);
  const [reportCancelling, setReportCancelling] = useState(false);
  const [reportUploadedBytes, setReportUploadedBytes] = useState(0);
  const [reportReceipt, setReportReceipt] = useState<ReportReceipt | null>(savedRunReportReceipt);
  const [reportError, setReportError] = useState("");
  const [reportDeleting, setReportDeleting] = useState(false);
  const [desktopSmokeProbe, setDesktopSmokeProbe] = useState<DesktopSmokeProbe | null>(null);
  const [desktopSmokeProbeBusy, setDesktopSmokeProbeBusy] = useState(false);
  const [desktopSmokeReview, setDesktopSmokeReview] = useState(false);
  const [desktopSmokeRunning, setDesktopSmokeRunning] = useState(false);
  const [desktopSmokeRunDirectory, setDesktopSmokeRunDirectory] = useState<string | null>(null);
  const [launcherSettings, setLauncherSettings] = useState<LaunchSettings | null>(null);
  const [launcherDraft, setLauncherDraft] = useState<LaunchSettingsUpdate | null>(null);
  const [launcherSettingsLoading, setLauncherSettingsLoading] = useState(false);
  const [launcherSettingsSaving, setLauncherSettingsSaving] = useState(false);
  const [removalPlan, setRemovalPlan] = useState<RemovalPlan | null>(null);
  const [removalBusy, setRemovalBusy] = useState(false);
  const [updateStatus, setUpdateStatus] = useState<UpdateStatus | null>(null);
  const [updateChecking, setUpdateChecking] = useState(false);
  const [updateInstalling, setUpdateInstalling] = useState(false);
  const [updateProgress, setUpdateProgress] = useState<UpdateProgressEvent | null>(null);
  const [updateError, setUpdateError] = useState("");
  const updateChecked = useRef(false);

  const refresh = useCallback(async (game?: string) => {
    setStatus("loading");
    setMessage("");
    try {
      const next = await getSnapshot(game);
      setSnapshot(next);
      setStatus(next.ready ? "ready" : "setup");
    } catch (error) {
      setStatus("error");
      setMessage(String(error));
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    try {
      if (reportReceipt) {
        window.localStorage.setItem(REPORT_RECEIPT_STORAGE_KEY, JSON.stringify(reportReceipt));
      } else {
        window.localStorage.removeItem(REPORT_RECEIPT_STORAGE_KEY);
      }
    } catch {
      // Receipt copying remains available if a locked-down webview denies local storage.
    }
  }, [reportReceipt]);

  const checkUpdates = useCallback(async (announce = false) => {
    if (updateChecking || updateInstalling) return;
    setUpdateChecking(true);
    setUpdateError("");
    try {
      const result = await checkForUpdate();
      setUpdateStatus(result);
      if (announce) {
        setMessage(result.available
          ? `Preflight ${result.version} is available. Review it before installing.`
          : result.configured
            ? "Preflight is up to date."
            : result.reason ?? "Verified updates aren’t configured in this build.");
      }
    } catch (error) {
      const detail = String(error);
      setUpdateError(detail);
      if (announce) setMessage(detail);
    } finally {
      setUpdateChecking(false);
    }
  }, [updateChecking, updateInstalling]);

  useEffect(() => {
    if (!isDesktopHost() || status !== "ready" || updateChecked.current) return;
    updateChecked.current = true;
    void checkUpdates(false);
  }, [checkUpdates, status]);

  useEffect(() => {
    try {
      window.localStorage.setItem("preflight.optimizationPreset", optimizationPreset);
    } catch {
      // Selection remains valid for this session when persistent storage is unavailable.
    }
  }, [optimizationPreset]);

  useEffect(() => {
    if (!isDesktopHost()) return;
    let stopListening: (() => void) | undefined;
    void listen<RunStateEvent>("run-state", ({ payload }) => {
      if (payload.state === "finished") {
        setStatus(snapshot?.ready ? "ready" : "setup");
        const outcome = payload.success ? "Welcome back. Your run was tucked away safely." : "The game closed with an error. Your run notes are still safe.";
        void refresh(snapshot?.selected?.installRoot).then(() => setMessage(outcome));
      }
    }).then((unlisten) => {
      stopListening = unlisten;
    });
    return () => stopListening?.();
  }, [refresh, snapshot?.ready, snapshot?.selected?.installRoot]);

  useEffect(() => {
    if (page !== "settings" || reportIntake !== null) return;
    let cancelled = false;
    void getReportIntakeStatus()
      .then((status) => {
        if (!cancelled) setReportIntake(status);
      })
      .catch((error) => {
        if (!cancelled) {
          setReportIntake({ configured: false, origin: null, reason: String(error) });
        }
      });
    return () => {
      cancelled = true;
    };
  }, [page, reportIntake]);

  useEffect(() => {
    if (!isDesktopHost()) return;
    let stopListening: (() => void) | undefined;
    void listen<ReportUploadStateEvent>("report-upload-state", ({ payload }) => {
      setReportUploadedBytes(payload.uploadedBytes);
      if (payload.state === "starting" || payload.state === "uploading") {
        setReportFinalizing(false);
      }
      if (payload.state === "finalizing") {
        setReportFinalizing(true);
        setReportCancelling(false);
        setMessage("The archive was accepted. Finishing its signed receipt…");
        return;
      }
      if (payload.state === "cancelling") {
        setReportCancelling(true);
        setMessage(payload.detail ?? "Stopping the report upload…");
        return;
      }
      if (payload.state === "cancelled" || payload.state === "failed") {
        setReportUploading(false);
        setReportFinalizing(false);
        setReportCancelling(false);
        if (payload.state === "failed") setReportError(payload.detail ?? "The report could not be sent.");
        setMessage(payload.detail ?? "The local diagnostics ZIP is unchanged.");
        return;
      }
      if (payload.state === "finished" && payload.receipt) {
        setReportUploading(false);
        setReportFinalizing(false);
        setReportCancelling(false);
        setReportReview(false);
        setReportReceipt(payload.receipt);
      }
    }).then((unlisten) => {
      stopListening = unlisten;
    });
    return () => stopListening?.();
  }, []);

  useEffect(() => {
    if (!isDesktopHost()) return;
    let stopListening: (() => void) | undefined;
    void listen<DesktopSmokeStateEvent>("desktop-smoke-state", ({ payload }) => {
      setDesktopSmokeRunDirectory(payload.runDirectory);
      if (payload.state === "cancelling") {
        setMessage(payload.detail ?? "Stopping the exact game process and sealing its evidence…");
        return;
      }
      if (payload.state !== "finished" && payload.state !== "cancelled") return;
      setDesktopSmokeRunning(false);
      setStatus(snapshot?.ready ? "ready" : "setup");
      const outcome = payload.state === "cancelled"
        ? payload.detail ?? `Automated game test stopped safely. Evidence is in ${shortPath(payload.runDirectory)}.`
        : payload.success
        ? `Automated game test passed. Evidence is in ${shortPath(payload.runDirectory)}.`
        : payload.detail ?? `Automated game test stopped. Evidence is in ${shortPath(payload.runDirectory)}.`;
      void refresh(snapshot?.selected?.installRoot).then(() => setMessage(outcome));
    }).then((unlisten) => {
      stopListening = unlisten;
    });
    return () => stopListening?.();
  }, [refresh, snapshot?.ready, snapshot?.selected?.installRoot]);

  const refreshCache = useCallback(async () => {
    const game = snapshot?.selected?.installRoot;
    if (!game) return;
    setCacheLoading(true);
    try {
      setCache(await getCache(game));
    } catch (error) {
      setMessage(String(error));
    } finally {
      setCacheInstallRoot(game);
      setCacheLoading(false);
    }
  }, [snapshot?.selected?.installRoot]);

  useEffect(() => {
    const game = snapshot?.selected?.installRoot;
    if (game && cacheInstallRoot !== game && !cacheLoading) void refreshCache();
  }, [cacheInstallRoot, cacheLoading, refreshCache, snapshot?.selected?.installRoot]);

  useEffect(() => {
    const game = snapshot?.selected?.installRoot;
    const cacheReady = game && cacheInstallRoot === game && !cacheLoading;
    const shouldPlan = cacheReady
      && optimizationPreset !== "off"
      && (page === "prepare" || !isCurrentProfilePrepared(cache));
    if (!game || !shouldPlan) {
      setPreparationPlan(null);
      setPreparationPlanLoading(false);
      return;
    }
    let cancelled = false;
    setPreparationPlanLoading(true);
    void getPreparationPlan(game, textureStorage, resourcePresets.balanced.workers)
      .then((plan) => {
        if (!cancelled) setPreparationPlan(plan);
      })
      .catch((error) => {
        if (!cancelled) {
          setPreparationPlan(null);
          setMessage(String(error));
        }
      })
      .finally(() => {
        if (!cancelled) setPreparationPlanLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [cache, cacheInstallRoot, cacheLoading, optimizationPreset, page, snapshot?.selected?.installRoot, textureStorage]);

  const refreshProfiles = useCallback(async () => {
    const game = snapshot?.selected?.installRoot;
    if (!game) return;
    setProfilesLoading(true);
    try {
      setProfiles(await getProfiles(game));
    } catch (error) {
      setMessage(String(error));
    } finally {
      setProfilesLoading(false);
    }
  }, [snapshot?.selected?.installRoot]);

  useEffect(() => {
    if (page === "profiles") void refreshProfiles();
  }, [page, refreshProfiles]);

  const refreshLauncherSettings = useCallback(async () => {
    const game = snapshot?.selected?.installRoot;
    if (!game) return;
    setLauncherSettingsLoading(true);
    try {
      const result = await getLaunchSettings(game);
      setLauncherSettings(result);
      setLauncherDraft({
        resolution: result.preferences.resolution ?? result.settings?.resolution ?? "1280x720",
        fullscreen: result.preferences.fullscreen,
        sound: result.preferences.sound,
        antialiasingSamples: result.preferences.antialiasingSamples ?? 0,
        uiScale: result.preferences.uiScale ?? 1,
        battleSize: result.preferences.battleSize ?? result.limits.battleSizeDefault ?? 400,
      });
    } catch (error) {
      setMessage(String(error));
    } finally {
      setLauncherSettingsLoading(false);
    }
  }, [snapshot?.selected?.installRoot]);

  useEffect(() => {
    if (page === "launch") void refreshLauncherSettings();
  }, [page, refreshLauncherSettings]);

  const chooseInstall = async () => {
    if (!isDesktopHost()) {
      await refresh("/Applications/Starsector");
      return;
    }
    const selected = await open({
      directory: true,
      multiple: false,
      title: "Choose your Starsector folder",
    });
    if (typeof selected === "string") {
      await refresh(selected);
    }
  };

  const launch = useCallback(async () => {
    const game = snapshot?.selected?.installRoot;
    if (!game) return;
    setStatus("running");
    setMessage("Preflight is opening the hangar…");
    try {
      await startGame(game, optimizationPreset);
      setMessage("Starsector is running. Preflight will keep the porch light on.");
    } catch (error) {
      setStatus("error");
      setMessage(String(error));
    }
  }, [optimizationPreset, snapshot?.selected?.installRoot]);

  const prepare = async (launchWhenReady = false) => {
    const game = snapshot?.selected?.installRoot;
    if (!game) return;
    const resources = resourcePresets[resourcePreset];
    try {
      let plan = preparationPlan;
      if (!plan || plan.textureStorage !== textureStorage) {
        setPreparationPlanLoading(true);
        plan = await getPreparationPlan(game, textureStorage, resources.workers);
        setPreparationPlan(plan);
      }
      if (!plan.safeToPrepare) {
        setMessage(plan.refusalReason ?? "Preparation was refused because its storage requirement could not be bounded safely.");
        return;
      }
      launchAfterPreparation.current = launchWhenReady;
      completedPreparationPhases.current.clear();
      setPreparationProgress(null);
      setPreparationCancelling(false);
      setPreparing(true);
      setMessage(launchWhenReady
        ? "Preparing the exact current profile. Starsector will open when it’s ready."
        : "Preparing the exact current profile… You can leave this window open.");
      await startPreparation(game, textureStorage, resources.workers, resources.memoryMib);
      if (!isDesktopHost()) {
        setPreparing(false);
        setMessage("Preview preparation complete.");
        await refreshCache();
        if (launchWhenReady) {
          launchAfterPreparation.current = false;
          await launch();
        }
      }
    } catch (error) {
      launchAfterPreparation.current = false;
      setPreparing(false);
      setMessage(String(error));
    } finally {
      setPreparationPlanLoading(false);
    }
  };

  useEffect(() => {
    if (!isDesktopHost()) return;
    let stopListening: (() => void) | undefined;
    void listen<PreparationStateEvent>("prepare-state", ({ payload }) => {
      if (payload.state === "cancelling") {
        setPreparationCancelling(true);
        setMessage("Stopping preparation safely…");
        return;
      }
      if (payload.state !== "finished" && payload.state !== "cancelled") return;
      const shouldLaunch = launchAfterPreparation.current;
      launchAfterPreparation.current = false;
      setPreparing(false);
      setPreparationCancelling(false);
      if (!payload.success) {
        setMessage(payload.detail ?? "Preparation stopped before it completed.");
        void refreshCache();
        return;
      }
      setMessage(shouldLaunch
        ? "Preparation is complete. Opening Starsector…"
        : "Preparation is complete. The current profile is warm and ready.");
      void (async () => {
        await refreshCache();
        if (shouldLaunch) await launch();
      })();
    }).then((unlisten) => {
      stopListening = unlisten;
    });
    return () => stopListening?.();
  }, [launch, refreshCache]);

  useEffect(() => {
    if (!isDesktopHost()) return;
    let stopListening: (() => void) | undefined;
    void listen<PreparationProgressEvent>("prepare-progress", ({ payload }) => {
      if (payload.state === "completed") completedPreparationPhases.current.add(payload.phase);
      setPreparationProgress({ ...payload });
    }).then((unlisten) => {
      stopListening = unlisten;
    });
    return () => stopListening?.();
  }, []);

  useEffect(() => {
    if (!isDesktopHost()) return;
    let stopListening: (() => void) | undefined;
    void listen<UpdateProgressEvent>("update-progress", ({ payload }) => {
      setUpdateProgress(payload);
      if (payload.state === "installed") setMessage("The verified update is installed. Restarting Preflight…");
    }).then((unlisten) => {
      stopListening = unlisten;
    });
    return () => stopListening?.();
  }, []);

  const stopPreparation = async () => {
    if (!preparing || preparationCancelling) return;
    setPreparationCancelling(true);
    launchAfterPreparation.current = false;
    setMessage("Stopping preparation safely…");
    try {
      const requested = await cancelPreparation();
      if (!requested) {
        setPreparing(false);
        setPreparationCancelling(false);
        setMessage("Preparation had already finished.");
      }
    } catch (error) {
      setPreparationCancelling(false);
      setMessage(String(error));
    }
  };

  const reviewCleanup = async () => {
    const game = snapshot?.selected?.installRoot;
    if (!game || cleanupBusy) return;
    setCleanupBusy(true);
    try {
      const plan = await getCacheCleanup(game);
      setCleanupPlan(plan);
      setMessage(plan.safe
        ? plan.files === 0
          ? "There’s no unused acceleration data to clean up."
          : "Cleanup is ready to review. Nothing has been removed."
        : plan.refusals[0] ?? "Preflight couldn’t prove that cleanup was safe.");
    } catch (error) {
      setMessage(String(error));
    } finally {
      setCleanupBusy(false);
    }
  };

  const cleanCache = async () => {
    const game = snapshot?.selected?.installRoot;
    if (!game || !cleanupPlan?.safe || cleanupPlan.files === 0 || cleanupBusy) return;
    setCleanupBusy(true);
    try {
      const result = await applyCacheCleanup(game);
      setCleanupPlan(null);
      setMessage(`Freed ${formatBytes(result.bytes)} across ${result.files.toLocaleString()} unused files. The current and named profiles stay warm.`);
      await refreshCache();
      setPreparationPlan(null);
    } catch (error) {
      setMessage(String(error));
    } finally {
      setCleanupBusy(false);
    }
  };

  const preparationPhaseLabel = preparationProgress?.phase
    ?.replaceAll("-", " ")
    .replace(/^./, (letter) => letter.toUpperCase());
  const preparationPercent = preparationProgress
    ? Math.min(100, Math.round((completedPreparationPhases.current.size / preparationProgress.totalPhases) * 100))
    : 0;

  const saveCurrentProfile = async () => {
    const game = snapshot?.selected?.installRoot;
    const name = profileName.trim();
    if (!game || !name) return;
    setProfileBusy(true);
    try {
      await saveProfile(game, name);
      setProfileName("");
      setMessage(`Saved the exact current mod order as “${name}”.`);
      await refreshProfiles();
    } catch (error) {
      setMessage(String(error));
    } finally {
      setProfileBusy(false);
    }
  };

  const reviewProfile = async (name: string) => {
    const game = snapshot?.selected?.installRoot;
    if (!game) return;
    setProfileBusy(true);
    try {
      setActivationPlan(await activateProfile(game, name, false));
    } catch (error) {
      setMessage(String(error));
    } finally {
      setProfileBusy(false);
    }
  };

  const applyProfile = async () => {
    const game = snapshot?.selected?.installRoot;
    if (!game || !activationPlan) return;
    setProfileBusy(true);
    try {
      const result = await activateProfile(game, activationPlan.name, true);
      await Promise.all([refresh(game), refreshProfiles(), refreshCache()]);
      if (!result.canActivate) {
        setActivationPlan(result);
        setMessage(result.missingMods.length
          ? `The switch was refused because these mods are now missing: ${result.missingMods.join(", ")}.`
          : "The switch was refused because this profile belongs to a different installation.");
      } else {
        setActivationPlan(null);
        setMessage(result.applied
          ? `Switched to “${result.name}”. Its exact caches will be reused automatically when available.`
          : `“${result.name}” was already active; nothing changed.`);
      }
    } catch (error) {
      setMessage(String(error));
    } finally {
      setProfileBusy(false);
    }
  };

  const checkDesktopAutomation = async () => {
    setDesktopSmokeProbeBusy(true);
    try {
      setDesktopSmokeProbe(await getDesktopSmokeProbe());
    } catch (error) {
      setDesktopSmokeProbe(null);
      setMessage(String(error));
    } finally {
      setDesktopSmokeProbeBusy(false);
    }
  };

  const runDesktopAutomation = async () => {
    const game = snapshot?.selected?.installRoot;
    if (!game || !desktopSmokeProbe?.probe.ready || desktopSmokeRunning) return;
    setDesktopSmokeReview(false);
    setDesktopSmokeRunning(true);
    setDesktopSmokeRunDirectory(null);
    setStatus("running");
    setMessage("Running the checked launch, campaign movement, evidence, and shutdown sequence…");
    try {
      await startDesktopSmoke(game);
      if (!isDesktopHost()) {
        setDesktopSmokeRunning(false);
        setStatus("ready");
        setDesktopSmokeRunDirectory("~/.starsector-preflight/runs/desktop-smoke-preview");
        setMessage("Automated game test passed in browser preview.");
      }
    } catch (error) {
      setDesktopSmokeRunning(false);
      setStatus(snapshot.ready ? "ready" : "setup");
      setMessage(String(error));
    }
  };

  const stopDesktopAutomation = async () => {
    if (!desktopSmokeRunning) return;
    setMessage("Stopping the exact game process and sealing its evidence…");
    try {
      const requested = await cancelDesktopSmoke();
      if (!requested) {
        setDesktopSmokeRunning(false);
        setStatus(snapshot?.ready ? "ready" : "setup");
        setMessage("The automated game test had already stopped.");
      }
    } catch (error) {
      setMessage(String(error));
    }
  };

  const saveDiagnostics = async () => {
    const stamp = new Date().toISOString().slice(0, 10);
    const destination = isDesktopHost()
      ? await saveFile({
          title: "Save Preflight diagnostics",
          defaultPath: `preflight-diagnostics-${stamp}.zip`,
          filters: [{ name: "ZIP archive", extensions: ["zip"] }],
        })
      : `/Users/captain/Desktop/preflight-diagnostics-${stamp}.zip`;
    if (!destination) return;
    setDiagnosticsBusy(true);
    setMessage("Collecting a small, disclosed support bundle…");
    try {
      const result = await exportDiagnostics(destination);
      setDiagnosticsExport(result);
      setReportReview(false);
      setReportError("");
      setReportUploadedBytes(0);
      setMessage(`Saved ${result.files} disclosed files. Inspect the ZIP before sharing it.`);
    } catch (error) {
      setMessage(String(error));
    } finally {
      setDiagnosticsBusy(false);
    }
  };

  const submitRunReport = async () => {
    if (!diagnosticsExport || !reportIntake?.configured || reportUploading) return;
    setReportUploading(true);
    setReportFinalizing(false);
    setReportCancelling(false);
    setReportUploadedBytes(0);
    setReportError("");
    setMessage("Creating a short-lived case for this exact diagnostics ZIP…");
    try {
      const receipt = await sendRunReport(diagnosticsExport);
      setReportReceipt(receipt);
      setReportReview(false);
      setReportUploadedBytes(diagnosticsExport.bytes);
      setMessage(`Run report ${receipt.caseId} was accepted. Keep the receipt for support or deletion.`);
    } catch (error) {
      const detail = String(error);
      if (!detail.toLowerCase().includes("cancel")) setReportError(detail);
      setMessage(detail);
    } finally {
      setReportUploading(false);
      setReportFinalizing(false);
      setReportCancelling(false);
    }
  };

  const stopRunReport = async () => {
    if (!reportUploading || reportCancelling) return;
    setReportCancelling(true);
    setMessage("Stopping the report upload…");
    try {
      const requested = await cancelRunReport();
      if (!requested) {
        setReportUploading(false);
        setReportCancelling(false);
        setMessage("The report upload had already stopped.");
      }
    } catch (error) {
      setReportCancelling(false);
      setMessage(String(error));
    }
  };

  const copyRunReportReceipt = async () => {
    if (!reportReceipt) return;
    try {
      await navigator.clipboard.writeText(JSON.stringify(reportReceipt, null, 2));
      setMessage("Run-report receipt copied. It includes the deletion authorization.");
    } catch (error) {
      setMessage(`Could not copy the receipt: ${error}`);
    }
  };

  const removeRunReport = async () => {
    if (!reportReceipt || reportDeleting) return;
    setReportDeleting(true);
    try {
      await deleteRunReport(reportReceipt.deletion);
      const caseId = reportReceipt.caseId;
      setReportReceipt(null);
      setMessage(`Run report ${caseId} was deleted. Your local diagnostics ZIP is unchanged.`);
    } catch (error) {
      setMessage(String(error));
    } finally {
      setReportDeleting(false);
    }
  };

  const saveLauncherSettings = async () => {
    const game = snapshot?.selected?.installRoot;
    if (!game || !launcherDraft) return;
    setLauncherSettingsSaving(true);
    setMessage("Saving Starsector’s own launch preferences…");
    try {
      const result = await updateLaunchSettings(game, launcherDraft);
      setLauncherSettings(result);
      setMessage("Launch settings saved. Vanilla and Preflight launches will use the same values.");
    } catch (error) {
      setMessage(String(error));
    } finally {
      setLauncherSettingsSaving(false);
    }
  };

  const reviewRemoval = async (scope: RemovalScope) => {
    if (removalBusy) return;
    setRemovalBusy(true);
    try {
      const plan = await getRemovalPlan(scope);
      setRemovalPlan(plan);
      setMessage(plan.targets.length === 0
        ? "There’s nothing in that removal scope."
        : "Removal is ready to review. Nothing has been removed.");
    } catch (error) {
      setMessage(String(error));
    } finally {
      setRemovalBusy(false);
    }
  };

  const removePreflight = async () => {
    if (!removalPlan?.safe || removalPlan.targets.length === 0 || removalBusy) return;
    const scope = removalPlan.scope;
    setRemovalBusy(true);
    try {
      const result = await applyRemoval(scope);
      if (scope === "all-data") {
        try { window.localStorage.removeItem("preflight.optimizationPreset"); } catch { /* already removed on disk */ }
        setCache(null);
        setProfiles(null);
      }
      setRemovalPlan(null);
      const platformStep = snapshot?.platform === "windows"
        ? "Use Windows Installed apps to remove this desktop app."
        : snapshot?.platform === "linux"
          ? "Remove this desktop package with the package manager that installed it."
          : "Move this desktop app to the Trash when you’re ready.";
      setMessage(`${result.files.toLocaleString()} Preflight-owned files removed. ${platformStep}`);
    } catch (error) {
      setMessage(String(error));
    } finally {
      setRemovalBusy(false);
    }
  };

  const installSignedUpdate = async () => {
    if (!updateStatus?.available || !updateStatus.version || updateInstalling || preparing || status === "running") return;
    setUpdateInstalling(true);
    setUpdateProgress(null);
    setUpdateError("");
    setMessage(`Downloading and verifying Preflight ${updateStatus.version}…`);
    try {
      await installUpdate(updateStatus.version);
    } catch (error) {
      const detail = String(error);
      setUpdateStatus(null);
      setUpdateError(detail);
      setMessage(detail);
      setUpdateInstalling(false);
    }
  };

  const isReady = Boolean(snapshot?.ready && snapshot.selected);
  const profilePrepared = isCurrentProfilePrepared(cache);
  const needsPreparation = optimizationPreset !== "off" && !profilePrepared;
  const selectedOptimization = optimizationPresets.find((preset) => preset.id === optimizationPreset)
    ?? optimizationPresets[0];
  const title = pageTitle(page, status, preparing, isReady);

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <Logo />
        <nav className="nav" aria-label="Main navigation">
          <button className={`nav__item ${page === "home" ? "nav__item--active" : ""}`} type="button" aria-current={page === "home" ? "page" : undefined} onClick={() => setPage("home")}>
            <HomeIcon />
            <span>Home</span>
          </button>
          <button className={`nav__item ${page === "launch" ? "nav__item--active" : ""}`} type="button" aria-current={page === "launch" ? "page" : undefined} onClick={() => setPage("launch")} disabled={!isReady}>
            <PlayIcon />
            <span>Launch</span>
          </button>
          <button className={`nav__item ${page === "prepare" ? "nav__item--active" : ""}`} type="button" aria-current={page === "prepare" ? "page" : undefined} onClick={() => setPage("prepare")} disabled={!isReady}>
            <SparklesIcon />
            <span>Prepare</span>
          </button>
          <button className={`nav__item ${page === "profiles" ? "nav__item--active" : ""}`} type="button" aria-current={page === "profiles" ? "page" : undefined} onClick={() => setPage("profiles")} disabled={!isReady}>
            <LayersIcon />
            <span>Profiles</span>
          </button>
        </nav>
        <div className="sidebar__footer">
          <button className={`nav__item ${page === "settings" ? "nav__item--active" : ""}`} type="button" aria-current={page === "settings" ? "page" : undefined} onClick={() => setPage("settings")}>
            <SettingsIcon />
            <span>Settings</span>
            {updateStatus?.available && <span className="nav__badge">Update</span>}
          </button>
          <div className="alpha-pill"><span /> Desktop alpha</div>
        </div>
      </aside>

      <main className="main">
        <header className="topbar">
          <h1>{title}</h1>
          <button className="icon-button" type="button" onClick={() => void refresh(snapshot?.selected?.installRoot)} aria-label="Refresh installation status" disabled={status === "loading"}>
            <RefreshIcon className={status === "loading" ? "spin" : ""} />
          </button>
        </header>

        {page === "home" ? <>
        <section className={`hero card ${isReady ? "hero--ready" : "hero--setup"}`}>
          <div className="hero__copy">
            <div className={`status-chip ${isReady ? "status-chip--ready" : ""}`}>
              {isReady ? <CheckIcon /> : <SparklesIcon />}
              {status === "running" ? "Game running" : preparing ? "Preparing profile" : isReady ? "Ready" : "Setup required"}
            </div>
            <h2>{isReady ? needsPreparation ? "Prepare this profile" : "Launch Starsector" : "Choose the game folder"}</h2>
            {!isReady || needsPreparation ? <p>{isReady
              ? "Build the caches for the current mod profile, then launch."
              : "Select the folder that contains the Starsector launcher."}</p> : null}
            <div className="hero__actions">
              {isReady ? (
                <button className="button button--primary" type="button" onClick={() => void (needsPreparation ? prepare(true) : launch())} disabled={status === "running" || status === "loading" || preparing || cacheLoading || (needsPreparation && (preparationPlanLoading || !preparationPlan?.safeToPrepare))}>
                  {needsPreparation ? <SparklesIcon /> : <PlayIcon />}
                  {status === "running" ? "Starsector is running" : preparing ? "Preparing…" : cacheLoading ? "Checking profile…" : preparationPlanLoading && needsPreparation ? "Calculating space…" : needsPreparation ? "Prepare and launch" : "Launch Starsector"}
                </button>
              ) : (
                <button className="button button--primary" type="button" onClick={() => void chooseInstall()} disabled={status === "loading"}>
                  <FolderIcon />
                  Choose game folder
                </button>
              )}
              {isReady && (
                <button className="button button--quiet" type="button" onClick={() => void chooseInstall()}>
                  Choose another
                </button>
              )}
            </div>
            {isReady && (
              <div className="hero__launch-note">
                <strong>{selectedOptimization.label}</strong>
                <span>{needsPreparation
                  ? preparationPlanLoading
                    ? "Reading the winning textures and calculating a safe disk requirement…"
                    : preparationPlan?.safeToPrepare
                      ? `${textureStorage === "balanced" ? "Balanced" : "Fastest"} predicts ${formatBytes(preparationPlan.predictedAdditionalBytes)} additional; ${formatBytes(preparationPlan.usableBytes)} is available.`
                      : preparationPlan?.refusalReason ?? "Storage must be calculated before preparation."
                  : profilePrepared
                    ? `Prepared · ${formatBytes(cache?.profiles.find((profile) => profile.current)?.bytes ?? 0)}`
                    : "Preparation is disabled for this troubleshooting launch."}</span>
              </div>
            )}
          </div>
          <div className="hero__art" aria-hidden="true">
            <div className="orbit orbit--outer" />
            <div className="orbit orbit--inner" />
            <div className="moon" />
            <div className="ship">
              <span className="ship__window" />
              <span className="ship__flame" />
            </div>
            <span className="star star--one">✦</span>
            <span className="star star--two">✦</span>
            <span className="star star--three">·</span>
          </div>
        </section>

        {updateStatus?.available && (
          <section className="update-notice" aria-label="Preflight update available">
            <strong>Preflight {updateStatus.version} is available</strong>
            <button type="button" className="text-button" onClick={() => setPage("settings")}>Review update <ArrowIcon /></button>
          </section>
        )}

        {message && (
          <div className={`notice ${status === "error" ? "notice--error" : ""}`} role="status">
            <span>{status === "error" ? "!" : "✦"}</span>
            <p>{message}</p>
            {status === "error" && <button type="button" onClick={() => void refresh()}>Try again</button>}
          </div>
        )}

        <div className="content-grid content-grid--single">
          <section className="card installation-card">
            <div className="card__heading">
              <div>
                <h2>Installation</h2>
              </div>
              <div className={`tiny-status ${isReady ? "tiny-status--good" : ""}`}>
                <span />
                {isReady ? "Found" : "Not found"}
              </div>
            </div>
            {isReady && snapshot?.selected ? (
              <div className="install-detail">
                <div className="install-icon"><FolderIcon /></div>
                <div>
                  <strong>{shortPath(snapshot.selected.installRoot)}</strong>
                  <span>{friendlyPlatform(snapshot.platform)} · {snapshot.selected.kind.replace("-", " ")}</span>
                </div>
                <button type="button" className="text-button" onClick={() => void chooseInstall()} aria-label="Change Starsector installation">
                  Change <ArrowIcon />
                </button>
              </div>
            ) : (
              <div className="empty-detail">
                <div className="install-icon"><FolderIcon /></div>
                <div><strong>No folder chosen yet</strong><span>Automatic discovery didn’t find a launcher.</span></div>
              </div>
            )}
          </section>

        </div>
        </> : page === "launch" ? (
          <div className="launch-page">
            <section className="card launch-intro">
              <div>
                <p className="eyebrow">Shared with Starsector</p>
                <h2>Game settings</h2>
              </div>
              <div className={`tiny-status ${launcherSettings?.directLaunchAvailable ? "tiny-status--good" : ""}`}>
                <span />
                {launcherSettingsLoading ? "Reading" : launcherSettings?.directLaunchAvailable ? "Direct launch ready" : "Vanilla launcher needed"}
              </div>
            </section>

            {message && (
              <div className="notice" role="status"><span>✦</span><p>{message}</p></div>
            )}

            {launcherDraft && launcherSettings ? (
              <>
                <section className="card optimization-card">
                  <div className="card__heading">
                    <div>
                      <p className="eyebrow">Launch behavior</p>
                      <h2>Optimizations</h2>
                    </div>
                    <span className="optimization-card__saved">Saved for future launches</span>
                  </div>
                  <div className="optimization-choices" role="radiogroup" aria-label="Optimization preset">
                    {optimizationPresets.map((preset) => (
                      <label className={`choice-card ${optimizationPreset === preset.id ? "choice-card--selected" : ""}`} key={preset.id}>
                        <input
                          type="radio"
                          name="optimization-preset"
                          aria-label={`${preset.label} optimizations`}
                          checked={optimizationPreset === preset.id}
                          onChange={() => setOptimizationPreset(preset.id)}
                        />
                        <span><strong>{preset.label}</strong><small>{preset.description}</small></span>
                        <b>{preset.badge}</b>
                      </label>
                    ))}
                  </div>
                </section>
                <div className="launch-settings-grid">
                  <section className="card launch-settings-card">
                    <div className="card__heading">
                      <div><p className="eyebrow">Display</p><h2>Window and rendering</h2></div>
                      <button className="icon-button icon-button--small" type="button" onClick={() => void refreshLauncherSettings()} aria-label="Refresh launch settings" disabled={launcherSettingsLoading}>
                        <RefreshIcon className={launcherSettingsLoading ? "spin" : ""} />
                      </button>
                    </div>
                    <label className="setting-field" htmlFor="launch-resolution">
                      <span><strong>Resolution</strong></span>
                      <input id="launch-resolution" aria-label="Resolution" value={launcherDraft.resolution} onChange={(event) => setLauncherDraft({ ...launcherDraft, resolution: event.target.value })} inputMode="text" spellCheck={false} />
                    </label>
                    <label className="setting-toggle">
                      <span><strong>Fullscreen</strong></span>
                      <input type="checkbox" aria-label="Fullscreen" checked={launcherDraft.fullscreen} onChange={(event) => setLauncherDraft({ ...launcherDraft, fullscreen: event.target.checked })} />
                    </label>
                    <label className="setting-toggle">
                      <span><strong>Sound</strong></span>
                      <input type="checkbox" aria-label="Sound" checked={launcherDraft.sound} onChange={(event) => setLauncherDraft({ ...launcherDraft, sound: event.target.checked })} />
                    </label>
                    <label className="setting-field" htmlFor="launch-aa">
                      <span><strong>Antialiasing</strong><small>Starsector recommends Off at 100%, 200%, or 300% UI scale</small></span>
                      <select id="launch-aa" aria-label="Antialiasing" value={launcherDraft.antialiasingSamples} onChange={(event) => setLauncherDraft({ ...launcherDraft, antialiasingSamples: Number(event.target.value) })}>
                        {launcherSettings.limits.antialiasingSamples.map((samples) => <option value={samples} key={samples}>{samples === 0 ? "Off" : `${samples} samples`}</option>)}
                      </select>
                    </label>
                    <label className="setting-slider" htmlFor="launch-scale">
                      <span><strong>UI scaling</strong><b>{Math.round(launcherDraft.uiScale * 100)}%</b></span>
                      <input id="launch-scale" aria-label="UI scaling" type="range" min={launcherSettings.limits.uiScaleMin} max={maximumUiScale(launcherDraft.resolution) ?? launcherSettings.limits.uiScaleMax} step={launcherSettings.limits.uiScaleStep} value={launcherDraft.uiScale} onChange={(event) => setLauncherDraft({ ...launcherDraft, uiScale: Number(event.target.value) })} />
                    </label>
                  </section>

                  <section className="card launch-settings-card">
                    <div className="card__heading"><div><p className="eyebrow">Combat</p><h2>Battle size</h2></div></div>
                    <p className="setting-explainer">Uses the installed game’s supported range.</p>
                    <label className="setting-slider" htmlFor="launch-battle-size">
                      <span><strong>Deployment-point budget</strong><b>{launcherDraft.battleSize}</b></span>
                      <input id="launch-battle-size" aria-label="Deployment-point budget" type="range" min={launcherSettings.limits.battleSizeMin ?? 1} max={launcherSettings.limits.battleSizeMax ?? Math.max(launcherDraft.battleSize, 400)} step="10" value={launcherDraft.battleSize} onChange={(event) => setLauncherDraft({ ...launcherDraft, battleSize: Number(event.target.value) })} />
                    </label>
                    <div className="battle-bounds">
                      <span>Minimum {launcherSettings.limits.battleSizeMin ?? "unknown"}</span>
                      <span>Game default {launcherSettings.limits.battleSizeDefault ?? "unknown"}</span>
                      <span>Maximum {launcherSettings.limits.battleSizeMax ?? "unknown"}</span>
                    </div>
                  </section>
                </div>

                {(launcherSettings.preferences.diagnostics.length > 0 || launcherSettings.limits.diagnostics.length > 0) && (
                  <section className="card launch-diagnostics">
                    {[...launcherSettings.preferences.diagnostics, ...launcherSettings.limits.diagnostics].map((diagnostic) => <p key={diagnostic}>{diagnostic}</p>)}
                  </section>
                )}

                <section className="card launch-save">
                  <div><strong>Save game settings</strong><span>{launcherSettings.backup ? `Previous values: ${shortPath(launcherSettings.backup)}` : "A backup is written before saving."}</span></div>
                  <button className="button button--primary" type="button" onClick={() => void saveLauncherSettings()} disabled={launcherSettingsSaving || status === "running" || preparing}>
                    <CheckIcon />{launcherSettingsSaving ? "Saving…" : "Save launch settings"}
                  </button>
                </section>
              </>
            ) : (
              <section className="card launch-loading">Reading Starsector’s saved preferences…</section>
            )}
          </div>
        ) : page === "prepare" ? (
          <div className="prepare-page">
            <section className="card prepare-intro">
              <div>
                <p className="eyebrow">Current mod profile</p>
                <h2>Build reusable caches</h2>
                <p>Changed game or mod files automatically use new cache identities.</p>
              </div>
              <div className={`tiny-status ${cache?.currentProfileFingerprint ? "tiny-status--good" : ""}`}>
                <span />
                {cacheLoading ? "Checking" : cache?.currentProfileFingerprint ? "Profile detected" : "Not prepared"}
              </div>
            </section>

            {message && (
              <div className="notice" role="status"><span>✦</span><p>{message}</p></div>
            )}

            <div className="prepare-grid">
              <section className="card prepare-options">
                <div className="card__heading">
                  <div><p className="eyebrow">Space and speed</p><h2>Texture storage</h2></div>
                </div>
                <label className={`choice-card ${textureStorage === "balanced" ? "choice-card--selected" : ""}`}>
                  <input type="radio" name="texture-storage" checked={textureStorage === "balanced"} onChange={() => setTextureStorage("balanced")} />
                  <span><strong>Balanced</strong><small>Recommended · exact lossless LZ4 with raw storage where compression barely helps</small></span>
                  <b>Default</b>
                </label>
                <label className={`choice-card ${textureStorage === "fastest" ? "choice-card--selected" : ""}`}>
                  <input type="radio" name="texture-storage" checked={textureStorage === "fastest"} onChange={() => setTextureStorage("fastest")} />
                  <span><strong>Fastest</strong><small>Keeps every upload-ready pixel array raw; typically several GB more for a few hundred ms</small></span>
                </label>

                <div className="resource-heading"><strong>Preparation resources</strong><span>Only affects the one-time build</span></div>
                <div className="preset-row">
                  {Object.entries(resourcePresets).map(([id, preset]) => (
                    <button key={id} type="button" className={resourcePreset === id ? "preset preset--selected" : "preset"} onClick={() => setResourcePreset(id as keyof typeof resourcePresets)}>
                      <strong>{preset.label}</strong><span>{preset.workers} workers · {preset.memoryMib} MiB</span>
                    </button>
                  ))}
                </div>
              </section>

              <section className="card storage-card">
                <div className="card__heading">
                  <div><p className="eyebrow">On this computer</p><h2>Preflight storage</h2></div>
                  <button className="icon-button icon-button--small" type="button" onClick={() => void refreshCache()} aria-label="Refresh cache storage" disabled={cacheLoading}><RefreshIcon className={cacheLoading ? "spin" : ""} /></button>
                </div>
                <strong className="storage-total">{cache ? formatBytes(cache.total.bytes) : "—"}</strong>
                <span className="storage-files">{cache ? `${cache.total.files.toLocaleString()} files` : "Reading cache…"}</span>
                <div className="storage-groups">
                  {(cache?.groups ?? []).map((group) => (
                    <div key={group.id}><span>{group.id}</span><strong>{formatBytes(group.bytes)}</strong></div>
                  ))}
                  {(cache?.uncategorizedBytes ?? 0) > 0 && (
                    <div><span>Other Preflight data</span><strong>{formatBytes(cache?.uncategorizedBytes ?? 0)}</strong></div>
                  )}
                </div>
                <div className="storage-groups storage-plan" aria-label="Preparation storage plan">
                  <div><span>Predicted additional</span><strong>{preparationPlanLoading ? "Calculating…" : preparationPlan ? formatBytes(preparationPlan.predictedAdditionalBytes) : "—"}</strong></div>
                  <div><span>Conservative bound</span><strong>{preparationPlan ? formatBytes(preparationPlan.upperBoundAdditionalBytes) : "—"}</strong></div>
                  <div><span>Safety reserve</span><strong>{preparationPlan ? formatBytes(preparationPlan.safetyReserveBytes) : "—"}</strong></div>
                  <div><span>Available now</span><strong>{preparationPlan ? formatBytes(preparationPlan.usableBytes) : "—"}</strong></div>
                </div>
                {preparationPlan && !preparationPlan.safeToPrepare && <p className="activation-warning">{preparationPlan.refusalReason}</p>}
                <p className="storage-note">Cleanup is always previewed before deletion.{(cache?.uncategorizedBytes ?? 0) > 0 ? " Other includes retained cache formats and files outside the active categories." : ""}</p>
                <button className="button button--quiet button--compact" type="button" onClick={() => void reviewCleanup()} disabled={cleanupBusy || preparing || status === "running"}>
                  {cleanupBusy ? "Checking…" : "Review cleanup"}
                </button>
              </section>
            </div>

            <section className="card prepare-action">
              <div>
                <strong>{preparationCancelling ? "Stopping preparation" : preparing ? preparationPhaseLabel ?? "Preparation is running" : preparationPlanLoading ? "Calculating disk requirement" : preparationPlan?.safeToPrepare ? "There’s room to prepare this profile" : "Preparation needs attention"}</strong>
                <span>{preparing ? `${preparationPercent}% complete · finished artifacts stay reusable` : `${textureStorage === "balanced" ? "Balanced storage selected" : "Fastest raw storage selected"} · ${resourcePresets[resourcePreset].label.toLowerCase()} resource use`}</span>
                {preparing && <div className="preparation-progress" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={preparationPercent}><span style={{ width: `${preparationPercent}%` }} /></div>}
              </div>
              <div className="prepare-actions">
                {preparing && <button className="button button--quiet" type="button" onClick={() => void stopPreparation()} disabled={preparationCancelling}>{preparationCancelling ? "Stopping…" : "Stop safely"}</button>}
                <button className="button button--primary" type="button" onClick={() => void prepare(false)} disabled={preparing || !isReady || preparationPlanLoading || !preparationPlan?.safeToPrepare}>
                  <SparklesIcon />{preparing ? "Preparing…" : preparationPlanLoading ? "Calculating…" : "Prepare current profile"}
                </button>
              </div>
            </section>

            {cleanupPlan && (
              <section className="card cleanup-review" aria-label="Cache cleanup review">
                <div className="activation-review__heading">
                  <div><p className="eyebrow">Nothing removed yet</p><h2>{cleanupPlan.files === 0 ? "Everything here is still useful" : `Free ${formatBytes(cleanupPlan.bytes)}?`}</h2></div>
                  <button className="text-button" type="button" onClick={() => setCleanupPlan(null)} disabled={cleanupBusy}>Close</button>
                </div>
                {!cleanupPlan.safe && <p className="activation-warning">{cleanupPlan.refusals.join(" ")}</p>}
                <p className="cleanup-summary">Preflight will keep the current profile and {Math.max(0, cleanupPlan.survivingProfileFingerprints.length - 1).toLocaleString()} named profile{cleanupPlan.survivingProfileFingerprints.length === 2 ? "" : "s"}. Game files, mods, saves, settings, and diagnostic evidence aren’t part of this cleanup.</p>
                {cleanupPlan.groups.length > 0 && <div className="cleanup-groups">
                  {cleanupPlan.groups.map((group) => <div key={group.reason}><span>{group.reason.replaceAll("-", " ")}</span><strong>{formatBytes(group.bytes)} · {group.files.toLocaleString()} files</strong></div>)}
                </div>}
                <div className="activation-review__footer">
                  <span><ShieldIcon /> The plan is recalculated under the shared operation lock before deletion.</span>
                  <button className="button button--danger" type="button" onClick={() => void cleanCache()} disabled={!cleanupPlan.safe || cleanupPlan.files === 0 || cleanupBusy}>
                    {cleanupBusy ? "Cleaning…" : cleanupPlan.files === 0 ? "Nothing to remove" : `Remove ${cleanupPlan.files.toLocaleString()} files`}
                  </button>
                </div>
              </section>
            )}
          </div>
        ) : page === "profiles" ? (
          <div className="profiles-page">
            <section className="card profiles-intro">
              <div>
                <p className="eyebrow">Mod profiles</p>
                <h2>Saved mod sets</h2>
                <p>Switches are previewed and backed up before applying.</p>
              </div>
              <div className={`tiny-status ${profiles?.profiles.some((profile) => profile.active) ? "tiny-status--good" : ""}`}>
                <span />
                {profilesLoading ? "Checking" : `${profiles?.profiles.length ?? 0} saved`}
              </div>
            </section>

            {message && (
              <div className="notice" role="status"><span>✦</span><p>{message}</p></div>
            )}

            <div className="profiles-grid">
              <section className="card profile-list-card">
                <div className="card__heading">
                  <div><p className="eyebrow">This installation</p><h2>Saved profiles</h2></div>
                  <button className="icon-button icon-button--small" type="button" onClick={() => void refreshProfiles()} aria-label="Refresh saved profiles" disabled={profilesLoading}>
                    <RefreshIcon className={profilesLoading ? "spin" : ""} />
                  </button>
                </div>
                <div className="profile-list">
                  {!profilesLoading && profiles?.profiles.length === 0 && (
                    <div className="profile-empty"><strong>No profiles saved yet</strong><span>Give the current mod set a name to make your first one.</span></div>
                  )}
                  {(profiles?.profiles ?? []).map((profile) => (
                    <article className={`profile-card ${profile.active ? "profile-card--active" : ""}`} key={profile.name}>
                      <div className="profile-card__mark"><LayersIcon /></div>
                      <div className="profile-card__copy">
                        <div><strong>{profile.name}</strong>{profile.active && <b>Active</b>}</div>
                        <span>{profile.modCount.toLocaleString()} mods · saved {new Date(profile.savedAt).toLocaleDateString()}</span>
                        {!profile.sameInstall && <small>Saved for a different installation</small>}
                        {profile.missingMods.length > 0 && <small>Missing: {profile.missingMods.join(", ")}</small>}
                      </div>
                      <button className="button button--quiet button--compact" type="button" onClick={() => void reviewProfile(profile.name)} disabled={profile.active || !profile.canActivate || profileBusy}>
                        {profile.active ? "Current" : "Review switch"}
                      </button>
                    </article>
                  ))}
                </div>
                {(profiles?.diagnostics.length ?? 0) > 0 && (
                  <div className="profile-diagnostics">{profiles?.diagnostics.map((diagnostic) => <p key={diagnostic}>{diagnostic}</p>)}</div>
                )}
              </section>

              <section className="card profile-save-card">
                <p className="eyebrow">Remember this setup</p>
                <h2>Save current profile</h2>
                <p>This records names and order only. Your mods remain exactly where they are.</p>
                <label htmlFor="profile-name">Profile name</label>
                <input id="profile-name" value={profileName} onChange={(event) => setProfileName(event.target.value)} placeholder="e.g. Heavy campaign" maxLength={96} />
                <button className="button button--primary" type="button" disabled={!profileName.trim() || profileBusy} onClick={() => void saveCurrentProfile()}>
                  Save current profile
                </button>
                <div className="profile-cache-note"><SparklesIcon /><span>Prepared caches are content-addressed. Matching profiles reuse them automatically; run Prepare after a switch only when its cache is missing.</span></div>
              </section>
            </div>

            {activationPlan && (
              <section className="card activation-review" aria-label="Profile switch review">
                <div className="activation-review__heading">
                  <div><p className="eyebrow">Nothing changed yet</p><h2>Switch to {activationPlan.name}?</h2></div>
                  <button className="text-button" type="button" onClick={() => setActivationPlan(null)} disabled={profileBusy}>Cancel</button>
                </div>
                {!activationPlan.sameInstall && <p className="activation-warning">This profile belongs to {shortPath(activationPlan.savedInstallRoot)} and cannot be applied here.</p>}
                {activationPlan.missingMods.length > 0 && <p className="activation-warning">Install these mods first: {activationPlan.missingMods.join(", ")}</p>}
                <div className="activation-columns">
                  <div><strong>Enable ({activationPlan.enable.length})</strong>{activationPlan.enable.length ? <ul>{activationPlan.enable.map((mod) => <li key={mod}>{mod}</li>)}</ul> : <span>Nothing</span>}</div>
                  <div><strong>Disable ({activationPlan.disable.length})</strong>{activationPlan.disable.length ? <ul>{activationPlan.disable.map((mod) => <li key={mod}>{mod}</li>)}</ul> : <span>Nothing</span>}</div>
                </div>
                <div className="activation-review__footer">
                  <span><ShieldIcon /> Preflight rechecks the file, writes a backup, then replaces it safely.</span>
                  <button className="button button--primary" type="button" onClick={() => void applyProfile()} disabled={!activationPlan.canActivate || activationPlan.active || profileBusy}>
                    {profileBusy ? "Switching…" : "Apply switch"}
                  </button>
                </div>
              </section>
            )}
          </div>
        ) : (
          <div className="settings-page">
            {message && (
              <div className="notice" role="status"><span>✦</span><p>{message}</p></div>
            )}

            <section className="card update-card">
              <div className="card__heading">
                <div><p className="eyebrow">Verified updates</p><h2>{updateStatus?.available ? `Preflight ${updateStatus.version} is available` : "Keep Preflight current"}</h2></div>
                <ShieldIcon className="settings-check" />
              </div>
              <p className={updateStatus?.available ? "update-release-notes" : undefined}>{updateStatus?.available
                ? updateStatus.notes || "A newer verified release is ready. Installation starts only after confirmation."
                : updateStatus?.configured
                  ? `Version ${updateStatus.currentVersion} is current.`
                  : updateStatus?.reason || "Update status hasn’t been checked yet."}</p>
              {updateError && <p className="activation-warning">{updateError}</p>}
              {updateInstalling && <div className="update-progress" role="progressbar" aria-label="Update download" aria-valuemin={0} aria-valuemax={updateProgress?.contentLength ?? undefined} aria-valuenow={updateProgress?.downloadedBytes ?? 0}><span>{updateProgress?.contentLength ? `${formatBytes(updateProgress.downloadedBytes)} of ${formatBytes(updateProgress.contentLength)}` : `${formatBytes(updateProgress?.downloadedBytes ?? 0)} downloaded`}</span></div>}
              <div className="update-actions">
                <button className="button button--quiet button--compact" type="button" onClick={() => void checkUpdates(true)} disabled={updateChecking || updateInstalling}>{updateChecking ? "Checking…" : updateStatus ? "Check again" : "Check for updates"}</button>
                {updateStatus?.available && <button className="button button--primary" type="button" onClick={() => void installSignedUpdate()} disabled={updateInstalling || preparing || status === "running"}>{updateInstalling ? "Installing…" : "Install and restart"}</button>}
              </div>
              {updateStatus?.available && <small>Prepared profiles stay in place. If the cache format changed, the previous copy is kept for rollback.</small>}
              <small>Downloads are verified with the release key embedded in this build. A failed download or signature check leaves the installed version unchanged.</small>
            </section>

            {desktopSmokeReview && (
              <section className="card automation-review" aria-label="Automated game test review">
                <div className="activation-review__heading">
                  <div><p className="eyebrow">Nothing started yet</p><h2>Run the checked campaign test?</h2></div>
                  <button className="text-button" type="button" onClick={() => setDesktopSmokeReview(false)} disabled={desktopSmokeRunning}>Cancel</button>
                </div>
                <p>Preflight will open the current installation with recommended optimizations, continue the latest save, move forward for three seconds, collect a window screenshot and bounded timing evidence, then close only that exact game process.</p>
                <p>Leave the game window unobstructed while it runs. The interaction sequence has a four-minute deadline; startup and cleanup have separate bounds.</p>
                <div className="activation-review__footer">
                  <span><ShieldIcon /> The driver doesn’t edit game, mod, or save files; it only sends the actions listed here.</span>
                  <button className="button button--primary" type="button" onClick={() => void runDesktopAutomation()} disabled={desktopSmokeRunning}>
                    {desktopSmokeRunning ? "Test running…" : "Start automated test"}
                  </button>
                </div>
              </section>
            )}

            <section className="card diagnostics-action">
              <div>
                <strong>{diagnosticsExport ? "Diagnostics are ready" : "Ready to collect support evidence"}</strong>
                <span>{diagnosticsExport
                  ? `${formatBytes(diagnosticsExport.bytes)} · ${shortPath(diagnosticsExport.output)}`
                  : "Home-directory paths are redacted. Other visible metadata is disclosed in the ZIP."}</span>
              </div>
              <div className="report-actions">
                <button className={`button ${diagnosticsExport ? "button--quiet" : "button--primary"}`} type="button" onClick={() => void saveDiagnostics()} disabled={diagnosticsBusy || reportUploading}>
                  <FolderIcon />{diagnosticsBusy ? "Saving…" : diagnosticsExport ? "Save another ZIP" : "Save diagnostics bundle"}
                </button>
                {diagnosticsExport && (
                  <button className="button button--primary" type="button" onClick={() => setReportReview(true)} disabled={!reportIntake?.configured || reportUploading || reportReceipt !== null}>
                    {reportReceipt ? "Receipt below" : "Review send"}
                  </button>
                )}
              </div>
            </section>

            <details className="card settings-disclosure">
              <summary>
                <span><strong>What diagnostics include</strong><small>Review the export boundary</small></span>
              </summary>
              <div className="settings-grid settings-disclosure__body">
                <section className="diagnostics-card">
                  <div className="card__heading">
                    <div><p className="eyebrow">Included</p><h2>Useful metadata only</h2></div>
                    <CheckIcon className="settings-check" />
                  </div>
                  <ul>
                    <li>Run outcome, runtime, adapter health and timing summaries</li>
                    <li>Enabled-mod and resource names, counts, sizes and content hashes</li>
                    <li>Benchmark identity, settings and result metadata</li>
                    <li>A manifest with every included or skipped file</li>
                  </ul>
                </section>
                <section className="diagnostics-card diagnostics-card--excluded">
                  <div className="card__heading">
                    <div><p className="eyebrow">Excluded</p><h2>Game and personal data</h2></div>
                    <ShieldIcon className="settings-check" />
                  </div>
                  <ul>
                    <li>Game, mod, save, texture, audio or bytecode contents</li>
                    <li>Acceleration caches, console logs and crash dumps</li>
                    <li>JFR recordings, screenshots, audio or unknown files</li>
                    <li>Symlinks or any source file larger than 512 KiB</li>
                  </ul>
                </section>
              </div>
            </details>

            {diagnosticsExport && reportIntake && !reportIntake.configured && (
              <p className="report-unavailable"><ShieldIcon /> {reportIntake.reason ?? "Run-report sending isn't configured in this build."} The ZIP remains available to inspect and share manually.</p>
            )}

            {reportReview && diagnosticsExport && (
              <section className="card report-review" aria-label="Run report consent">
                <div className="activation-review__heading">
                  <div><p className="eyebrow">Nothing sent yet</p><h2>Send this exact ZIP?</h2></div>
                  <button className="text-button" type="button" onClick={() => setReportReview(false)} disabled={reportUploading}>Cancel</button>
                </div>
                <p>Preflight will send the ZIP shown below to {reportIntake?.origin}. The service also receives ordinary network metadata such as your IP address for delivery and rate limiting. There are no automatic or background uploads.</p>
                <div className="report-facts">
                  <div><span>File</span><strong>{shortPath(diagnosticsExport.output)}</strong></div>
                  <div><span>Size</span><strong>{formatBytes(diagnosticsExport.bytes)} ({diagnosticsExport.bytes.toLocaleString()} bytes)</strong></div>
                  <div className="report-facts__digest"><span>SHA-256</span><code>{diagnosticsExport.sha256}</code></div>
                  <div><span>Retention</span><strong>Automatic deletion starts after 14 days; receipt deadline is 15 days</strong></div>
                </div>
                <div className="report-contents">
                  <strong>Included entries ({diagnosticsExport.included.length})</strong>
                  {diagnosticsExport.included.length > 0
                    ? <ul>{diagnosticsExport.included.map((entry) => <li key={entry.entry}><span>{entry.entry}</span><small>{formatBytes(entry.bytes)}</small></li>)}</ul>
                    : <p>No run or benchmark evidence is present; the ZIP contains only its disclosure and manifest.</p>}
                </div>
                <p>Game and mod files, saves, logs and crash dumps, caches, JFR, screenshots, audio, unknown files, binary content, and symlinks stay excluded. Home-directory paths are replaced with <code>&lt;home&gt;</code>.</p>
                {diagnosticsExport.skipped.length > 0 && <p>{diagnosticsExport.skipped.length} present source file{diagnosticsExport.skipped.length === 1 ? " was" : "s were"} skipped under the disclosed limits.</p>}
                {reportError && <p className="activation-warning">{reportError}</p>}
                {reportUploading && (
                  <div className="report-progress" role="progressbar" aria-label="Run report upload" aria-valuemin={0} aria-valuemax={diagnosticsExport.bytes} aria-valuenow={reportUploadedBytes}>
                    <span style={{ width: `${Math.min(100, diagnosticsExport.bytes > 0 ? reportUploadedBytes / diagnosticsExport.bytes * 100 : 0)}%` }} />
                    <strong>{reportFinalizing ? "Archive accepted · finishing receipt…" : reportCancelling ? "Stopping…" : `${formatBytes(reportUploadedBytes)} of ${formatBytes(diagnosticsExport.bytes)}`}</strong>
                  </div>
                )}
                <div className="activation-review__footer">
                  <span><ShieldIcon /> The native host rechecks the file, size, and SHA-256 immediately before upload.</span>
                  {reportUploading
                    ? <button className="button button--quiet" type="button" onClick={() => void stopRunReport()} disabled={reportCancelling || reportFinalizing}>{reportFinalizing ? "Finishing receipt…" : reportCancelling ? "Stopping…" : "Cancel upload"}</button>
                    : <button className="button button--primary" type="button" onClick={() => void submitRunReport()} disabled={!reportIntake?.configured}>Send this exact ZIP</button>}
                </div>
              </section>
            )}

            {reportReceipt && (
              <section className="card report-receipt" aria-label="Run report receipt">
                <div className="card__heading">
                  <div><p className="eyebrow">Accepted</p><h2>Run report {reportReceipt.caseId}</h2></div>
                  <CheckIcon className="settings-check" />
                </div>
                <p>The intake accepted {formatBytes(reportReceipt.bytes)} with the same SHA-256. Preflight keeps this deletion receipt on this computer until you delete the report, dismiss the receipt, or its deadline passes.</p>
                <div className="report-facts">
                  <div><span>Received</span><strong>{new Date(reportReceipt.receivedAt).toLocaleString()}</strong></div>
                  <div><span>Retention deadline</span><strong>{new Date(reportReceipt.retentionDeadline).toLocaleString()}</strong></div>
                  <div className="report-facts__digest"><span>SHA-256</span><code>{reportReceipt.sha256}</code></div>
                </div>
                <div className="update-actions">
                  <button className="button button--quiet button--compact" type="button" onClick={() => void copyRunReportReceipt()}>Copy receipt</button>
                  <button className="button button--quiet button--compact" type="button" onClick={() => { setReportReceipt(null); setMessage("Receipt dismissed. Its local deletion authorization was removed."); }}>I saved this receipt</button>
                  <button className="button button--danger button--compact" type="button" onClick={() => void removeRunReport()} disabled={reportDeleting}>{reportDeleting ? "Deleting…" : "Delete uploaded report"}</button>
                </div>
              </section>
            )}

            <details className="card settings-disclosure automation-card">
              <summary>
                <span><strong>Automated game test</strong><small>{desktopSmokeProbe?.probe.ready ? "Ready" : "Optional compatibility check"}</small></span>
              </summary>
              <div className="settings-disclosure__body">
                <p>{desktopSmokeProbe === null
                  ? "Check whether Preflight can control one exact game process, collect bounded evidence, and close it after the test. Nothing launches during this check."
                  : desktopSmokeProbe.probe.ready
                    ? `Ready through ${desktopSmokeProbe.probe.driver?.id ?? "the platform driver"}. The test remains opt-in and hasn’t started a game.`
                    : desktopSmokeProbe.probe.diagnostics[0] ?? "Automated testing isn’t available on this system yet."}</p>
                {desktopSmokeProbe?.probe.ready && <small>{desktopSmokeProbe.probe.driver?.capabilities.join(" · ")}</small>}
                <div className="update-actions">
                  <button className="button button--quiet button--compact" type="button" onClick={() => void checkDesktopAutomation()} disabled={desktopSmokeProbeBusy || preparing || status === "running"}>
                    {desktopSmokeProbeBusy ? "Checking…" : desktopSmokeProbe ? "Check again" : "Check readiness"}
                  </button>
                  {desktopSmokeProbe?.probe.ready && !desktopSmokeRunning && <button className="button button--primary button--compact" type="button" onClick={() => setDesktopSmokeReview(true)} disabled={desktopSmokeRunning || preparing || status === "running"}>Review test</button>}
                  {desktopSmokeRunning && <button className="button button--quiet button--compact" type="button" onClick={() => void stopDesktopAutomation()}>Stop test safely</button>}
                  {desktopSmokeProbe && !desktopSmokeProbe.probe.ready && snapshot?.platform === "mac" && <button className="button button--quiet button--compact" type="button" onClick={() => void openDesktopAccessibilitySettings().catch((error) => setMessage(String(error)))}>Open Accessibility settings</button>}
                </div>
                {desktopSmokeRunDirectory && <small>Latest evidence: {shortPath(desktopSmokeRunDirectory)}</small>}
              </div>
            </details>

            <details className="card settings-disclosure removal-card">
              <summary>
                <span><strong>Remove Preflight</strong><small>Launcher only or all local data</small></span>
              </summary>
              <div className="settings-disclosure__body">
                <p>Every removal is previewed first. Starsector, mods, saves, and game settings stay untouched.</p>
                <div className="removal-choices">
                  <div><strong>Launch integration</strong><span>Remove Preflight’s installed command engine and OS launch shortcuts. Keep prepared data and diagnostics.</span><button className="button button--quiet button--compact" type="button" onClick={() => void reviewRemoval("launcher")} disabled={removalBusy || preparing || status === "running"}>Review launcher removal</button></div>
                  <div><strong>All Preflight data</strong><span>Remove launch integrations, caches, profiles, evidence, and backups. The packaged desktop app remains for the operating system to uninstall.</span><button className="button button--quiet button--compact" type="button" onClick={() => void reviewRemoval("all-data")} disabled={removalBusy || preparing || status === "running"}>Review all data removal</button></div>
                </div>
              </div>
            </details>

            {removalPlan && (
              <section className="card removal-review" aria-label="Removal review">
                <div className="activation-review__heading">
                  <div><p className="eyebrow">Nothing removed yet</p><h2>{removalPlan.scope === "all-data" ? "Remove all Preflight data?" : "Remove launch integration?"}</h2></div>
                  <button className="text-button" type="button" onClick={() => setRemovalPlan(null)} disabled={removalBusy}>Cancel</button>
                </div>
                <p className="cleanup-summary">{formatBytes(removalPlan.bytes)} across {removalPlan.files.toLocaleString()} files. The plan was measured from the paths below.</p>
                <div className="cleanup-groups">{removalPlan.targets.map((target) => <div key={`${target.kind}:${target.path}`}><span>{target.label}</span><strong>{formatBytes(target.bytes)} · {shortPath(target.path)}</strong></div>)}</div>
                <div className="activation-review__footer">
                  <span><ShieldIcon /> Starsector, mods, saves, and game settings aren’t removal targets.</span>
                  <button className="button button--danger" type="button" onClick={() => void removePreflight()} disabled={!removalPlan.safe || removalPlan.targets.length === 0 || removalBusy}>{removalBusy ? "Removing…" : removalPlan.targets.length === 0 ? "Nothing to remove" : removalPlan.scope === "all-data" ? "Remove all Preflight data" : "Remove launch integration"}</button>
                </div>
              </section>
            )}
          </div>
        )}

        <footer>
          <span>Preflight {snapshot?.engineVersion ?? "…"}</span>
          <span>Unofficial · Not affiliated with Fractal Softworks</span>
        </footer>
      </main>
    </div>
  );
}
