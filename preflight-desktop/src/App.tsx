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
import { NoticeBanner } from "./components/NoticeBanner";
import { PreparationPage } from "./components/PreparationPage";
import { ProfilesPage } from "./components/ProfilesPage";
import { ReportsPage } from "./components/ReportsPage";
import { SettingsPage } from "./components/SettingsPage";
import { WorkflowLockNotice } from "./components/WorkflowLockNotice";
import { useDesktopAutomation } from "./useDesktopAutomation";
import { useCacheCleanup } from "./useCacheCleanup";
import { useDiagnosticsReport } from "./useDiagnosticsReport";
import { useLauncherSettings } from "./useLauncherSettings";
import { useOptimizationPolicy } from "./useOptimizationPolicy";
import { usePreparation } from "./usePreparation";
import { useProfiles } from "./useProfiles";
import { useRemoval } from "./useRemoval";
import { useSignedUpdates } from "./useSignedUpdates";
import { useTheme } from "./useTheme";
import { useWorkflowNotices } from "./useWorkflowNotices";
import { listenWhileMounted } from "./tauriEvents";
import { startOperationReconciliation } from "./operationReconciliation";
import { failedRunSummary, shortPath } from "./uiFormat";
import type {
  AppStatus,
  DesktopSnapshot,
  NoticeTone,
  RunStateEvent,
} from "./types";

function pageTitle(page: Page, status: AppStatus, preparing: boolean, isReady: boolean, needsPreparation: boolean): string {
  if (page === "launch") return "Game settings";
  if (page === "prepare") return preparing ? "Preparing…" : "Preflight";
  if (page === "reports") return "Benchmark";
  if (page === "profiles") return "Profiles";
  if (page === "settings") return "Settings";
  if (preparing) return "Preparing…";
  if (status === "loading") return "Finding Starsector…";
  if (status === "error") return "Needs attention";
  if (status === "launching") return "Opening Starsector…";
  if (status === "running") return "Running";
  if (!isReady) return "Setup";
  return needsPreparation ? "Preparation needed" : "Ready";
}

interface RunFailure {
  summary: string;
  detail?: string;
}

export default function App() {
  const theme = useTheme();
  const [snapshot, setSnapshot] = useState<DesktopSnapshot | null>(null);
  const [status, setStatus] = useState<AppStatus>("loading");
  const [retryIntent, setRetryIntent] = useState<{ kind: "discovery" | "installation" | "launch"; game?: string } | null>(null);
  const [runFailure, setRunFailure] = useState<RunFailure | null>(null);
  const [page, setPage] = useState<Page>("home");
  const { announce: announceNotice, clear: clearNotice, latest: latestNotice } = useWorkflowNotices();
  const announceInstallation = useCallback((message: string, tone?: NoticeTone) => announceNotice("installation", message, tone), [announceNotice]);
  const announceGame = useCallback((message: string, tone?: NoticeTone) => announceNotice("game", message, tone), [announceNotice]);
  const announceGameSettings = useCallback((message: string, tone?: NoticeTone) => announceNotice("game-settings", message, tone), [announceNotice]);
  const announcePreparation = useCallback((message: string, tone?: NoticeTone) => announceNotice("preparation", message, tone), [announceNotice]);
  const announceProfiles = useCallback((message: string, tone?: NoticeTone) => announceNotice("profiles", message, tone), [announceNotice]);
  const announceCache = useCallback((message: string, tone?: NoticeTone) => announceNotice("cache", message, tone), [announceNotice]);
  const announceBenchmark = useCallback((message: string, tone?: NoticeTone) => announceNotice("benchmark", message, tone), [announceNotice]);
  const announceSupport = useCallback((message: string, tone?: NoticeTone) => announceNotice("support", message, tone), [announceNotice]);
  const announceUpdates = useCallback((message: string, tone?: NoticeTone) => announceNotice("updates", message, tone), [announceNotice]);
  const announceRemoval = useCallback((message: string, tone?: NoticeTone) => announceNotice("removal", message, tone), [announceNotice]);
  const {
    optimizationPreset,
    disabledOptimizationDomains,
    setOptimizationPreset,
    setOptimizationDomainEnabled,
  } = useOptimizationPolicy();
  const refreshRequest = useRef(0);
  const refresh = useCallback(async (game?: string): Promise<boolean> => {
    const request = ++refreshRequest.current;
    setStatus("loading");
    clearNotice("installation");
    setRetryIntent(null);
    try {
      const next = await getSnapshot(game);
      if (request !== refreshRequest.current) return false;
      setSnapshot(next);
      setStatus(next.ready ? "ready" : "setup");
      return true;
    } catch (error) {
      if (request !== refreshRequest.current) return false;
      setStatus("error");
      setRetryIntent(game ? { kind: "installation", game } : { kind: "discovery" });
      announceInstallation(game ? `Couldn’t use ${shortPath(game)}. ${String(error)}` : String(error), "error");
      return false;
    }
  }, [announceInstallation, clearNotice]);
  const launch = useCallback(async () => {
    const game = snapshot?.selected?.installRoot;
    if (!game) return;
    setStatus("launching");
    setRetryIntent(null);
    announceGame("Opening Starsector…");
    try {
      await startGame(game, optimizationPreset, disabledOptimizationDomains);
      setStatus("running");
      announceGame("Starsector is running. Preflight will return here when it exits.", "success");
    } catch (error) {
      setStatus("error");
      setRetryIntent({ kind: "launch" });
      announceGame(String(error), "error");
    }
  }, [announceGame, disabledOptimizationDomains, optimizationPreset, snapshot?.selected?.installRoot]);
  const preparation = usePreparation(
    snapshot?.selected?.installRoot,
    page === "prepare",
    optimizationPreset,
    launch,
    announcePreparation,
  );
  const {
    preparing,
    cacheRepairing,
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
    announceProfiles,
  );
  const { clearProfiles } = profilesState;
  const cleanup = useCacheCleanup(
    snapshot?.selected?.installRoot,
    announceCache,
    refreshCache,
    invalidatePreparationPlan,
  );
  const isReady = Boolean(snapshot?.ready && snapshot.selected);
  const automation = useDesktopAutomation({
    game: snapshot?.selected?.installRoot,
    installationReady: isReady,
    announce: announceBenchmark,
    displayPath: shortPath,
    refreshInstallation: refresh,
    setStatus,
  });
  const diagnostics = useDiagnosticsReport(page === "reports", announceSupport);
  const launcher = useLauncherSettings(
    snapshot?.selected?.installRoot,
    page === "home" || page === "launch",
    announceGameSettings,
  );
  const needsPreparation = optimizationPreset !== "off" && !profilePrepared;
  const primaryLaunch = async () => {
    if (launcher.dirty && !(await launcher.save())) return;
    await (preparation.cacheHealth?.status === "repair-needed"
      ? preparation.repairAndPrepare(true)
      : needsPreparation ? prepare(true) : launch());
  };
  const removal = useRemoval(snapshot?.platform, announceRemoval, clearCache, clearProfiles);
  const updates = useSignedUpdates(status === "ready", preparing || status === "launching" || status === "running", announceUpdates);
  const { updateStatus } = updates;

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    if (!isDesktopHost()) return;
    let stopReconciliation: () => void = () => undefined;
    const stopListening = listenWhileMounted<RunStateEvent>("run-state", ({ payload }) => {
      if (payload.state === "finished") {
        setStatus(snapshot?.ready ? "ready" : "setup");
        const outcome = payload.success
          ? "Starsector closed normally. The run report is ready."
          : failedRunSummary(payload.detail);
        setRunFailure(payload.success ? null : { summary: outcome, detail: payload.detail });
        void refresh(snapshot?.selected?.installRoot).then((refreshed) => {
          if (refreshed) announceGame(outcome, payload.success ? "success" : "error");
        });
      }
    }, (error) => {
      announceGame(`Live game-process updates were interrupted: ${error}. Preflight is checking native state directly.`, "warning");
      let previousPid: number | null | undefined;
      stopReconciliation();
      stopReconciliation = startOperationReconciliation({
        apply: (operation) => {
          if (operation.gamePid !== null) {
            previousPid = operation.gamePid;
            setStatus("running");
            return;
          }
          if (previousPid !== null && previousPid !== undefined) {
            previousPid = null;
            setStatus(snapshot?.ready ? "ready" : "setup");
            void refresh(snapshot?.selected?.installRoot).then((refreshed) => {
              if (refreshed) announceGame("Starsector closed. The exact outcome is available in run reports.", "warning");
            });
          } else {
            previousPid = null;
          }
        },
        isActive: () => true,
        onError: (pollError) => announceGame(`Could not refresh native game state: ${pollError}`, "error"),
      });
    });
    return () => {
      stopListening();
      stopReconciliation();
    };
  }, [announceGame, refresh, snapshot?.ready, snapshot?.selected?.installRoot]);

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
    || cacheRepairing
    || status === "launching"
    || status === "running"
    || cleanup.busy
    || launcher.saving
    || profilesState.profileBusy
    || removal.busy
    || updates.updateInstalling;
  const activeOperation = preparing
    ? { reason: `Preparing this mod setup · ${preparation.preparationPercent}% complete`, owner: "home" as Page }
    : cacheRepairing
      ? { reason: "Repairing prepared data for this mod setup", owner: "prepare" as Page }
      : status === "launching"
        ? { reason: "Opening Starsector", owner: "home" as Page }
        : status === "running"
          ? { reason: "Starsector is running", owner: "home" as Page }
          : cleanup.busy
            ? { reason: "Reviewing or cleaning prepared data", owner: "prepare" as Page }
            : launcher.saving
              ? { reason: "Saving game settings", owner: "launch" as Page }
              : profilesState.profileBusy
                ? { reason: "Updating the saved mod profile", owner: "profiles" as Page }
                : removal.busy
                  ? { reason: "Reviewing or removing Preflight data", owner: "settings" as Page }
                  : updates.updateInstalling
                    ? { reason: "Installing the verified Preflight update", owner: "settings" as Page }
                    : null;
  const retryFailedOperation = () => {
    if (retryIntent?.kind === "launch") {
      void primaryLaunch();
      return;
    }
    void refresh(retryIntent?.kind === "installation" ? retryIntent.game : undefined);
  };
  const retryLabel = retryIntent?.kind === "launch"
    ? "Try launch again"
    : retryIntent?.kind === "installation"
      ? "Try this folder again"
      : "Scan again";
  const homeNotice = latestNotice(["installation", "game", "game-settings", "preparation", "profiles", "cache"]);
  const launchNotice = latestNotice(["installation", "game-settings"]);
  const preparationNotice = latestNotice(["installation", "preparation", "cache"]);
  const profilesNotice = latestNotice(["installation", "profiles"]);
  const reportsNotice = latestNotice(["installation", "benchmark", "support"]);
  const settingsNotice = latestNotice(["installation", "updates", "removal"]);
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
      theme={theme.preference}
      onPageChange={setPage}
      onRefresh={() => void refresh(snapshot?.selected?.installRoot)}
      onThemeChange={theme.setPreference}
    >
        {activeOperation && page !== activeOperation.owner ? (
          <WorkflowLockNotice
            reason={`${activeOperation.reason}. Other changes wait until it finishes.`}
            actionLabel={activeOperation.owner === "home" ? "View progress" : `Open ${pageTitle(activeOperation.owner, status, preparing, isReady, needsPreparation)}`}
            onAction={() => setPage(activeOperation.owner)}
          />
        ) : null}
        {page === "home" ? (
          <HomePage
            snapshot={snapshot}
            status={status}
            message={homeNotice?.message ?? ""}
            messageTone={homeNotice?.tone ?? "info"}
            isReady={isReady}
            needsPreparation={needsPreparation}
            preparation={preparation}
            profilesState={profilesState}
            updateStatus={updateStatus}
            launcherSettings={launcher.settings}
            launcherDraft={launcher.draft}
            launcherSettingsLoading={launcher.loading}
            launcherSettingsSaving={launcher.saving}
            launchSettingsDirty={launcher.dirty}
            operationBlocked={operationBlocked}
            theme={theme.resolved}
            onLauncherChange={launcher.changeDraft}
            onChooseInstall={() => void chooseInstall()}
            onPrimaryLaunch={() => void primaryLaunch()}
            onSaveLauncherSettings={() => void launcher.save()}
            retryLabel={retryLabel}
            onRetry={retryFailedOperation}
            runFailure={runFailure}
            onDismissRunFailure={() => setRunFailure(null)}
            onNavigate={setPage}
          />
        ) : page === "launch" ? (
          <>
            <NoticeBanner message={launchNotice?.message ?? ""} tone={launchNotice?.tone ?? "info"} />
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
            message={preparationNotice?.message ?? ""}
            messageTone={preparationNotice?.tone ?? "info"}
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
          <ProfilesPage message={profilesNotice?.message ?? ""} messageTone={profilesNotice?.tone ?? "info"} profilesState={profilesState} operationBlocked={operationBlocked} />
        ) : page === "reports" ? (
          <ReportsPage
            message={reportsNotice?.message ?? ""}
            messageTone={reportsNotice?.tone ?? "info"}
            status={status}
            isReady={isReady}
            preparing={preparing}
            automation={automation}
            diagnostics={diagnostics}
          />
        ) : (
          <SettingsPage
            message={settingsNotice?.message ?? ""}
            messageTone={settingsNotice?.tone ?? "info"}
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
