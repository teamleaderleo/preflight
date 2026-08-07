import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { open, save as saveFile } from "@tauri-apps/plugin-dialog";
import { listen } from "@tauri-apps/api/event";
import {
  activateProfile,
  cancelPreparation,
  exportDiagnostics,
  getCache,
  getLaunchSettings,
  getPreparationPlan,
  getProfiles,
  getSnapshot,
  isDesktopHost,
  saveProfile,
  startGame,
  startPreparation,
  updateLaunchSettings,
} from "./bridge";
import {
  ArrowIcon,
  CheckIcon,
  ClockIcon,
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
  DesktopSnapshot,
  DiagnosticsExport,
  LaunchSettings,
  LaunchSettingsUpdate,
  OptimizationPreset,
  PreparationStoragePlan,
  PreparationStateEvent,
  PreparationProgressEvent,
  ProfileActivationPlan,
  ProfileList,
  RunStateEvent,
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
  gentle: { workers: 2, memoryMib: 128, label: "Gentle" },
  balanced: { workers: 4, memoryMib: 256, label: "Balanced" },
  eager: { workers: 8, memoryMib: 512, label: "Eager" },
} as const;

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
  const [launcherSettings, setLauncherSettings] = useState<LaunchSettings | null>(null);
  const [launcherDraft, setLauncherDraft] = useState<LaunchSettingsUpdate | null>(null);
  const [launcherSettingsLoading, setLauncherSettingsLoading] = useState(false);
  const [launcherSettingsSaving, setLauncherSettingsSaving] = useState(false);

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
        setMessage(payload.success ? "Welcome back. Your run was tucked away safely." : "The game closed with an error. Your run notes are still safe.");
        void refresh(snapshot?.selected?.installRoot);
      }
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
      setMessage(`Saved ${result.files} disclosed files. Inspect the ZIP before sharing it.`);
    } catch (error) {
      setMessage(String(error));
    } finally {
      setDiagnosticsBusy(false);
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

  const isReady = Boolean(snapshot?.ready && snapshot.selected);
  const profilePrepared = isCurrentProfilePrepared(cache);
  const needsPreparation = optimizationPreset !== "off" && !profilePrepared;
  const selectedOptimization = optimizationPresets.find((preset) => preset.id === optimizationPreset)
    ?? optimizationPresets[0];
  const title = useMemo(() => {
    if (page === "launch") return "Starsector launch settings";
    if (page === "prepare") return preparing ? "Warming the flight deck…" : "Prepare your profile";
    if (page === "profiles") return "Your saved flight plans";
    if (page === "settings") return "Support and diagnostics";
    if (preparing) return "Preparing your first launch…";
    if (status === "loading") return "Checking the launch pad…";
    if (status === "running") return "You’re cleared for adventure";
    if (isReady) return "Your launch pad is cozy and ready";
    return "Let’s find your Starsector home";
  }, [isReady, page, preparing, status]);

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
          <button className="nav__item" type="button" disabled title="Coming in the next desktop slice">
            <ClockIcon />
            <span>Runs</span>
            <small>Soon</small>
          </button>
        </nav>
        <div className="sidebar__footer">
          <button className={`nav__item ${page === "settings" ? "nav__item--active" : ""}`} type="button" aria-current={page === "settings" ? "page" : undefined} onClick={() => setPage("settings")}>
            <SettingsIcon />
            <span>Settings</span>
          </button>
          <div className="alpha-pill"><span /> Desktop alpha</div>
        </div>
      </aside>

      <main className="main">
        <header className="topbar">
          <div>
            <p className="eyebrow">Good evening, captain</p>
            <h1>{title}</h1>
          </div>
          <button className="icon-button" type="button" onClick={() => void refresh(snapshot?.selected?.installRoot)} aria-label="Refresh installation status" disabled={status === "loading"}>
            <RefreshIcon className={status === "loading" ? "spin" : ""} />
          </button>
        </header>

        {page === "home" ? <>
        <section className={`hero card ${isReady ? "hero--ready" : "hero--setup"}`}>
          <div className="hero__copy">
            <div className={`status-chip ${isReady ? "status-chip--ready" : ""}`}>
              {isReady ? <CheckIcon /> : <SparklesIcon />}
              {status === "running" ? "Game running" : preparing ? "Preparing profile" : isReady ? "All systems comfy" : "A tiny bit of setup"}
            </div>
            <h2>{isReady ? needsPreparation ? "Prepare once, then launch faster." : "Ready when you are." : "Show Preflight where the game lives."}</h2>
            <p>
              {isReady
                ? needsPreparation
                  ? "Preflight found your installation. The first launch can prepare the exact current mod profile, then open the game automatically."
                  : "Preflight found your installation and can launch it with run notes enabled. Your game files, mods, and saves stay exactly where they are."
                : "Pick the folder that contains Starsector. Preflight will check it gently and remember the way back."}
            </p>
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
                <strong>{selectedOptimization.label} optimizations</strong>
                <span>{needsPreparation
                  ? preparationPlanLoading
                    ? "Reading the winning textures and calculating a safe disk requirement…"
                    : preparationPlan?.safeToPrepare
                      ? `${textureStorage === "balanced" ? "Balanced" : "Fastest"} predicts ${formatBytes(preparationPlan.predictedAdditionalBytes)} additional; ${formatBytes(preparationPlan.usableBytes)} is available.`
                      : preparationPlan?.refusalReason ?? "Storage must be calculated before preparation."
                  : profilePrepared
                    ? `Current profile prepared · ${formatBytes(cache?.profiles.find((profile) => profile.current)?.bytes ?? 0)}`
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

        {message && (
          <div className={`notice ${status === "error" ? "notice--error" : ""}`} role="status">
            <span>{status === "error" ? "!" : "✦"}</span>
            <p>{message}</p>
            {status === "error" && <button type="button" onClick={() => void refresh()}>Try again</button>}
          </div>
        )}

        <div className="content-grid">
          <section className="card installation-card">
            <div className="card__heading">
              <div>
                <p className="eyebrow">Game home</p>
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

          <section className="card next-card">
            <div className="card__heading">
              <div>
                <p className="eyebrow">Ready now</p>
                <h2>Prepare your voyage</h2>
              </div>
              <SparklesIcon className="heading-sparkle" />
            </div>
            <p>Warm the safe caches before launch and get a clear, plain-English summary of what changed.</p>
            <div className="feature-row">
              <span className="feature-dot feature-dot--peach" />
              <div><strong>One gentle button</strong><span>Useful defaults, details when you want them</span></div>
              <button className="text-button" type="button" onClick={() => setPage("prepare")}>Open <ArrowIcon /></button>
            </div>
          </section>
        </div>

        <section className="safety card">
          <div className="safety__icon"><ShieldIcon /></div>
          <div>
            <strong>Your save is sacred.</strong>
            <p>Preflight never rewrites game binaries, mods, or saves. A profile switch changes only the enabled-mod list after an exact review and backup.</p>
          </div>
          <span className="safety__check"><CheckIcon /> Narrow changes only</span>
        </section>
        </> : page === "launch" ? (
          <div className="launch-page">
            <section className="card launch-intro">
              <div>
                <p className="eyebrow">The game’s own preferences</p>
                <h2>One setup, whichever launcher you use</h2>
                <p>These are the same values Starsector’s vanilla launcher and in-game settings save. Preflight does not patch a display or combat hook to apply them.</p>
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
                      <p className="eyebrow">Optimization level</p>
                      <h2>Choose the boundary, not individual patches</h2>
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
                  <p className="optimization-card__note">Every exact adapter still declines safely when the installed game or mod no longer matches its reviewed fingerprint.</p>
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
                      <span><strong>Resolution</strong><small>WIDTHxHEIGHT, exactly as the vanilla launcher stores it</small></span>
                      <input id="launch-resolution" aria-label="Resolution" value={launcherDraft.resolution} onChange={(event) => setLauncherDraft({ ...launcherDraft, resolution: event.target.value })} inputMode="text" spellCheck={false} />
                    </label>
                    <label className="setting-toggle">
                      <span><strong>Fullscreen</strong><small>Use Starsector’s fullscreen mode</small></span>
                      <input type="checkbox" aria-label="Fullscreen" checked={launcherDraft.fullscreen} onChange={(event) => setLauncherDraft({ ...launcherDraft, fullscreen: event.target.checked })} />
                    </label>
                    <label className="setting-toggle">
                      <span><strong>Sound</strong><small>Initialize the game’s audio system</small></span>
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
                    <p className="setting-explainer">This changes the same campaign gameplay preference as the in-game slider. Preflight respects the currently installed settings.json bounds.</p>
                    <label className="setting-slider" htmlFor="launch-battle-size">
                      <span><strong>Deployment-point budget</strong><b>{launcherDraft.battleSize}</b></span>
                      <input id="launch-battle-size" aria-label="Deployment-point budget" type="range" min={launcherSettings.limits.battleSizeMin ?? 1} max={launcherSettings.limits.battleSizeMax ?? Math.max(launcherDraft.battleSize, 400)} step="10" value={launcherDraft.battleSize} onChange={(event) => setLauncherDraft({ ...launcherDraft, battleSize: Number(event.target.value) })} />
                    </label>
                    <div className="battle-bounds">
                      <span>Minimum {launcherSettings.limits.battleSizeMin ?? "unknown"}</span>
                      <span>Game default {launcherSettings.limits.battleSizeDefault ?? "unknown"}</span>
                      <span>Maximum {launcherSettings.limits.battleSizeMax ?? "unknown"}</span>
                    </div>
                    <div className="setting-safety"><ShieldIcon /><span>A preference backup is written before every save. Game binaries, mods and saves remain untouched.</span></div>
                  </section>
                </div>

                {(launcherSettings.preferences.diagnostics.length > 0 || launcherSettings.limits.diagnostics.length > 0) && (
                  <section className="card launch-diagnostics">
                    {[...launcherSettings.preferences.diagnostics, ...launcherSettings.limits.diagnostics].map((diagnostic) => <p key={diagnostic}>{diagnostic}</p>)}
                  </section>
                )}

                <section className="card launch-save">
                  <div><strong>Use these settings everywhere</strong><span>{launcherSettings.backup ? `Previous values saved at ${shortPath(launcherSettings.backup)}` : "Vanilla and Preflight launches share this preference store."}</span></div>
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
                <p className="eyebrow">Exact current mod profile</p>
                <h2>Build once, reuse safely</h2>
                <p>Preflight prepares content-addressed caches outside the game. Changed game or mod files select new identities; missing or rejected entries use the original loader.</p>
              </div>
              <div className={`tiny-status ${cache?.currentProfileFingerprint ? "tiny-status--good" : ""}`}>
                <span />
                {cacheLoading ? "Checking" : cache?.currentProfileFingerprint ? "Profile detected" : "Not prepared"}
              </div>
            </section>

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
                </div>
                <div className="storage-groups storage-plan" aria-label="Preparation storage plan">
                  <div><span>Predicted additional</span><strong>{preparationPlanLoading ? "Calculating…" : preparationPlan ? formatBytes(preparationPlan.predictedAdditionalBytes) : "—"}</strong></div>
                  <div><span>Conservative bound</span><strong>{preparationPlan ? formatBytes(preparationPlan.upperBoundAdditionalBytes) : "—"}</strong></div>
                  <div><span>Safety reserve</span><strong>{preparationPlan ? formatBytes(preparationPlan.safetyReserveBytes) : "—"}</strong></div>
                  <div><span>Available now</span><strong>{preparationPlan ? formatBytes(preparationPlan.usableBytes) : "—"}</strong></div>
                </div>
                {preparationPlan && !preparationPlan.safeToPrepare && <p className="activation-warning">{preparationPlan.refusalReason}</p>}
                <p className="storage-note">Acceleration data and diagnostic evidence are tracked separately. Cleanup is always previewed before deletion.</p>
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
          </div>
        ) : page === "profiles" ? (
          <div className="profiles-page">
            <section className="card profiles-intro">
              <div>
                <p className="eyebrow">Named mod profiles</p>
                <h2>Change fleets without losing your place</h2>
                <p>Profiles remember the exact enabled-mod order for this installation. Switching is always previewed, refuses missing mods, and backs up the current file before applying.</p>
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
            <section className="card settings-intro">
              <div>
                <p className="eyebrow">Attachable support evidence</p>
                <h2>Make a small diagnostics ZIP</h2>
                <p>Preflight exports only allowlisted text metadata from the newest three runs and two benchmarks. The bundle explains its own contents and redactions.</p>
              </div>
              <ShieldIcon className="settings-shield" />
            </section>

            {message && (
              <div className="notice" role="status"><span>✦</span><p>{message}</p></div>
            )}

            <div className="settings-grid">
              <section className="card diagnostics-card">
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
              <section className="card diagnostics-card diagnostics-card--excluded">
                <div className="card__heading">
                  <div><p className="eyebrow">Never included</p><h2>Your actual game data</h2></div>
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

            <section className="card diagnostics-action">
              <div>
                <strong>{diagnosticsExport ? "Diagnostics are ready" : "Ready to collect support evidence"}</strong>
                <span>{diagnosticsExport ? `${formatBytes(diagnosticsExport.bytes)} · ${shortPath(diagnosticsExport.output)}` : "Home-directory paths are redacted. Other visible metadata is disclosed in the ZIP."}</span>
              </div>
              <button className="button button--primary" type="button" onClick={() => void saveDiagnostics()} disabled={diagnosticsBusy}>
                <FolderIcon />{diagnosticsBusy ? "Saving…" : "Save diagnostics bundle"}
              </button>
            </section>
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
