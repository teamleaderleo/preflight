import { useCallback, useEffect, useRef, useState } from "react";
import { open } from "@tauri-apps/plugin-dialog";
import {
  getSnapshot,
  isDesktopHost,
  startGame,
} from "./bridge";
import { DesktopShell, type Page } from "./components/DesktopShell";
import { GameSettingsPage } from "./components/GameSettingsPage";
import { HomePage } from "./components/HomePage";
import { PreparationPage } from "./components/PreparationPage";
import { ProfilesPage } from "./components/ProfilesPage";
import { ReportsPage } from "./components/ReportsPage";
import { SettingsPage } from "./components/SettingsPage";
import { useDesktopAutomation } from "./useDesktopAutomation";
import { useCacheCleanup } from "./useCacheCleanup";
import { useDiagnosticsReport } from "./useDiagnosticsReport";
import { useLauncherSettings } from "./useLauncherSettings";
import { useOptimizationPolicy } from "./useOptimizationPolicy";
import { usePreparation } from "./usePreparation";
import { useProfiles } from "./useProfiles";
import { useRemoval } from "./useRemoval";
import { useSignedUpdates } from "./useSignedUpdates";
import { listenWhileMounted } from "./tauriEvents";
import { shortPath } from "./uiFormat";
import type {
  AppStatus,
  DesktopSnapshot,
  RunStateEvent,
} from "./types";

export { isCurrentProfilePrepared } from "./usePreparation";

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
  const {
    optimizationPreset,
    disabledOptimizationDomains,
    setOptimizationPreset,
    setOptimizationDomainEnabled,
  } = useOptimizationPolicy();
  const refreshRequest = useRef(0);
  const refresh = useCallback(async (game?: string) => {
    const request = ++refreshRequest.current;
    setStatus("loading");
    setMessage("");
    try {
      const next = await getSnapshot(game);
      if (request !== refreshRequest.current) return;
      setSnapshot(next);
      setStatus(next.ready ? "ready" : "setup");
    } catch (error) {
      if (request !== refreshRequest.current) return;
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
      await startGame(game, optimizationPreset, disabledOptimizationDomains);
      setMessage("Starsector is running. Preflight will keep the porch light on.");
    } catch (error) {
      setStatus("error");
      setMessage(String(error));
    }
  }, [disabledOptimizationDomains, optimizationPreset, snapshot?.selected?.installRoot]);
  const preparation = usePreparation(
    snapshot?.selected?.installRoot,
    page === "prepare",
    optimizationPreset,
    launch,
    setMessage,
  );
  const {
    preparing,
    profilePrepared,
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
    clearProfiles,
    reviewProfile,
  } = profilesState;
  const cleanup = useCacheCleanup(
    snapshot?.selected?.installRoot,
    setMessage,
    refreshCache,
    invalidatePreparationPlan,
  );
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
  const launcher = useLauncherSettings(
    snapshot?.selected?.installRoot,
    page === "home" || page === "launch",
    setMessage,
  );
  const needsPreparation = optimizationPreset !== "off" && !profilePrepared;
  const primaryLaunch = async () => {
    if (launcher.dirty && !(await launcher.save())) return;
    await (needsPreparation ? prepare(true) : launch());
  };
  const removal = useRemoval(snapshot?.platform, setMessage, clearCache, clearProfiles);
  const updates = useSignedUpdates(status === "ready", preparing || status === "running", setMessage);
  const { updateStatus } = updates;

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    if (!isDesktopHost()) return;
    return listenWhileMounted<RunStateEvent>("run-state", ({ payload }) => {
      if (payload.state === "finished") {
        setStatus(snapshot?.ready ? "ready" : "setup");
        const outcome = payload.success ? "Welcome back. Your run was tucked away safely." : "The game closed with an error. Your run notes are still safe.";
        void refresh(snapshot?.selected?.installRoot).then(() => setMessage(outcome));
      }
    }, (error) => setMessage(`Could not observe the game process: ${error}`));
  }, [refresh, snapshot?.ready, snapshot?.selected?.installRoot]);

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

  const operationBlocked = preparing
    || status === "running"
    || cleanup.busy
    || launcher.saving
    || profilesState.profileBusy
    || removal.busy
    || updates.updateInstalling;
  const title = pageTitle(page, status, preparing, isReady, needsPreparation);
  return (
    <DesktopShell
      page={page}
      title={title}
      status={status}
      isReady={isReady}
      updateAvailable={Boolean(updateStatus?.available)}
      engineVersion={snapshot?.engineVersion ?? "…"}
      refreshDisabled={operationBlocked}
      onPageChange={setPage}
      onRefresh={() => void refresh(snapshot?.selected?.installRoot)}
    >
        {page === "home" ? (
          <HomePage
            snapshot={snapshot}
            status={status}
            message={message}
            isReady={isReady}
            needsPreparation={needsPreparation}
            optimizationPreset={optimizationPreset}
            disabledOptimizationDomains={disabledOptimizationDomains}
            preparation={preparation}
            profilesState={profilesState}
            updateStatus={updateStatus}
            launcherSettings={launcher.settings}
            launcherDraft={launcher.draft}
            launcherSettingsLoading={launcher.loading}
            launcherSettingsSaving={launcher.saving}
            launchSettingsDirty={launcher.dirty}
            operationBlocked={operationBlocked}
            onLauncherChange={launcher.changeDraft}
            onChooseInstall={() => void chooseInstall()}
            onPrimaryLaunch={() => void primaryLaunch()}
            onSaveLauncherSettings={() => void launcher.save()}
            onRetry={() => void refresh()}
            onNavigate={setPage}
            onSelectProfile={(name) => {
              setPage("profiles");
              void reviewProfile(name);
            }}
          />
        ) : page === "launch" ? (
          <>
            {message ? <div className="notice" role="status"><span>✦</span><p>{message}</p></div> : null}
            <GameSettingsPage
              settings={launcher.settings}
              draft={launcher.draft}
              loading={launcher.loading}
              saving={launcher.saving}
              dirty={launcher.dirty}
              disabled={operationBlocked}
              onChange={launcher.changeDraft}
              onRefresh={() => void launcher.refresh()}
              onSave={() => void launcher.save()}
            />
          </>
        ) : page === "prepare" ? (
          <PreparationPage
            message={message}
            isReady={isReady}
            optimizationPreset={optimizationPreset}
            disabledOptimizationDomains={disabledOptimizationDomains}
            preparation={preparation}
            cleanupPlan={cleanup.plan}
            cleanupBusy={cleanup.busy}
            operationBlocked={operationBlocked}
            onOptimizationPresetChange={setOptimizationPreset}
            onOptimizationDomainChange={setOptimizationDomainEnabled}
            onReviewCleanup={() => void cleanup.review()}
            onCleanCache={() => void cleanup.clean()}
            onDismissCleanup={cleanup.dismiss}
          />
        ) : page === "profiles" ? (
          <ProfilesPage message={message} profilesState={profilesState} operationBlocked={operationBlocked} />
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
            operationBlocked={operationBlocked}
            updates={updates}
            removalPlan={removal.plan}
            removalBusy={removal.busy}
            onReviewRemoval={(scope) => void removal.review(scope)}
            onDismissRemoval={removal.dismiss}
            onRemove={() => void removal.remove()}
          />
        )}
    </DesktopShell>
  );
}
