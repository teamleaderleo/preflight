import { useCallback, useEffect, useRef, useState } from "react";
import { open } from "@tauri-apps/plugin-dialog";
import {
  getSnapshot,
  getBootstrapSnapshot,
  getOperationState,
  browserPreviewScenario,
  isDesktopHost,
  previewRunFailure,
  startGame,
  stopGame,
} from "./bridge";
import { DesktopShell, type Page } from "./components/DesktopShell";
import { GameSettingsPage } from "./components/GameSettingsPage";
import { HangarPage } from "./components/HangarPage";
import { HomePage } from "./components/HomePage";
import { NoticeBanner } from "./components/NoticeBanner";
import { PreparationPage } from "./components/PreparationPage";
import { ProfilesPage } from "./components/ProfilesPage";
import { BenchmarkPage } from "./components/BenchmarkPage";
import { HelpPage } from "./components/HelpPage";
import { SettingsPage } from "./components/SettingsPage";
import { WorkflowLockNotice } from "./components/WorkflowLockNotice";
import { useDesktopAutomation } from "./useDesktopAutomation";
import { useAutomaticMaintenance } from "./useAutomaticMaintenance";
import { useAfterLaunchBehavior } from "./useAfterLaunchBehavior";
import { useCacheCleanup } from "./useCacheCleanup";
import { useDiagnosticsReport } from "./useDiagnosticsReport";
import { useLauncherSettings } from "./useLauncherSettings";
import { useInstrumentHull } from "./useInstrumentHull";
import { useOptimizationPolicy } from "./useOptimizationPolicy";
import { usePreparation } from "./usePreparation";
import { useProfiles } from "./useProfiles";
import { useRemoval } from "./useRemoval";
import { useSignedUpdates } from "./useSignedUpdates";
import { useSpeedRecord } from "./useSpeedRecord";
import { useTheme } from "./useTheme";
import { useWorkflowNotices } from "./useWorkflowNotices";
import { listenWhileMounted } from "./tauriEvents";
import { startOperationReconciliation } from "./operationReconciliation";
import { benchmarkOperationReason } from "./operationAvailability";
import { failedRunSummary, shortPath } from "./uiFormat";
import { blockingWorkflow } from "./workflowPolicy";
import { readLastInstallRoot, rememberLastInstallRoot } from "./desktopStorage";
import type {
  AppStatus,
  DesktopSnapshot,
  NoticeTone,
  RunStateEvent,
} from "./types";

function pageTitle(page: Page, status: AppStatus, preparing: boolean, isReady: boolean, needsPreparation: boolean): string {
  if (page === "launch") return "Game settings";
  if (page === "speed") return preparing ? "Preparing…" : "Speed";
  if (page === "benchmark") return "Benchmark";
  if (page === "mods") return "Mods";
  if (page === "hangar") return "Hangar";
  if (page === "help") return "Help";
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
  const [status, setStatus] = useState<AppStatus>(() =>
    !isDesktopHost() && browserPreviewScenario() === "running" ? "running" : "loading");
  const [retryIntent, setRetryIntent] = useState<{ kind: "discovery" | "installation" | "launch"; game?: string } | null>(null);
  const [runFailure, setRunFailure] = useState<RunFailure | null>(previewRunFailure);
  const [maintenanceEpoch, setMaintenanceEpoch] = useState(0);
  const [page, setPage] = useState<Page>("home");
  const [choosingInstall, setChoosingInstall] = useState(false);
  const [stoppingGame, setStoppingGame] = useState(false);
  const [forceStopAvailable, setForceStopAvailable] = useState(false);
  const [restoringOperation, setRestoringOperation] = useState(() => isDesktopHost());
  const [nativeBenchmarkBlockReason, setNativeBenchmarkBlockReason] = useState<string | null>(null);
  const choosingInstallRef = useRef(false);
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
  const { afterLaunchBehavior, setAfterLaunchBehavior } = useAfterLaunchBehavior();
  const refreshRequest = useRef(0);
  /**
   * Whether the installation is usable and whether a game is running are two different facts
   * sharing one `status`. A refresh only learns the first, so it must not publish over the second.
   *
   * <p>The authority for "launching" and "running" is the native process stream, which clears them
   * when Starsector exits. Letting a refresh overwrite them reported "ready" mid-game and released
   * the shared operation lock with it -- re-enabling Launch, Apply changes, profile switching,
   * cache cleanup, and Remove Preflight while the game was still running.
   */
  const setInstallationStatus = useCallback((next: AppStatus) => {
    setStatus((current) => (current === "running" || current === "launching" ? current : next));
  }, []);
  const speedStanding = useSpeedRecord();
  const instrumentHull = useInstrumentHull(
    snapshot?.selected?.installRoot,
    page === "speed" || page === "hangar",
  );
  const { countFastLaunch, rememberBenchmark } = speedStanding;
  const currentProfileFingerprint = useRef<string | null>(null);
  const countWhenFinished = useRef<{ pid: number; profileFingerprint: string } | null>(null);
  const refresh = useCallback(async (
    game?: string,
    options?: { bootstrap?: boolean; fallbackDiscovery?: boolean },
  ): Promise<boolean> => {
    const request = ++refreshRequest.current;
    setInstallationStatus("loading");
    clearNotice("installation");
    setRetryIntent(null);
    try {
      let next: DesktopSnapshot;
      try {
        next = options?.bootstrap
          ? await getBootstrapSnapshot(game)
          : await getSnapshot(game);
      } catch (error) {
        if (!options?.fallbackDiscovery || !game) throw error;
        next = await getBootstrapSnapshot();
      }
      if (options?.fallbackDiscovery && game && !next.ready) {
        next = await getBootstrapSnapshot();
      }
      if (request !== refreshRequest.current) return false;
      setSnapshot(next);
      rememberLastInstallRoot(next.selected?.installRoot ?? null);
      setInstallationStatus(next.ready ? "ready" : "setup");
      return true;
    } catch (error) {
      if (request !== refreshRequest.current) return false;
      setInstallationStatus("error");
      setRetryIntent(game ? { kind: "installation", game } : { kind: "discovery" });
      announceInstallation(game ? `Couldn’t use ${shortPath(game)}. ${String(error)}` : String(error), "error");
      return false;
    }
  }, [announceInstallation, clearNotice, setInstallationStatus]);
  const launch = useCallback(async () => {
    const game = snapshot?.selected?.installRoot;
    if (!game) return;
    setStatus("launching");
    setRetryIntent(null);
    announceGame("Opening Starsector…");
    try {
      const started = await startGame(
        game,
        optimizationPreset,
        disabledOptimizationDomains,
        afterLaunchBehavior,
      );
      setForceStopAvailable(false);
      // The desktop backend waits for an exact live game-JVM identity before publishing running.
      // Browser previews have no native event stream, so they settle immediately.
      if (!isDesktopHost()) setStatus("running");
      const fingerprint = currentProfileFingerprint.current;
      // The benchmark's optimized half uses the complete recommended path. Other presets still
      // launch safely, but they do not inherit a number they did not measure.
      countWhenFinished.current = optimizationPreset === "recommended"
        && disabledOptimizationDomains.length === 0
        && fingerprint
        ? { pid: started.pid, profileFingerprint: fingerprint }
        : null;
      announceGame(isDesktopHost() ? "Waiting for Starsector…" : "Starsector is running.", "success");
    } catch (error) {
      countWhenFinished.current = null;
      setStatus("error");
      setRetryIntent({ kind: "launch" });
      announceGame(String(error), "error");
    }
  }, [afterLaunchBehavior, announceGame, disabledOptimizationDomains, optimizationPreset, snapshot?.selected?.installRoot]);

  const stopRunningGame = useCallback(async () => {
    if (stoppingGame) return;
    const force = forceStopAvailable;
    setStoppingGame(true);
    try {
      const result = await stopGame(force);
      if (result.stillRunning > 0 && !force) {
        setForceStopAvailable(true);
        announceGame("Starsector didn’t respond. Force stop is available.", "warning");
      } else if (result.stillRunning > 0) {
        announceGame("Starsector is still running. The run details are available in Help.", "error");
      } else {
        setForceStopAvailable(false);
        announceGame(result.stopped > 0 ? "Stopping Starsector…" : "Starsector has already stopped.", "success");
      }
    } catch (error) {
      announceGame(`Couldn’t stop Starsector safely: ${error}`, "error");
    } finally {
      setStoppingGame(false);
    }
  }, [announceGame, forceStopAvailable, stoppingGame]);
  const preparation = usePreparation(
    snapshot?.selected?.installRoot,
    page === "speed",
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
  currentProfileFingerprint.current = preparation.cache?.currentProfileFingerprint ?? null;
  const profilesState = useProfiles(
    snapshot?.selected?.installRoot,
    page === "home" || page === "mods",
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
  // Settings states what this build sends, and that sentence depends on whether an intake origin
  // was compiled in, so the status is fetched for either page rather than only where it is sent.
  const diagnostics = useDiagnosticsReport(page === "help" || page === "settings", announceSupport);
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
  // The one outcome a launcher cannot have is refusing to launch. Preparation can be refused --
  // no disk, an unverifiable cache boundary -- and the game still has to start, so this skips
  // preparation and launches: missing artifacts fall back to the game's own loader by design.
  const launchWithoutPreparing = async () => {
    if (launcher.dirty && !(await launcher.save())) return;
    await launch();
  };
  const removal = useRemoval(
    snapshot?.platform,
    announceRemoval,
    clearCache,
    clearProfiles,
    diagnostics.clearReportReceipt,
  );
  const updates = useSignedUpdates(status === "ready", preparing || status === "launching" || status === "running", announceUpdates);
  const { updateStatus } = updates;
  // A measurement that costs several minutes of the machine to itself is written down the moment
  // it lands, rather than living only in the event that delivered it.
  const { desktopBenchmarkComparison } = automation;
  useEffect(() => {
    rememberBenchmark(desktopBenchmarkComparison);
  }, [desktopBenchmarkComparison, rememberBenchmark]);

  useEffect(() => {
    const rememberedGame = readLastInstallRoot();
    void refresh(rememberedGame ?? undefined, {
      bootstrap: true,
      fallbackDiscovery: rememberedGame !== null,
    });
  }, [refresh]);

  useEffect(() => {
    if (!isDesktopHost()) return;
    let cancelled = false;
    let recoveredGameActive = false;
    let backgroundOperationActive = false;
    let stopRecoveredGameReconciliation: () => void = () => undefined;
    let stopBackgroundOperationReconciliation: () => void = () => undefined;
    void getOperationState(true).then((operation) => {
      if (cancelled) return;
      const benchmarkReason = benchmarkOperationReason(operation);
      setNativeBenchmarkBlockReason(benchmarkReason);
      if (benchmarkReason !== null) {
        backgroundOperationActive = true;
        stopBackgroundOperationReconciliation = startOperationReconciliation({
          // These owners live in the current Tauri process. Avoid the durable game probe here;
          // it starts the CLI and is only needed by the separate recovered-game path below.
          read: () => getOperationState(false),
          apply: (current) => {
            const reason = benchmarkOperationReason(current);
            setNativeBenchmarkBlockReason(reason);
            backgroundOperationActive = reason !== null;
          },
          isActive: () => backgroundOperationActive,
          onError: (error) => announceBenchmark(`Couldn’t refresh native operation state: ${error}`, "warning"),
        });
      }
      if (automation.reconnectDesktopAutomation(operation)) {
        announceBenchmark("Reconnected to the running startup benchmark.", "success");
      } else if (operation.gamePid !== null) {
        setStatus("running");
        announceGame("Reconnected to the running Starsector game.", "success");
        if (operation.gameRecovered) {
          recoveredGameActive = true;
          stopRecoveredGameReconciliation = startOperationReconciliation({
            read: () => getOperationState(true),
            apply: (current) => {
              if (current.gamePid !== null) return;
              recoveredGameActive = false;
              setStoppingGame(false);
              setForceStopAvailable(false);
              setMaintenanceEpoch((epoch) => epoch + 1);
              setStatus("ready");
              void refresh().then((refreshed) => {
                if (refreshed) announceGame("Starsector closed. The run report is ready.", "success");
              });
            },
            isActive: () => recoveredGameActive,
            onError: (error) => announceGame(`Couldn’t refresh the running game: ${error}`, "warning"),
            intervalMs: 2_000,
          });
        }
      }
    }).catch((error) => {
      if (!cancelled) {
        announceGame(`Couldn’t check for a previous Starsector launch: ${error}`, "warning");
      }
    }).finally(() => {
      if (!cancelled) setRestoringOperation(false);
    });
    return () => {
      cancelled = true;
      recoveredGameActive = false;
      backgroundOperationActive = false;
      stopRecoveredGameReconciliation();
      stopBackgroundOperationReconciliation();
    };
  }, [announceBenchmark, announceGame, automation.reconnectDesktopAutomation, refresh]);

  useEffect(() => {
    if (!isDesktopHost()) return;
    let stopReconciliation: () => void = () => undefined;
    const stopListening = listenWhileMounted<RunStateEvent>("run-state", ({ payload }) => {
      if (payload.state === "running") {
        setStatus("running");
        announceGame("Starsector is running.", "success");
        return;
      }
      if (payload.state === "finished") {
        setStoppingGame(false);
        setForceStopAvailable(false);
        setMaintenanceEpoch((current) => current + 1);
        const pendingCount = countWhenFinished.current;
        countWhenFinished.current = null;
        if (payload.success && pendingCount?.pid === payload.pid) {
          countFastLaunch(pendingCount.profileFingerprint);
        }
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
            setMaintenanceEpoch((current) => current + 1);
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
  }, [announceGame, countFastLaunch, refresh, snapshot?.ready, snapshot?.selected?.installRoot]);

  const chooseInstall = async (): Promise<boolean> => {
    if (choosingInstallRef.current) return false;
    choosingInstallRef.current = true;
    setChoosingInstall(true);
    try {
      if (!isDesktopHost()) {
        return refresh("/Applications/Starsector");
      }
      const selected = await open({
        directory: true,
        multiple: false,
        title: "Choose your Starsector folder",
      });
      if (typeof selected === "string") {
        return refresh(selected, { bootstrap: true });
      }
      return false;
    } catch (error) {
      announceInstallation(`Couldn’t open the folder picker. ${String(error)}`, "error");
      return false;
    } finally {
      choosingInstallRef.current = false;
      setChoosingInstall(false);
    }
  };

  const activeOperation = blockingWorkflow({
    preparing,
    preparationPercent: preparation.preparationPercent,
    cacheRepairing,
    choosingInstall,
    restoringOperation,
    desktopSmokeRunning: automation.desktopSmokeRunning,
    desktopSmokeCancelling: automation.desktopSmokeCancelling,
    status,
    cleanupBusy: cleanup.busy,
    launcherSaving: launcher.saving,
    profileBusy: profilesState.profileBusy,
    diagnosticsBusy: diagnostics.diagnosticsBusy,
    reportUploading: diagnostics.reportUploading,
    reportFinalizing: diagnostics.reportFinalizing,
    reportCancelling: diagnostics.reportCancelling,
    removalBusy: removal.busy,
    updateInstalling: updates.updateInstalling,
  });
  const operationBlocked = activeOperation !== null;
  const launchSettingsBlocked = choosingInstall
    || restoringOperation
    || status === "launching"
    || status === "running"
    || automation.desktopSmokeRunning
    || updates.updateInstalling
    || removal.busy;
  const refreshAfterAutomaticCacheCleanup = useCallback(() => {
    void refreshCache();
    invalidatePreparationPlan();
  }, [invalidatePreparationPlan, refreshCache]);
  useAutomaticMaintenance(
    status === "ready" && isReady && !operationBlocked,
    maintenanceEpoch,
    {
      game: snapshot?.selected?.installRoot,
      cacheBytes: preparation.cache?.total.bytes,
      onCacheCleaned: refreshAfterAutomaticCacheCleanup,
    },
  );
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
  const benchmarkNotice = latestNotice(["installation", "benchmark"]);
  const helpNotice = latestNotice(["installation", "support"]);
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
      theme={theme.preference}
      palette={theme.palette}
      onPageChange={setPage}
      onThemeChange={theme.setPreference}
      onPaletteChange={theme.setPalette}
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
            launchSettingsBlocked={launchSettingsBlocked}
            theme={theme.resolved}
            onLauncherChange={launcher.changeDraft}
            onChooseInstall={() => void chooseInstall()}
            optimizationPreset={optimizationPreset}
            onPrimaryLaunch={() => void primaryLaunch()}
            onLaunchWithoutPreparing={() => void launchWithoutPreparing()}
            stoppingGame={stoppingGame}
            forceStopAvailable={forceStopAvailable}
            onStopGame={() => void stopRunningGame()}
            onSaveLauncherSettings={() => void launcher.save()}
            retryLabel={retryLabel}
            onRetry={retryFailedOperation}
            runFailure={runFailure}
            onDismissRunFailure={() => setRunFailure(null)}
            onOpenStorage={() => {
              setPage("speed");
              void cleanup.review();
            }}
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
              disabled={launchSettingsBlocked}
              onChange={launcher.changeDraft}
              onRefresh={() => void launcher.refresh()}
              onSave={() => void launcher.save()}
            />
          </>
        ) : page === "speed" ? (
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
            speedStanding={speedStanding}
            playtime={snapshot?.playtime}
            instrumentHull={instrumentHull.selected}
            onOptimizationPresetChange={setOptimizationPreset}
            onOptimizationDomainChange={setOptimizationDomainEnabled}
            onReviewCleanup={() => void cleanup.review()}
            onCleanCache={() => void cleanup.clean()}
            onDismissCleanup={cleanup.dismiss}
            onOpenBenchmark={() => setPage("benchmark")}
          />
        ) : page === "mods" ? (
          <ProfilesPage message={profilesNotice?.message ?? ""} messageTone={profilesNotice?.tone ?? "info"} profilesState={profilesState} operationBlocked={operationBlocked} />
        ) : page === "hangar" ? (
          <HangarPage instrumentHull={instrumentHull} />
        ) : page === "benchmark" ? (
          <BenchmarkPage
            message={benchmarkNotice?.message ?? ""}
            messageTone={benchmarkNotice?.tone ?? "info"}
            status={status}
            isReady={isReady}
            preparing={preparing}
            operationBlocked={operationBlocked}
            nativeBlockReason={nativeBenchmarkBlockReason}
            automation={automation}
          />
        ) : page === "help" ? (
          <HelpPage
            message={helpNotice?.message ?? ""}
            messageTone={helpNotice?.tone ?? "info"}
            diagnostics={diagnostics}
            operationBlocked={operationBlocked}
            optimizationPreset={optimizationPreset}
            onTurnOffOptimizations={() => setOptimizationPreset("off")}
            onChooseInstall={() => {
              void chooseInstall().then((changed) => {
                if (changed) setPage("home");
              });
            }}
            onNavigate={setPage}
          />
        ) : (
          <SettingsPage
            message={settingsNotice?.message ?? ""}
            messageTone={settingsNotice?.tone ?? "info"}
            operationBlocked={operationBlocked}
            updates={updates}
            reportIntake={diagnostics.reportIntake}
            removalPlan={removal.plan}
            removalBusy={removal.busy}
            afterLaunchBehavior={afterLaunchBehavior}
            onAfterLaunchBehaviorChange={setAfterLaunchBehavior}
            onReviewRemoval={(scope) => void removal.review(scope)}
            onDismissRemoval={removal.dismiss}
            onRemove={() => void removal.remove()}
          />
        )}
    </DesktopShell>
  );
}
