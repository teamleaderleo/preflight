import { useCallback, useEffect, useState } from "react";
import { open } from "@tauri-apps/plugin-dialog";
import { listen } from "@tauri-apps/api/event";
import {
  applyCacheCleanup,
  applyRemoval,
  getCacheCleanup,
  getLaunchSettings,
  getRemovalPlan,
  getSnapshot,
  isDesktopHost,
  openDesktopAccessibilitySettings,
  startGame,
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
import { useDesktopAutomation } from "./useDesktopAutomation";
import { useDiagnosticsReport } from "./useDiagnosticsReport";
import { resourcePresets, usePreparation } from "./usePreparation";
import { useProfiles } from "./useProfiles";
import { useSignedUpdates } from "./useSignedUpdates";
import type {
  AppStatus,
  CacheCleanupPlan,
  DesktopSnapshot,
  LaunchSettings,
  LaunchSettingsUpdate,
  OptimizationPreset,
  RemovalPlan,
  RemovalScope,
  RunStateEvent,
} from "./types";

export { isCurrentProfilePrepared } from "./usePreparation";

type Page = "home" | "launch" | "prepare" | "reports" | "profiles" | "settings";

const optimizationPresets: Array<{
  id: OptimizationPreset;
  label: string;
  description: string;
  badge: string;
}> = [
  {
    id: "recommended",
    label: "Recommended",
    description: "All reviewed startup and gameplay optimizations. True-size textures.",
    badge: "Default",
  },
  {
    id: "conservative",
    label: "Conservative",
    description: "Portable startup caches and padded textures. Gameplay adapters stay off.",
    badge: "Fallback",
  },
  {
    id: "off",
    label: "Off",
    description: "Wrapper and bounded process report only.",
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

function pageTitle(page: Page, status: AppStatus, preparing: boolean, isReady: boolean, needsPreparation: boolean): string {
  if (page === "launch") return "Game settings";
  if (page === "prepare") return preparing ? "Preparing…" : "Preflight";
  if (page === "reports") return "Run reports";
  if (page === "profiles") return "Profiles";
  if (page === "settings") return "Settings";
  if (preparing) return "Preparing…";
  if (status === "loading") return "Checking…";
  if (status === "running") return "Running";
  if (!isReady) return "Setup";
  return needsPreparation ? "Preparation needed" : "Ready";
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

export default function App() {
  const [snapshot, setSnapshot] = useState<DesktopSnapshot | null>(null);
  const [status, setStatus] = useState<AppStatus>("loading");
  const [message, setMessage] = useState("");
  const [page, setPage] = useState<Page>("home");
  const [optimizationPreset, setOptimizationPreset] = useState<OptimizationPreset>(savedOptimizationPreset);
  const [cleanupPlan, setCleanupPlan] = useState<CacheCleanupPlan | null>(null);
  const [cleanupBusy, setCleanupBusy] = useState(false);
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
  const {
    cache,
    cacheLoading,
    preparationCancelling,
    preparationPercent,
    preparationPhaseLabel,
    preparationPlan,
    preparationPlanLoading,
    preparing,
    profilePrepared,
    resourcePreset,
    textureStorage,
    clearCache,
    invalidatePreparationPlan,
    prepare,
    refreshCache,
    setResourcePreset,
    setTextureStorage,
    stopPreparation,
  } = usePreparation(
    snapshot?.selected?.installRoot,
    page === "prepare",
    optimizationPreset,
    launch,
    setMessage,
  );
  const {
    activationPlan,
    profileBusy,
    profileName,
    profiles,
    profilesLoading,
    applyProfile,
    clearProfiles,
    refreshProfiles,
    reviewProfile,
    saveCurrentProfile,
    setActivationPlan,
    setProfileName,
  } = useProfiles(
    snapshot?.selected?.installRoot,
    page === "home" || page === "profiles",
    refresh,
    refreshCache,
    setMessage,
  );
  const isReady = Boolean(snapshot?.ready && snapshot.selected);
  const {
    desktopSmokeProbe,
    desktopSmokeProbeBusy,
    desktopSmokeReview,
    desktopSmokeRunDirectory,
    desktopSmokeRunning,
    checkDesktopAutomation,
    runDesktopAutomation,
    setDesktopSmokeReview,
    stopDesktopAutomation,
  } = useDesktopAutomation({
    game: snapshot?.selected?.installRoot,
    installationReady: isReady,
    announce: setMessage,
    displayPath: shortPath,
    refreshInstallation: refresh,
    setStatus,
  });
  const {
    diagnosticsBusy,
    diagnosticsExport,
    reportCancelling,
    reportDeleting,
    reportError,
    reportFinalizing,
    reportIntake,
    reportReceipt,
    reportReview,
    reportUploadedBytes,
    reportUploading,
    copyRunReportReceipt,
    dismissRunReportReceipt,
    removeRunReport,
    saveDiagnostics,
    setReportReview,
    stopRunReport,
    submitRunReport,
  } = useDiagnosticsReport(page === "reports", setMessage);
  const [launcherSettings, setLauncherSettings] = useState<LaunchSettings | null>(null);
  const [launcherDraft, setLauncherDraft] = useState<LaunchSettingsUpdate | null>(null);
  const [launcherSettingsLoading, setLauncherSettingsLoading] = useState(false);
  const [launcherSettingsSaving, setLauncherSettingsSaving] = useState(false);
  const [removalPlan, setRemovalPlan] = useState<RemovalPlan | null>(null);
  const [removalBusy, setRemovalBusy] = useState(false);
  const {
    updateChecking,
    updateError,
    updateInstalling,
    updateProgress,
    updateStatus,
    checkUpdates,
    installSignedUpdate,
  } = useSignedUpdates(status === "ready", preparing || status === "running", setMessage);

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
        const outcome = payload.success ? "Welcome back. Your run was tucked away safely." : "The game closed with an error. Your run notes are still safe.";
        void refresh(snapshot?.selected?.installRoot).then(() => setMessage(outcome));
      }
    }).then((unlisten) => {
      stopListening = unlisten;
    });
    return () => stopListening?.();
  }, [refresh, snapshot?.ready, snapshot?.selected?.installRoot]);

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
        memoryMiB: result.memory.editable ? result.memory.maxHeapMiB : null,
      });
    } catch (error) {
      setMessage(String(error));
    } finally {
      setLauncherSettingsLoading(false);
    }
  }, [snapshot?.selected?.installRoot]);

  useEffect(() => {
    if (page === "home" || page === "launch") void refreshLauncherSettings();
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
      invalidatePreparationPlan();
    } catch (error) {
      setMessage(String(error));
    } finally {
      setCleanupBusy(false);
    }
  };

  const saveLauncherSettings = async () => {
    const game = snapshot?.selected?.installRoot;
    if (!game || !launcherDraft) return;
    setLauncherSettingsSaving(true);
    setMessage("Saving game settings…");
    try {
      const result = await updateLaunchSettings(game, launcherDraft);
      setLauncherSettings(result);
      setMessage("Game settings saved. Vanilla and Preflight launches will use the same values.");
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
        clearCache();
        clearProfiles();
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

  const needsPreparation = optimizationPreset !== "off" && !profilePrepared;
  const selectedOptimization = optimizationPresets.find((preset) => preset.id === optimizationPreset)
    ?? optimizationPresets[0];
  const activeProfile = profiles?.profiles.find((profile) => profile.active) ?? null;
  const launchSettingsDirty = Boolean(launcherDraft && launcherSettings && (
    launcherDraft.resolution !== (launcherSettings.preferences.resolution ?? launcherSettings.settings?.resolution ?? "1280x720")
    || launcherDraft.fullscreen !== launcherSettings.preferences.fullscreen
    || launcherDraft.sound !== launcherSettings.preferences.sound
    || launcherDraft.antialiasingSamples !== (launcherSettings.preferences.antialiasingSamples ?? 0)
    || launcherDraft.uiScale !== (launcherSettings.preferences.uiScale ?? 1)
    || launcherDraft.battleSize !== (launcherSettings.preferences.battleSize ?? launcherSettings.limits.battleSizeDefault ?? 400)
    || (launcherDraft.memoryMiB !== null && launcherDraft.memoryMiB !== launcherSettings.memory.maxHeapMiB)
  ));
  const title = pageTitle(page, status, preparing, isReady, needsPreparation);
  const startActive = page === "home" || page === "launch";

  useEffect(() => {
    document.documentElement.scrollTop = 0;
    document.body.scrollTop = 0;
  }, [page]);

  return (
    <div
      className="app-shell"
      onPointerMove={(event) => {
        event.currentTarget.style.setProperty("--grid-x", `${event.clientX}px`);
        event.currentTarget.style.setProperty("--grid-y", `${event.clientY}px`);
      }}
      onPointerLeave={(event) => {
        event.currentTarget.style.setProperty("--grid-x", "-1000px");
        event.currentTarget.style.setProperty("--grid-y", "-1000px");
      }}
    >
      <aside className="sidebar">
        <Logo />
        <nav className="nav" aria-label="Main navigation">
          <button className={`nav__item ${startActive ? "nav__item--active" : ""}`} type="button" aria-current={startActive ? "page" : undefined} onClick={() => setPage("home")}>
            <HomeIcon />
            <span>Home</span>
          </button>
          <button className={`nav__item ${page === "prepare" ? "nav__item--active" : ""}`} type="button" aria-current={page === "prepare" ? "page" : undefined} onClick={() => setPage("prepare")} disabled={!isReady}>
            <SparklesIcon />
            <span>Preflight</span>
          </button>
          <button className={`nav__item ${page === "reports" ? "nav__item--active" : ""}`} type="button" aria-current={page === "reports" ? "page" : undefined} onClick={() => setPage("reports")}>
            <ShieldIcon />
            <span>Run reports</span>
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

        <div className={`page-viewport page-viewport--${page}`}>
        {page === "home" ? <>
        <section className={`launch-console card ${isReady ? "launch-console--ready" : "launch-console--setup"}`}>
          <div className="launch-console__primary">
            <div className={`status-chip ${isReady ? "status-chip--ready" : ""}`}>
              {isReady && !needsPreparation ? <CheckIcon /> : <SparklesIcon />}
              {status === "running" ? "Game running" : preparing ? "Preparing profile" : needsPreparation ? "Profile changed" : isReady ? "Prepared" : "Installation required"}
            </div>
            {!isReady && <h2>Choose the game folder</h2>}
            {!isReady || needsPreparation ? <p>{needsPreparation
              ? "Build reusable data for this mod profile first."
              : "Select the folder that contains the Starsector launcher."}</p> : null}
            <div className="launch-console__actions">
              {isReady ? (
                <button className="button button--primary button--launch" type="button" onClick={() => void (needsPreparation ? prepare(true) : launch())} disabled={status === "running" || status === "loading" || preparing || cacheLoading || (needsPreparation && (preparationPlanLoading || !preparationPlan?.safeToPrepare))}>
                  {needsPreparation ? <SparklesIcon /> : <PlayIcon />}
                  {status === "running" ? "Starsector is running" : preparing ? "Preparing…" : cacheLoading ? "Checking profile…" : preparationPlanLoading && needsPreparation ? "Calculating space…" : needsPreparation ? "Prepare and launch" : "Launch Starsector"}
                </button>
              ) : (
                <button className="button button--primary" type="button" onClick={() => void chooseInstall()} disabled={status === "loading"}>
                  <FolderIcon />
                  Choose game folder
                </button>
              )}
            </div>
            {isReady && (
              <div className="launch-console__note">
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
          {isReady && launcherDraft && launcherSettings ? (
            <div className="quick-settings" aria-label="Common game settings">
              <div className="quick-settings__heading">
                <strong>Game setup</strong>
                <button className="text-button" type="button" onClick={() => setPage("launch")}>All settings <ArrowIcon /></button>
              </div>
              <div className="quick-settings__grid">
                <label className="quick-control" htmlFor="home-resolution">
                  <span>Resolution</span>
                  <input id="home-resolution" aria-label="Home resolution" value={launcherDraft.resolution} onChange={(event) => setLauncherDraft({ ...launcherDraft, resolution: event.target.value })} inputMode="text" spellCheck={false} />
                </label>
                <label className="quick-control" htmlFor="home-battle-size">
                  <span>Battle size</span>
                  <input id="home-battle-size" aria-label="Home battle size" type="number" min={launcherSettings.limits.battleSizeMin ?? 1} max={launcherSettings.limits.battleSizeMax ?? Math.max(launcherDraft.battleSize, 400)} step="10" value={launcherDraft.battleSize} onChange={(event) => setLauncherDraft({ ...launcherDraft, battleSize: Number(event.target.value) })} />
                </label>
                {launcherSettings.memory.editable && launcherDraft.memoryMiB !== null ? (
                  <label className="quick-control" htmlFor="home-memory">
                    <span>RAM</span>
                    <select id="home-memory" aria-label="Home game memory" value={launcherDraft.memoryMiB} onChange={(event) => setLauncherDraft({ ...launcherDraft, memoryMiB: Number(event.target.value) })}>
                      {Array.from(new Set([2048, 4096, 6144, 8192, 12288, 16384, launcherDraft.memoryMiB])).sort((a, b) => a - b).map((memory) => <option value={memory} key={memory}>{memory / 1024} GB</option>)}
                    </select>
                  </label>
                ) : (
                  <div className="quick-control quick-control--read-only" title={launcherSettings.memory.reason ?? undefined}>
                    <span>RAM</span><strong>{launcherSettings.memory.maxHeapMiB ? `${launcherSettings.memory.maxHeapMiB / 1024} GB` : "External"}</strong>
                  </div>
                )}
              </div>
              <div className="quick-settings__toggles">
                <label><input type="checkbox" aria-label="Home fullscreen" checked={launcherDraft.fullscreen} onChange={(event) => setLauncherDraft({ ...launcherDraft, fullscreen: event.target.checked })} /><span>Fullscreen</span></label>
                <label><input type="checkbox" aria-label="Home sound" checked={launcherDraft.sound} onChange={(event) => setLauncherDraft({ ...launcherDraft, sound: event.target.checked })} /><span>Sound</span></label>
              </div>
              <button className={`button ${launchSettingsDirty ? "button--primary" : "button--quiet"} quick-settings__save`} type="button" onClick={() => void saveLauncherSettings()} disabled={!launchSettingsDirty || launcherSettingsSaving || status === "running" || preparing}>
                <CheckIcon />{launcherSettingsSaving ? "Saving…" : launchSettingsDirty ? "Apply changes" : "Settings applied"}
              </button>
            </div>
          ) : isReady ? <div className="quick-settings quick-settings--loading">{launcherSettingsLoading ? "Reading game settings…" : "Game settings unavailable"}</div> : null}
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

        <section className="card home-overview" aria-label="Current Preflight setup">
          <div className="home-fact">
            <span>Profile</span>
            {profiles && profiles.profiles.length > 0 ? (
              <select className="home-profile-select" aria-label="Active profile" value={activeProfile?.name ?? ""} onChange={(event) => {
                const name = event.target.value;
                if (!name || name === activeProfile?.name) return;
                setPage("profiles");
                void reviewProfile(name);
              }}>
                {!activeProfile && <option value="">Current mod list</option>}
                {profiles.profiles.map((profile) => <option value={profile.name} key={profile.name}>{profile.name}</option>)}
              </select>
            ) : <strong>{profilesLoading ? "Reading…" : `${profiles?.enabledMods.length ?? 0} enabled mods`}</strong>}
            <button className="text-button" type="button" onClick={() => setPage("profiles")} disabled={!isReady}>Manage <ArrowIcon /></button>
          </div>
          <div className="home-fact">
            <span>Preflight data</span>
            <strong>{cacheLoading ? "Reading…" : formatBytes(cache?.total.bytes ?? 0)}</strong>
            <button className="text-button" type="button" onClick={() => setPage("prepare")} disabled={!isReady}>Storage <ArrowIcon /></button>
          </div>
          <div className="home-fact home-fact--installation">
            <span>Installation</span>
            <strong>{isReady && snapshot?.selected ? shortPath(snapshot.selected.installRoot) : "Not selected"}</strong>
            <small>{isReady && snapshot ? `${friendlyPlatform(snapshot.platform)} · ${snapshot.selected?.kind.replace("-", " ")}` : "Choose the game folder to begin."}</small>
            <button type="button" className="text-button" onClick={() => void chooseInstall()} aria-label="Change Starsector installation">Change <ArrowIcon /></button>
          </div>
        </section>
        </> : page === "launch" ? (
          <div className="launch-page">
            {message && (
              <div className="notice" role="status"><span>✦</span><p>{message}</p></div>
            )}

            {launcherDraft && launcherSettings ? (
              <>
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
                    <label className="setting-field" htmlFor="launch-memory">
                      <span><strong>Game memory</strong><small>{launcherSettings.memory.source ? `Owned by ${shortPath(launcherSettings.memory.source)}` : launcherSettings.memory.reason ?? "Managed by the selected launcher"}</small></span>
                      {launcherSettings.memory.editable && launcherDraft.memoryMiB !== null ? (
                        <select id="launch-memory" aria-label="Game memory" value={launcherDraft.memoryMiB} onChange={(event) => setLauncherDraft({ ...launcherDraft, memoryMiB: Number(event.target.value) })}>
                          {Array.from(new Set([2048, 4096, 6144, 8192, 12288, 16384, launcherDraft.memoryMiB])).sort((a, b) => a - b).map((memory) => <option value={memory} key={memory}>{memory / 1024} GB</option>)}
                        </select>
                      ) : <strong>{launcherSettings.memory.maxHeapMiB ? `${launcherSettings.memory.maxHeapMiB / 1024} GB` : "Unavailable"}</strong>}
                    </label>
                  </section>
                </div>

                {(launcherSettings.preferences.diagnostics.length > 0 || launcherSettings.limits.diagnostics.length > 0 || launcherSettings.memory.diagnostics.length > 0) && (
                  <section className="card launch-diagnostics">
                    {[...launcherSettings.preferences.diagnostics, ...launcherSettings.limits.diagnostics, ...launcherSettings.memory.diagnostics].map((diagnostic) => <p key={diagnostic}>{diagnostic}</p>)}
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
            {message && (
              <div className="notice" role="status"><span>✦</span><p>{message}</p></div>
            )}

            <section className="card optimization-card">
              <div className="card__heading">
                <div>
                  <p className="eyebrow">Runtime policy</p>
                  <h2>Optimizations</h2>
                </div>
                <div className={`tiny-status ${optimizationPreset !== "off" ? "tiny-status--good" : ""}`}>
                  <span />
                  {selectedOptimization.label}
                </div>
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

            <div className="prepare-grid">
              <section className="card prepare-options">
                <div className="card__heading">
                  <div><p className="eyebrow">Space and speed</p><h2>Texture storage</h2></div>
                  <div className={`tiny-status ${cache?.currentProfileFingerprint ? "tiny-status--good" : ""}`}>
                    <span />
                    {cacheLoading ? "Checking" : cache?.currentProfileFingerprint ? "Profile detected" : "Not prepared"}
                  </div>
                </div>
                <label className={`choice-card ${textureStorage === "balanced" ? "choice-card--selected" : ""}`}>
                  <input type="radio" name="texture-storage" checked={textureStorage === "balanced"} onChange={() => setTextureStorage("balanced")} />
                  <span><strong>Balanced</strong><small>Lossless LZ4; raw only when compression doesn’t help</small></span>
                  <b>Default</b>
                </label>
                <label className={`choice-card ${textureStorage === "fastest" ? "choice-card--selected" : ""}`}>
                  <input type="radio" name="texture-storage" checked={textureStorage === "fastest"} onChange={() => setTextureStorage("fastest")} />
                  <span><strong>Fastest</strong><small>Raw upload-ready pixels; several GB more for a small startup gain</small></span>
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
            {message && (
              <div className="notice" role="status"><span>✦</span><p>{message}</p></div>
            )}

            <div className="profiles-grid">
              <section className="card profile-list-card">
                <div className="card__heading">
                  <div><p className="eyebrow">This installation</p><h2>Saved profiles</h2></div>
                  <div className="card__heading-actions">
                    <div className={`tiny-status ${profiles?.profiles.some((profile) => profile.active) ? "tiny-status--good" : ""}`}>
                      <span />
                      {profilesLoading ? "Checking" : `${profiles?.profiles.length ?? 0} saved`}
                    </div>
                    <button className="icon-button icon-button--small" type="button" onClick={() => void refreshProfiles()} aria-label="Refresh saved profiles" disabled={profilesLoading}>
                      <RefreshIcon className={profilesLoading ? "spin" : ""} />
                    </button>
                  </div>
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
                <p>Names and load order only. Mod files stay where they are.</p>
                <label htmlFor="profile-name">Profile name</label>
                <input id="profile-name" value={profileName} onChange={(event) => setProfileName(event.target.value)} placeholder="e.g. Heavy campaign" maxLength={96} />
                <button className="button button--primary" type="button" disabled={!profileName.trim() || profileBusy} onClick={() => void saveCurrentProfile()}>
                  Save current profile
                </button>
                <div className="profile-cache-note"><SparklesIcon /><span>Matching profiles reuse prepared caches automatically.</span></div>
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

            <div className="settings-overview">
              {page === "settings" && <section className="card update-card">
                <div className="card__heading">
                  <div><p className="eyebrow">Application</p><h2>{updateStatus?.available ? `Preflight ${updateStatus.version}` : "Updates"}</h2></div>
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
                <small>Release signatures are checked before installation. A failed check leaves the current version untouched.</small>
              </section>}

              {page === "reports" && <section className="card diagnostics-action">
                <div>
                  <strong>{diagnosticsExport ? "Diagnostics ready" : "Diagnostics"}</strong>
                  <span>{diagnosticsExport
                    ? `${formatBytes(diagnosticsExport.bytes)} · ${shortPath(diagnosticsExport.output)}`
                    : "Paths are redacted. Review contents before saving or sending."}</span>
                </div>
                <div className="report-actions">
                  <button className={`button ${diagnosticsExport ? "button--quiet" : "button--primary"}`} type="button" onClick={() => void saveDiagnostics()} disabled={diagnosticsBusy || reportUploading}>
                    <FolderIcon />{diagnosticsBusy ? "Saving…" : diagnosticsExport ? "Save another ZIP" : "Save diagnostics"}
                  </button>
                  {diagnosticsExport && (
                    <button className="button button--primary" type="button" onClick={() => setReportReview(true)} disabled={!reportIntake?.configured || reportUploading || reportReceipt !== null}>
                      {reportReceipt ? "Receipt below" : "Review send"}
                    </button>
                  )}
                </div>
              </section>}
            </div>

            {page === "reports" && desktopSmokeReview && (
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

            {page === "reports" && <details className="card settings-disclosure">
              <summary>
                <span><strong>Diagnostic contents</strong><small>Included and excluded data</small></span>
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
            </details>}

            {page === "reports" && diagnosticsExport && reportIntake && !reportIntake.configured && (
              <p className="report-unavailable"><ShieldIcon /> {reportIntake.reason ?? "Run-report sending isn't configured in this build."} The ZIP remains available to inspect and share manually.</p>
            )}

            {page === "reports" && reportReview && diagnosticsExport && (
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

            {page === "reports" && reportReceipt && (
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
                  <button className="button button--quiet button--compact" type="button" onClick={dismissRunReportReceipt}>I saved this receipt</button>
                  <button className="button button--danger button--compact" type="button" onClick={() => void removeRunReport()} disabled={reportDeleting}>{reportDeleting ? "Deleting…" : "Delete uploaded report"}</button>
                </div>
              </section>
            )}

            {page === "reports" && <details className="card settings-disclosure automation-card">
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
            </details>}

            {page === "settings" && <details className="card settings-disclosure removal-card">
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
            </details>}

            {page === "settings" && removalPlan && (
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
        </div>

        <footer>
          <span>Preflight {snapshot?.engineVersion ?? "…"}</span>
          <span>Unofficial · Not affiliated with Fractal Softworks</span>
        </footer>
      </main>
    </div>
  );
}
