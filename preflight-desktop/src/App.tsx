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
import { startOperationReconciliation } from "./operationReconciliation";
import { shortPath } from "./uiFormat";
import type {
  AppStatus,
  DesktopSnapshot,
  NoticeTone,
  RunStateEvent,
} from "./types";

export { isCurrentProfilePrepared } from "./usePreparation";

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

function failedRunSummary(detail?: string): string {
  const firstLine = detail
    ?.split(/\r?\n/)
    .map((line) => line.trim())
    .find(Boolean);
  if (!firstLine) return "Starsector closed with an error. The run report is ready.";
  const summary = firstLine.length > 360 ? `${firstLine.slice(0, 357)}…` : firstLine;
  return `Starsector closed with an error: ${summary} The run report has full details.`;
}

export default function App() {
  const [snapshot, setSnapshot] = useState<DesktopSnapshot | null>(null);
  const [status, setStatus] = useState<AppStatus>("loading");
  const [message, setMessage] = useState("");
  const [messageTone, setMessageTone] = useState<NoticeTone>("info");
  const [retryIntent, setRetryIntent] = useState<{ kind: "discovery" | "installation" | "launch"; game?: string } | null>(null);
  const [runFailure, setRunFailure] = useState<string | null>(null);
  const [page, setPage] = useState<Page>("home");
  const announce = useCallback((nextMessage: string, tone: NoticeTone = "info") => {
    setMessage(nextMessage);
    setMessageTone(tone);
  }, []);
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
    setMessage("");
    setMessageTone("info");
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
      announce(game ? `Couldn’t use ${shortPath(game)}. ${String(error)}` : String(error), "error");
      return false;
    }
  }, [announce]);
  const launch = useCallback(async () => {
    const game = snapshot?.selected?.installRoot;
    if (!game) return;
    setStatus("launching");
    setRetryIntent(null);
    announce("Opening Starsector…");
    try {
      await startGame(game, optimizationPreset, disabledOptimizationDomains);
      setStatus("running");
      announce("Starsector is running. Preflight will return here when it exits.", "success");
    } catch (error) {
      setStatus("error");
      setRetryIntent({ kind: "launch" });
      announce(String(error), "error");
    }
  }, [announce, disabledOptimizationDomains, optimizationPreset, snapshot?.selected?.installRoot]);
  const preparation = usePreparation(
    snapshot?.selected?.installRoot,
    page === "prepare",
    optimizationPreset,
    launch,
    announce,
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
    announce,
  );
  const { clearProfiles } = profilesState;
  const cleanup = useCacheCleanup(
    snapshot?.selected?.installRoot,
    announce,
    refreshCache,
    invalidatePreparationPlan,
  );
  const isReady = Boolean(snapshot?.ready && snapshot.selected);
  const automation = useDesktopAutomation({
    game: snapshot?.selected?.installRoot,
    installationReady: isReady,
    announce,
    displayPath: shortPath,
    refreshInstallation: refresh,
    setStatus,
  });
  const diagnostics = useDiagnosticsReport(page === "reports", announce);
  const launcher = useLauncherSettings(
    snapshot?.selected?.installRoot,
    page === "home" || page === "launch",
    announce,
  );
  const needsPreparation = optimizationPreset !== "off" && !profilePrepared;
  const primaryLaunch = async () => {
    if (launcher.dirty && !(await launcher.save())) return;
    await (preparation.cacheHealth?.status === "repair-needed"
      ? preparation.repairAndPrepare(true)
      : needsPreparation ? prepare(true) : launch());
  };
  const removal = useRemoval(snapshot?.platform, announce, clearCache, clearProfiles);
  const updates = useSignedUpdates(status === "ready", preparing || status === "launching" || status === "running", announce);
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
        setRunFailure(payload.success ? null : outcome);
        void refresh(snapshot?.selected?.installRoot).then((refreshed) => {
          if (refreshed) announce(outcome, payload.success ? "success" : "error");
        });
      }
    }, (error) => {
      announce(`Live game-process updates were interrupted: ${error}. Preflight is checking native state directly.`, "warning");
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
              if (refreshed) announce("Starsector closed. The exact outcome is available in run reports.", "warning");
            });
          } else {
            previousPid = null;
          }
        },
        isActive: () => true,
        onError: (pollError) => announce(`Could not refresh native game state: ${pollError}`, "error"),
      });
    });
    return () => {
      stopListening();
      stopReconciliation();
    };
  }, [announce, refresh, snapshot?.ready, snapshot?.selected?.installRoot]);

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
            messageTone={messageTone}
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
            <NoticeBanner message={message} tone={messageTone} />
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
            messageTone={messageTone}
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
          <ProfilesPage message={message} messageTone={messageTone} profilesState={profilesState} operationBlocked={operationBlocked} />
        ) : page === "reports" ? (
          <ReportsPage
            message={message}
            messageTone={messageTone}
            status={status}
            platform={snapshot?.platform ?? null}
            preparing={preparing}
            automation={automation}
            diagnostics={diagnostics}
            onMessage={announce}
          />
        ) : (
          <SettingsPage
            message={message}
            messageTone={messageTone}
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
