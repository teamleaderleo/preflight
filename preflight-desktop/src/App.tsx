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
  startGame,
  updateLaunchSettings,
} from "./bridge";
import {
  ArrowIcon,
  CheckIcon,
  FolderIcon,
  PlayIcon,
  SparklesIcon,
} from "./icons";
import { DesktopShell, type Page } from "./components/DesktopShell";
import { GameSettingsPage } from "./components/GameSettingsPage";
import { PreparationPage, optimizationPresets } from "./components/PreparationPage";
import { ProfilesPage } from "./components/ProfilesPage";
import { QuickGameSettings } from "./components/QuickGameSettings";
import { ReportsPage } from "./components/ReportsPage";
import { SettingsPage } from "./components/SettingsPage";
import { useDesktopAutomation } from "./useDesktopAutomation";
import { useDiagnosticsReport } from "./useDiagnosticsReport";
import { usePreparation } from "./usePreparation";
import { useProfiles } from "./useProfiles";
import { useSignedUpdates } from "./useSignedUpdates";
import { formatBytes, friendlyPlatform, shortPath } from "./uiFormat";
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
  const preparation = usePreparation(
    snapshot?.selected?.installRoot,
    page === "prepare",
    optimizationPreset,
    launch,
    setMessage,
  );
  const {
    cache,
    cacheLoading,
    preparationPlan,
    preparationPlanLoading,
    preparing,
    profilePrepared,
    textureStorage,
    clearCache,
    invalidatePreparationPlan,
    prepare,
    refreshCache,
  } = preparation;
  const profilesState = useProfiles(
    snapshot?.selected?.installRoot,
    page === "home" || page === "profiles",
    refresh,
    refreshCache,
    setMessage,
  );
  const {
    profiles,
    profilesLoading,
    clearProfiles,
    reviewProfile,
  } = profilesState;
  const isReady = Boolean(snapshot?.ready && snapshot.selected);
  const automation = useDesktopAutomation({
    game: snapshot?.selected?.installRoot,
    installationReady: isReady,
    announce: setMessage,
    displayPath: shortPath,
    refreshInstallation: refresh,
    setStatus,
  });
  const diagnostics = useDiagnosticsReport(page === "reports", setMessage);
  const [launcherSettings, setLauncherSettings] = useState<LaunchSettings | null>(null);
  const [launcherDraft, setLauncherDraft] = useState<LaunchSettingsUpdate | null>(null);
  const [launcherSettingsLoading, setLauncherSettingsLoading] = useState(false);
  const [launcherSettingsSaving, setLauncherSettingsSaving] = useState(false);
  const [removalPlan, setRemovalPlan] = useState<RemovalPlan | null>(null);
  const [removalBusy, setRemovalBusy] = useState(false);
  const updates = useSignedUpdates(status === "ready", preparing || status === "running", setMessage);
  const { updateStatus } = updates;

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

  const changeLauncherDraft = useCallback((change: Partial<LaunchSettingsUpdate>) => {
    setLauncherDraft((current) => current ? { ...current, ...change } : current);
  }, []);

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
  return (
    <DesktopShell
      page={page}
      title={title}
      status={status}
      isReady={isReady}
      updateAvailable={Boolean(updateStatus?.available)}
      engineVersion={snapshot?.engineVersion ?? "…"}
      onPageChange={setPage}
      onRefresh={() => void refresh(snapshot?.selected?.installRoot)}
    >
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
            <QuickGameSettings
              settings={launcherSettings}
              draft={launcherDraft}
              dirty={launchSettingsDirty}
              saving={launcherSettingsSaving}
              disabled={status === "running" || preparing}
              onChange={changeLauncherDraft}
              onOpenAll={() => setPage("launch")}
              onSave={() => void saveLauncherSettings()}
            />
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
          <>
            {message ? <div className="notice" role="status"><span>✦</span><p>{message}</p></div> : null}
            <GameSettingsPage
              settings={launcherSettings}
              draft={launcherDraft}
              loading={launcherSettingsLoading}
              saving={launcherSettingsSaving}
              dirty={launchSettingsDirty}
              disabled={status === "running" || preparing}
              onChange={changeLauncherDraft}
              onRefresh={() => void refreshLauncherSettings()}
              onSave={() => void saveLauncherSettings()}
            />
          </>
        ) : page === "prepare" ? (
          <PreparationPage
            message={message}
            status={status}
            isReady={isReady}
            optimizationPreset={optimizationPreset}
            preparation={preparation}
            cleanupPlan={cleanupPlan}
            cleanupBusy={cleanupBusy}
            onOptimizationPresetChange={setOptimizationPreset}
            onReviewCleanup={() => void reviewCleanup()}
            onCleanCache={() => void cleanCache()}
            onDismissCleanup={() => setCleanupPlan(null)}
          />
        ) : page === "profiles" ? (
          <ProfilesPage message={message} profilesState={profilesState} />
        ) : page === "reports" ? (
          <ReportsPage
            message={message}
            status={status}
            platform={snapshot?.platform ?? null}
            preparing={preparing}
            automation={automation}
            diagnostics={diagnostics}
            onMessage={setMessage}
          />
        ) : (
          <SettingsPage
            message={message}
            status={status}
            preparing={preparing}
            updates={updates}
            removalPlan={removalPlan}
            removalBusy={removalBusy}
            onReviewRemoval={(scope) => void reviewRemoval(scope)}
            onDismissRemoval={() => setRemovalPlan(null)}
            onRemove={() => void removePreflight()}
          />
        )}
    </DesktopShell>
  );
}
