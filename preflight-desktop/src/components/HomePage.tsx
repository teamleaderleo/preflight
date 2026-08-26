import { useEffect, useRef, useState } from "react";
import {
  ArrowIcon,
  CheckIcon,
  ClockIcon,
  FolderIcon,
  OrbitClockwiseIcon,
  OrbitCounterClockwiseIcon,
  PauseIcon,
  PlayIcon,
  ShipIcon,
  SparklesIcon,
} from "../icons";
import { adapterHealthLine } from "../adapterHealthText";
import { HOME_OPTIONS_STORAGE_KEY } from "../desktopStorage";
import type { Page } from "./DesktopShell";
import type { ThemePreference } from "../useTheme";
import { QuickGameSettings } from "./QuickGameSettings";
import { NoticeBanner } from "./NoticeBanner";
import { RunRecoveryActions } from "./RunRecoveryActions";
import { HomeLaunchIdentity } from "./HomeLaunchIdentity";
import { storagePlanApplies, type usePreparation } from "../usePreparation";
import { lastRunForCurrentProfile, launchSetupApplicability } from "../lastRunApplicability";
import { formatBytes, formatPlaytime, splitPlaytime } from "../uiFormat";
import { FlightInstrument } from "./FlightInstrument";
import { useHomePresentation } from "../useHomePresentation";
import { useInstrumentMotion } from "../useInstrumentMotion";
import type { useInstrumentHull } from "../useInstrumentHull";
import type {
  AppStatus,
  DesktopSnapshot,
  LaunchSettings,
  LaunchSettingsUpdate,
  ModReadiness,
  NoticeTone,
  OptimizationPreset,
  UpdateStatus,
} from "../types";

type PreparationState = ReturnType<typeof usePreparation>;
type InstrumentHullState = ReturnType<typeof useInstrumentHull>;

interface HomePageProps {
  snapshot: DesktopSnapshot | null;
  status: AppStatus;
  message: string;
  messageTone: NoticeTone;
  isReady: boolean;
  needsPreparation: boolean;
  optimizationPreset: OptimizationPreset;
  preparation: PreparationState;
  updateStatus: UpdateStatus | null;
  launcherSettings: LaunchSettings | null;
  launcherDraft: LaunchSettingsUpdate | null;
  launcherSettingsLoading: boolean;
  launcherSettingsSaving: boolean;
  launchSettingsDirty: boolean;
  operationBlocked: boolean;
  launchSettingsEditingBlocked: boolean;
  launchSettingsSaveBlocked: boolean;
  launchSettingsSaveBlockReason?: string;
  theme: Exclude<ThemePreference, "system">;
  onLauncherChange: (change: Partial<LaunchSettingsUpdate>) => void;
  onChooseInstall: () => void;
  onPrimaryLaunch: () => void;
  onLaunchWithoutPreparing: () => void;
  stoppingGame: boolean;
  forceStopAvailable: boolean;
  onStopGame: () => void;
  onSaveLauncherSettings: (settingsToolsClosed: boolean) => void;
  retryLabel: string;
  onRetry: () => void;
  runFailure: {
    summary: string;
    detail?: string;
    installRoot?: string;
    profileFingerprint?: string;
  } | null;
  onDismissRunFailure: () => void;
  onNavigate: (page: Page) => void;
  instrumentHull: InstrumentHullState;
  launchProfileName: string | null;
  modReadiness: ModReadiness | null;
}

export function HomePage({
  snapshot,
  status,
  message,
  messageTone,
  isReady,
  needsPreparation,
  optimizationPreset,
  preparation,
  updateStatus,
  launcherSettings,
  launcherDraft,
  launcherSettingsLoading,
  launcherSettingsSaving,
  launchSettingsDirty,
  operationBlocked,
  launchSettingsEditingBlocked,
  launchSettingsSaveBlocked,
  launchSettingsSaveBlockReason,
  theme,
  onLauncherChange,
  onChooseInstall,
  onPrimaryLaunch,
  onLaunchWithoutPreparing,
  stoppingGame,
  forceStopAvailable,
  onStopGame,
  onSaveLauncherSettings,
  retryLabel,
  onRetry,
  runFailure,
  onDismissRunFailure,
  onNavigate,
  instrumentHull,
  launchProfileName,
  modReadiness,
}: HomePageProps) {
  const [optionsOpen, setOptionsOpen] = useState(() => {
    try {
      return window.localStorage.getItem(HOME_OPTIONS_STORAGE_KEY) === "open";
    } catch {
      return false;
    }
  });
  const [hudVisible, setHudVisible] = useState(true);
  const hudTimer = useRef<number | null>(null);
  const homePresentation = useHomePresentation();
  const instrumentMotion = useInstrumentMotion();
  const {
    cache,
    cacheHealth,
    cacheLoading,
    cacheRepairing,
    preparationPlan,
    preparationPlanLoading,
    preparationCancelling,
    preparationPercent,
    preparationPhaseLabel,
    preparing,
    profilePrepared,
    textureStorage,
    prepare,
    stopPreparation,
  } = preparation;
  const awaitingStoragePlan = storagePlanApplies(textureStorage)
    && (preparationPlanLoading || !preparationPlan);
  const firstSetup = needsPreparation && (cache?.profiles.length ?? 0) === 0 && !snapshot?.lastRun;
  const storageBlocked = needsPreparation
    && !preparationPlanLoading
    && Boolean(preparationPlan && !preparationPlan.safeToPrepare);
  const cacheNeedsRepair = cacheHealth?.status === "repair-needed";
  const cacheBoundaryUnsafe = cacheHealth?.status === "unsafe";
  const cacheIdentityUnknown = cacheHealth?.status === "unknown";
  const cacheInspectionBlocked = cacheBoundaryUnsafe || cacheIdentityUnknown;
  const searchedLocations = (snapshot?.diagnostics ?? [])
    .filter((diagnostic) => diagnostic.startsWith("Searched "))
    .map((diagnostic) => diagnostic.slice("Searched ".length));
  const setupDiagnostics = (snapshot?.diagnostics ?? [])
    .filter((diagnostic) => !diagnostic.startsWith("Searched "))
    .filter((diagnostic) => !diagnostic.includes("--game") && !diagnostic.includes("--launcher"));
  const playtime = snapshot?.playtime;
  const applicableLastRun = lastRunForCurrentProfile(
    snapshot?.lastRun,
    snapshot?.selected?.installRoot,
    cache?.currentProfileFingerprint,
  );
  const lastAdapterHealth = applicableLastRun?.adapterHealth ?? null;
  const runFailureApplicability = launchSetupApplicability(
    runFailure,
    snapshot?.selected?.installRoot,
    cache?.currentProfileFingerprint,
  );
  const runFailureStale = runFailureApplicability === "foreign";
  useEffect(() => {
    if (runFailureStale) onDismissRunFailure();
  }, [onDismissRunFailure, runFailureStale]);
  const visibleRunFailure = runFailureStale ? null : runFailure;
  const hasPlaytime = Boolean(playtime?.readable && playtime.launches > 0 && playtime.totalMillis > 0);
  const playtimeTotal = hasPlaytime && playtime ? splitPlaytime(playtime.totalMillis) : null;
  const recoveryLayout = Boolean(visibleRunFailure) || status === "error";
  const activeLayout = status === "launching" || status === "running";
  const preparationLayout = isReady && !recoveryLayout && !activeLayout
    && (preparing || needsPreparation || !profilePrepared || cacheInspectionBlocked);
  const homeLayoutState = !isReady
    ? "setup"
    : recoveryLayout
      ? "recovery"
      : activeLayout
        ? "active"
        : preparationLayout
          ? "preparation"
          : "settled";
  const clearHudTimer = () => {
    if (hudTimer.current !== null) {
      window.clearTimeout(hudTimer.current);
      hudTimer.current = null;
    }
  };
  const keepHudVisible = () => {
    clearHudTimer();
    setHudVisible(true);
  };
  const scheduleHudFade = () => {
    clearHudTimer();
    if (homeLayoutState !== "settled" || optionsOpen) {
      setHudVisible(true);
      return;
    }
    hudTimer.current = window.setTimeout(() => {
      setHudVisible(false);
      hudTimer.current = null;
    }, 2200);
  };
  const revealHud = () => {
    keepHudVisible();
    scheduleHudFade();
  };
  useEffect(() => {
    clearHudTimer();
    if (homeLayoutState !== "settled" || optionsOpen) {
      setHudVisible(true);
      return clearHudTimer;
    }
    hudTimer.current = window.setTimeout(() => {
      setHudVisible(false);
      hudTimer.current = null;
    }, 2200);
    return clearHudTimer;
  }, [homeLayoutState, optionsOpen]);
  useEffect(() => {
    const root = document.documentElement;
    if (homeLayoutState === "settled") root.dataset.homeHud = hudVisible ? "visible" : "idle";
    else delete root.dataset.homeHud;
    return () => {
      delete root.dataset.homeHud;
    };
  }, [homeLayoutState, hudVisible]);
  const recoveryFirst = Boolean(visibleRunFailure || cacheInspectionBlocked || status === "error");
  const settledReady = isReady
    && !needsPreparation
    && !cacheNeedsRepair
    && !cacheInspectionBlocked
    && optimizationPreset !== "off";
  const modBlockingCount = modReadiness?.counts.blocking ?? 0;
  const modWarningCount = (modReadiness?.counts.warning ?? 0) + (modReadiness?.counts.unknown ?? 0);
  const toggleOptions = () => {
    setOptionsOpen((current) => {
      const next = !current;
      try {
        if (next) window.localStorage.setItem(HOME_OPTIONS_STORAGE_KEY, "open");
        else window.localStorage.removeItem(HOME_OPTIONS_STORAGE_KEY);
      } catch {
        // The view still toggles for this session if the WebView store is unavailable.
      }
      return next;
    });
  };
  const statusLabel = status === "launching"
    ? "Opening Starsector"
    : status === "running"
      ? "Game running"
      : preparing
        ? preparationPercent === null ? "Preparation in progress" : `Preparing ${preparationPercent}%`
        : status === "loading"
          ? "Finding Starsector"
          : !isReady
            ? "Installation required"
            : cacheInspectionBlocked
              ? "Prepared data needs attention"
              : cacheNeedsRepair
                ? "Prepared data needs repair"
                : firstSetup
                  ? null
                  : needsPreparation
                    ? "Preparation needed"
                    : optimizationPreset === "off"
                      ? "Optimizations off"
                      : null;
  const selectedHullIndex = instrumentHull.hulls.findIndex((hull) => hull.id === instrumentHull.selected.id);
  const cycleHull = (offset: number) => {
    if (instrumentHull.hulls.length < 2) return;
    const current = selectedHullIndex >= 0 ? selectedHullIndex : 0;
    const next = (current + offset + instrumentHull.hulls.length) % instrumentHull.hulls.length;
    instrumentHull.choose(instrumentHull.hulls[next].id);
  };
  const playtimeVisible = homePresentation.showPlaytime;
  const togglePlaytime = () => homePresentation.setShowPlaytime(!homePresentation.showPlaytime);

  const notice = (
    <NoticeBanner
      message={visibleRunFailure?.summary === message ? "" : message}
      tone={status === "error" ? "error" : messageTone}
      actionLabel={status === "error" ? retryLabel : undefined}
      onAction={status === "error" ? onRetry : undefined}
    />
  );
  const cacheRecovery = cacheInspectionBlocked ? (
    <section className="card run-recovery cache-recovery" aria-label="Prepared data needs attention">
      <div>
        <strong>{cacheIdentityUnknown ? "Prepared data couldn't be checked" : cacheBoundaryUnsafe ? "Prepared data location needs attention" : "Prepared data needs repair"}</strong>
        <p>{cacheHealth.issues[0]?.summary ?? "Some prepared data for this mod setup couldn't be validated."} Preflight left it in place. Starsector and your mods are unchanged.</p>
        {cacheHealth.issues.length > 1 ? <small>{cacheHealth.issues.length - 1} more issue{cacheHealth.issues.length === 2 ? "" : "s"} found.</small> : null}
      </div>
      <div className="run-recovery__actions">
        <button className="button button--quiet button--compact" type="button" onClick={() => onNavigate("speed")} disabled={cacheRepairing}>Review details</button>
      </div>
    </section>
  ) : null;
  const runRecovery = visibleRunFailure ? (
    <section className="card run-recovery" aria-label="Run needs attention" role="alert">
      <div>
        <strong>Run needs attention</strong>
        <p>{visibleRunFailure.summary}</p>
        {visibleRunFailure.detail ? (
          <details className="run-recovery__details">
            <summary>Technical details</summary>
            <pre>{visibleRunFailure.detail}</pre>
          </details>
        ) : null}
      </div>
      <RunRecoveryActions
        optimizationPreset={optimizationPreset}
        operationBlocked={operationBlocked}
        onRelaunch={onPrimaryLaunch}
        onGetHelp={() => onNavigate("help")}
        onDismiss={onDismissRunFailure}
      />
    </section>
  ) : null;
  const recoveryContent = <>{notice}{cacheRecovery}{runRecovery}</>;

  return (
    <>
      {recoveryFirst ? recoveryContent : null}

      <section
        className={`launch-console ${isReady ? "launch-console--ready" : "card launch-console--setup"} launch-console--${status} launch-console--layout-${homeLayoutState} ${cacheNeedsRepair ? "launch-console--repair-state" : ""} ${cacheInspectionBlocked ? "launch-console--attention-state" : ""} ${isReady && optionsOpen ? "launch-console--options-open" : "launch-console--minimal"} ${launchSettingsDirty ? "launch-console--settings-dirty" : ""} ${hasPlaytime && playtimeVisible ? "launch-console--has-playtime" : ""} ${hudVisible ? "home-hud--visible" : "home-hud--idle"}`}
        onPointerMove={(event) => {
          const target = event.target;
          if (target instanceof Element && target.closest(".home-hud-layer")) keepHudVisible();
          else revealHud();
        }}
        onPointerDown={keepHudVisible}
        onPointerLeave={scheduleHudFade}
        onFocusCapture={keepHudVisible}
        onBlurCapture={(event) => {
          if (!event.currentTarget.contains(event.relatedTarget)) scheduleHudFade();
        }}
      >
        <div className="launch-console__primary">
          {isReady ? (
            <div className="home-flight-instrument">
              <FlightInstrument hull={instrumentHull.selected} variant="stage" interactive framing={0.84} />
            </div>
          ) : null}
          {isReady ? (
            <div className="launch-console__status-line home-hud-layer">
              {status !== "running" && status !== "launching" && statusLabel ? (
                <div className={`status-chip ${settledReady ? "status-chip--ready" : ""}`}>
                  {settledReady ? <CheckIcon /> : <SparklesIcon />}
                  {statusLabel}
                </div>
              ) : null}
              {status !== "running" && status !== "launching" && (modBlockingCount > 0 || modWarningCount > 0) ? (
                <button className="status-chip status-chip--mod-attention" type="button" onClick={() => onNavigate("mods")}>
                  {modBlockingCount > 0
                    ? `${modBlockingCount} mod problem${modBlockingCount === 1 ? "" : "s"}`
                    : `${modWarningCount} mod warning${modWarningCount === 1 ? "" : "s"}`}
                </button>
              ) : null}
              {hasPlaytime ? (
                <button
                  className="home-display-toggle"
                  type="button"
                  aria-label="Playtime"
                  title={playtimeVisible ? "Hide time" : "Show time"}
                  aria-pressed={playtimeVisible}
                  onClick={(event) => {
                    togglePlaytime();
                    if (event.detail > 0) event.currentTarget.blur();
                    scheduleHudFade();
                  }}
                >
                  <ClockIcon />
                </button>
              ) : null}
              <button
                className="home-display-toggle"
                type="button"
                aria-label="Ship"
                title={homePresentation.mode === "compact" ? "Show ship" : "Hide ship"}
                aria-pressed={homePresentation.mode !== "compact"}
                onClick={(event) => {
                  homePresentation.setMode(homePresentation.mode === "compact" ? "hangar" : "compact");
                  if (event.detail > 0) event.currentTarget.blur();
                  scheduleHudFade();
                }}
              >
                <ShipIcon />
              </button>
              <button
                className="home-options-toggle"
                type="button"
                aria-expanded={optionsOpen}
                onClick={toggleOptions}
              >
                {optionsOpen ? "Hide options" : launchSettingsDirty ? "Options · changed" : "Options"}
                <ArrowIcon />
              </button>
            </div>
          ) : null}
          {isReady && playtime && hasPlaytime && playtimeTotal ? (
            <div className="home-playtime home-hud-layer" aria-label={`${formatPlaytime(playtime.totalMillis)} played across ${playtime.launches.toLocaleString()} recorded sessions`}>
              <strong>{playtimeTotal.value}<i>{playtimeTotal.unit}</i></strong>
              <span>{playtime.launches.toLocaleString()} sessions</span>
            </div>
          ) : null}
          {isReady && lastAdapterHealth?.reviewRecommended && status !== "running" && status !== "launching" ? (
            <span
              className="last-run-health last-run-health--review home-hud-layer"
              title={lastAdapterHealth.suggestedActions[0] ?? "Compatibility details from the latest Preflight launch"}
            >
              {adapterHealthLine(lastAdapterHealth)}
            </span>
          ) : null}
          {!isReady ? <h2>{status === "loading" ? "Finding Starsector…" : "Choose Starsector"}</h2> : null}
          {!isReady ? <p>{status === "loading" ? "Checking the usual installation locations." : "Select the folder containing Starsector.app, starsector.exe, or starsector.sh."}</p> : null}
          {isReady && !visibleRunFailure && (status === "ready" || status === "error") && snapshot?.selected ? (
            <HomeLaunchIdentity
              installRoot={snapshot.selected.installRoot}
              profileName={launchProfileName}
              onOpenProfiles={() => onNavigate("mods")}
            />
          ) : null}
          {isReady && (preparing || needsPreparation || !profilePrepared) ? (
            <div className="launch-console__note">
              <span>{preparing
                ? preparationPercent === null
                  ? `${preparationPhaseLabel ?? "Preparation continues"} · Resumed. Starsector stays closed when this finishes.`
                  : `${preparationPhaseLabel ?? "Preparing"} · Starsector opens when it’s ready.`
                : cacheNeedsRepair
                  ? "Damaged prepared data will be rebuilt. Game files, mods, and saves stay unchanged."
                : needsPreparation
                ? !storagePlanApplies(textureStorage)
                  ? "Prepared textures are skipped. The other startup caches keep working."
                  : preparationPlanLoading
                  ? "Inspecting this mod setup and calculating a safe disk requirement…"
                  : preparationPlan?.safeToPrepare
                    ? `Uses about ${formatBytes(preparationPlan.predictedRetainedTextureBytes ?? preparationPlan.predictedPackBytes)} · ${formatBytes(preparationPlan.usableBytes)} free.`
                    : preparationPlan
                      ? `Preparation needs ${formatBytes(preparationPlan.requiredFreeBytes)} free; ${formatBytes(preparationPlan.usableBytes)} is available.`
                      : "Storage must be calculated before preparation."
                : "Optimizations are off for this launch."}</span>
              {cacheInspectionBlocked ? <span>You can still launch at normal speed while Preflight leaves this prepared data alone.</span> : null}
            </div>
          ) : null}
          <div className="launch-console__actions">
            {isReady ? (
              <>
                <button
                  className="button button--primary button--launch"
                  type="button"
                  onClick={storageBlocked
                    ? () => void prepare(true, "minimal")
                    : cacheInspectionBlocked
                      ? () => onNavigate("speed")
                      : onPrimaryLaunch}
                  disabled={preparing || cacheRepairing || operationBlocked || status === "loading" || status === "error" || cacheLoading || (!storageBlocked && needsPreparation && !cacheNeedsRepair && !cacheInspectionBlocked && awaitingStoragePlan)}
                >
                  {needsPreparation ? <SparklesIcon /> : <PlayIcon />}
                  <span>{status === "launching" ? "Opening Starsector…" : status === "running" ? "Starsector is running" : preparing ? preparationPercent === null ? "Preparation in progress…" : `Preparing ${preparationPercent}%…` : cacheRepairing ? "Repairing prepared data…" : cacheLoading ? "Checking this mod setup…" : cacheInspectionBlocked ? "Review prepared data" : cacheNeedsRepair ? "Repair and launch" : preparationPlanLoading && needsPreparation ? "Calculating space…" : storageBlocked ? "Prepare with less disk" : firstSetup ? "Set up and launch" : needsPreparation ? "Prepare and launch" : "Launch Starsector"}</span>
                </button>
                {homeLayoutState === "settled" && homePresentation.mode !== "compact" ? (
                  <div className="home-motion-controls home-hud-layer" role="group" aria-label="Ship rotation">
                    <button
                      className="home-motion-toggle"
                      type="button"
                      aria-label="Reverse rotation"
                      title={instrumentMotion.direction === "clockwise" ? "Rotate counter-clockwise" : "Rotate clockwise"}
                      onClick={() => instrumentMotion.setDirection(instrumentMotion.direction === "clockwise" ? "counter-clockwise" : "clockwise")}
                    >
                      {instrumentMotion.direction === "clockwise" ? <OrbitClockwiseIcon /> : <OrbitCounterClockwiseIcon />}
                    </button>
                    <button
                      className="home-motion-toggle"
                      type="button"
                      aria-label={instrumentMotion.motion === "rotate" ? "Pause ship rotation" : "Resume ship rotation"}
                      title={instrumentMotion.motion === "rotate" ? "Pause rotation" : "Resume rotation"}
                      aria-pressed={instrumentMotion.motion === "still"}
                      onClick={() => instrumentMotion.setMotion(instrumentMotion.motion === "rotate" ? "still" : "rotate")}
                    >
                      {instrumentMotion.motion === "rotate" ? <PauseIcon /> : <PlayIcon />}
                    </button>
                  </div>
                ) : null}
                {preparing ? (
                  <button className="button button--quiet launch-console__stop" type="button" onClick={() => void stopPreparation()} disabled={preparationCancelling}>
                    {preparationCancelling ? "Stopping…" : "Stop safely"}
                  </button>
                ) : null}
                {status === "running" ? (
                  <button
                    className={`button ${forceStopAvailable ? "button--danger" : "button--quiet"} launch-console__stop`}
                    type="button"
                    onClick={onStopGame}
                    disabled={stoppingGame}
                  >
                    {stoppingGame ? "Stopping…" : forceStopAvailable ? "Force stop Starsector" : "Stop Starsector"}
                  </button>
                ) : null}
                {cacheNeedsRepair && !preparing && !cacheRepairing ? (
                  <button className="button button--quiet launch-console__stop" type="button" onClick={() => onNavigate("speed")}>Repair details</button>
                ) : null}
                {(storageBlocked || cacheInspectionBlocked) && !preparing && !cacheRepairing ? (
                  <button
                    className="button button--quiet launch-console__stop"
                    type="button"
                    onClick={onLaunchWithoutPreparing}
                    disabled={operationBlocked || status === "launching" || status === "running"}
                  >
                    Launch normally
                  </button>
                ) : null}
              </>
            ) : (
              <button className="button button--primary" type="button" onClick={onChooseInstall} disabled={status === "loading" || operationBlocked}><FolderIcon />Choose game folder</button>
            )}
          </div>
          {isReady && homeLayoutState === "settled" ? (
            <div className="home-ship-picker home-hud-layer" aria-label="Display ship">
              <button type="button" aria-label="Previous display ship" title="Previous ship" onClick={() => cycleHull(-1)} disabled={instrumentHull.hulls.length < 2}><ArrowIcon /></button>
              <button className="home-ship-name" type="button" title="Choose a display ship" onClick={() => onNavigate("hangar")}>{instrumentHull.selected.name}</button>
              <button type="button" aria-label="Next display ship" title="Next ship" onClick={() => cycleHull(1)} disabled={instrumentHull.hulls.length < 2}><ArrowIcon /></button>
            </div>
          ) : null}
        </div>
        {isReady && optionsOpen && launcherDraft && launcherSettings ? (
          <QuickGameSettings
            settings={launcherSettings}
            draft={launcherDraft}
            dirty={launchSettingsDirty}
            saving={launcherSettingsSaving}
            editingDisabled={launchSettingsEditingBlocked || launcherSettingsLoading}
            saveBlocked={launchSettingsSaveBlocked || launcherSettingsLoading}
            saveBlockReason={launchSettingsSaveBlockReason}
            onChange={onLauncherChange}
            onOpenAll={() => onNavigate("launch")}
            onSave={onSaveLauncherSettings}
          />
        ) : isReady && optionsOpen ? <div className="quick-settings quick-settings--loading">{launcherSettingsLoading ? "Reading game settings…" : "Game settings unavailable"}</div> : null}
      </section>

      {updateStatus?.available ? (
        <section className="update-notice" aria-label="Preflight update available">
          <strong>Preflight {updateStatus.version} is available</strong>
          <button type="button" className="text-button" onClick={() => onNavigate("settings")}>Review update <ArrowIcon /></button>
        </section>
      ) : null}

      {!recoveryFirst ? recoveryContent : null}

      {!isReady && snapshot?.diagnostics.length ? (
        <details className="setup-diagnostics">
          <summary>Why wasn’t Starsector found?</summary>
          {setupDiagnostics.length > 0 ? <ul>{setupDiagnostics.map((diagnostic) => <li key={diagnostic}>{diagnostic}</li>)}</ul> : null}
          {searchedLocations.length > 0 ? (
            <>
              <p className="setup-diagnostics__label">Preflight looked in these places:</p>
              <ul className="setup-diagnostics__paths">{searchedLocations.map((location) => <li key={location}>{location}</li>)}</ul>
              <p className="setup-diagnostics__label">If your installation isn’t one of these, choose its folder above.</p>
            </>
          ) : null}
        </details>
      ) : null}
    </>
  );
}
