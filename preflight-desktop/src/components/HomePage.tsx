import { ArrowIcon, CheckIcon, FolderIcon, PlayIcon, SparklesIcon } from "../icons";
import type { Page } from "./DesktopShell";
import { QuickGameSettings } from "./QuickGameSettings";
import { NoticeBanner } from "./NoticeBanner";
import { optimizationPresets } from "./PreparationPage";
import type { usePreparation } from "../usePreparation";
import type { useProfiles } from "../useProfiles";
import { formatBytes, friendlyPlatform, shortPath } from "../uiFormat";
import type {
  AppStatus,
  DesktopSnapshot,
  LaunchSettings,
  LaunchSettingsUpdate,
  NoticeTone,
  OptimizationDomain,
  OptimizationPreset,
  UpdateStatus,
} from "../types";

type PreparationState = ReturnType<typeof usePreparation>;
type ProfilesState = ReturnType<typeof useProfiles>;

const flightPlot = (
  <div className="flight-plot" aria-hidden="true">
    <svg viewBox="0 0 480 250" role="presentation">
      <path className="flight-plot__guide" d="M20 205H458M72 18V232M382 18V232" />
      <path className="flight-plot__route" d="M28 203C105 31 286 18 449 155" />
      <circle cx="28" cy="203" r="4" />
      <circle cx="242" cy="52" r="4" />
      <circle cx="449" cy="155" r="5" />
      <path className="flight-plot__craft" d="m431 139 21 16-18 2-8 14 1-19-12-9Z" />
    </svg>
  </div>
);

interface HomePageProps {
  snapshot: DesktopSnapshot | null;
  status: AppStatus;
  message: string;
  messageTone: NoticeTone;
  isReady: boolean;
  needsPreparation: boolean;
  optimizationPreset: OptimizationPreset;
  disabledOptimizationDomains: OptimizationDomain[];
  preparation: PreparationState;
  profilesState: ProfilesState;
  updateStatus: UpdateStatus | null;
  launcherSettings: LaunchSettings | null;
  launcherDraft: LaunchSettingsUpdate | null;
  launcherSettingsLoading: boolean;
  launcherSettingsSaving: boolean;
  launchSettingsDirty: boolean;
  operationBlocked: boolean;
  onLauncherChange: (change: Partial<LaunchSettingsUpdate>) => void;
  onChooseInstall: () => void;
  onPrimaryLaunch: () => void;
  onSaveLauncherSettings: () => void;
  retryLabel: string;
  onRetry: () => void;
  runFailure: string | null;
  onDismissRunFailure: () => void;
  onNavigate: (page: Page) => void;
}

export function HomePage({
  snapshot,
  status,
  message,
  messageTone,
  isReady,
  needsPreparation,
  optimizationPreset,
  disabledOptimizationDomains,
  preparation,
  profilesState,
  updateStatus,
  launcherSettings,
  launcherDraft,
  launcherSettingsLoading,
  launcherSettingsSaving,
  launchSettingsDirty,
  operationBlocked,
  onLauncherChange,
  onChooseInstall,
  onPrimaryLaunch,
  onSaveLauncherSettings,
  retryLabel,
  onRetry,
  runFailure,
  onDismissRunFailure,
  onNavigate,
}: HomePageProps) {
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
    stopPreparation,
  } = preparation;
  const { profiles, profilesLoading } = profilesState;
  const selectedOptimization = optimizationPresets.find((preset) => preset.id === optimizationPreset)
    ?? optimizationPresets[0];
  const activeProfile = profiles?.profiles.find((profile) => profile.active) ?? null;
  const firstSetup = needsPreparation && (cache?.profiles.length ?? 0) === 0 && !snapshot?.lastRun;
  const storageBlocked = needsPreparation
    && !preparationPlanLoading
    && Boolean(preparationPlan && !preparationPlan.safeToPrepare);
  const cacheNeedsRepair = cacheHealth?.status === "repair-needed";
  const cacheBoundaryUnsafe = cacheHealth?.status === "unsafe";
  const cacheIdentityUnknown = cacheHealth?.status === "unknown";
  const cacheInspectionBlocked = cacheBoundaryUnsafe || cacheIdentityUnknown;
  const statusLabel = status === "launching"
    ? "Opening Starsector"
    : status === "running"
      ? "Game running"
      : preparing
        ? `Preparing ${preparationPercent}%`
        : status === "loading"
          ? "Finding Starsector"
          : !isReady
            ? "Installation required"
            : firstSetup
              ? "First launch setup"
              : needsPreparation
                ? "This mod setup needs preparation"
                : "Ready";

  return (
    <>
      <section className={`launch-console card ${isReady ? "launch-console--ready" : "launch-console--setup"} ${isReady && launcherDraft && launcherSettings ? "launch-console--configured" : ""}`}>
        <div className="launch-console__primary">
          {flightPlot}
          {status !== "ready" || preparing || needsPreparation || !isReady ? (
            <div className={`status-chip ${isReady && !needsPreparation ? "status-chip--ready" : ""}`}>
              {isReady && !needsPreparation ? <CheckIcon /> : <SparklesIcon />}
              {statusLabel}
            </div>
          ) : null}
          {!isReady ? <h2>{status === "loading" ? "Finding Starsector…" : "Choose your Starsector installation"}</h2> : null}
          {!isReady ? <p>{status === "loading" ? "Checking the usual installation locations." : "Select the folder containing Starsector.app, starsector.exe, or starsector.sh."}</p> : null}
          <div className="launch-console__actions">
            {isReady ? (
              <>
                <button
                  className="button button--primary button--launch"
                  type="button"
                  onClick={storageBlocked || cacheInspectionBlocked ? () => onNavigate("prepare") : onPrimaryLaunch}
                  disabled={preparing || cacheRepairing || (!storageBlocked && (operationBlocked || status === "loading" || status === "error" || cacheLoading || (needsPreparation && !cacheNeedsRepair && !cacheInspectionBlocked && (preparationPlanLoading || !preparationPlan))))}
                >
                  {needsPreparation ? <SparklesIcon /> : <PlayIcon />}
                  {status === "launching" ? "Opening Starsector…" : status === "running" ? "Starsector is running" : preparing ? `Preparing ${preparationPercent}%…` : cacheRepairing ? "Repairing prepared data…" : cacheLoading ? "Checking this mod setup…" : cacheInspectionBlocked ? "Review profile check" : cacheNeedsRepair ? "Repair and launch" : preparationPlanLoading && needsPreparation ? "Calculating space…" : storageBlocked ? "Review storage" : needsPreparation ? "Prepare and launch" : "Launch Starsector"}
                </button>
                {preparing ? (
                  <button className="button button--quiet launch-console__stop" type="button" onClick={() => void stopPreparation()} disabled={preparationCancelling}>
                    {preparationCancelling ? "Stopping…" : "Stop safely"}
                  </button>
                ) : null}
              </>
            ) : (
              <button className="button button--primary" type="button" onClick={onChooseInstall} disabled={status === "loading" || operationBlocked}><FolderIcon />Choose game folder</button>
            )}
          </div>
          {isReady ? (
            <div className="launch-console__note">
              <strong>{selectedOptimization.label}</strong>
              <span>{preparing
                ? `${preparationPhaseLabel ?? "Preparing this mod setup"} · Starsector will open automatically. Finished work stays reusable if you stop.`
                : needsPreparation
                ? preparationPlanLoading
                  ? "Inspecting this mod setup and calculating a safe disk requirement…"
                  : preparationPlan?.safeToPrepare
                    ? `${firstSetup ? "Initial setup" : "Preparation needed"} · ${textureStorage === "balanced" ? "Balanced" : "Fastest"} uses about ${formatBytes(preparationPlan.predictedAdditionalBytes)}; ${formatBytes(preparationPlan.requiredFreeBytes)} must be free; ${formatBytes(preparationPlan.usableBytes)} is available. Starsector and its mods stay where they are.`
                    : preparationPlan?.refusalReason ?? "Storage must be calculated before preparation."
                : profilePrepared
                  ? `Prepared · ${formatBytes(cache?.profiles.find((profile) => profile.current)?.bytes ?? 0)}${disabledOptimizationDomains.length > 0 ? ` · ${disabledOptimizationDomains.length} prepared cache${disabledOptimizationDomains.length === 1 ? "" : "s"} off` : ""}`
                  : "Preparation is disabled for this troubleshooting launch."}</span>
            </div>
          ) : null}
        </div>
        {isReady && launcherDraft && launcherSettings ? (
          <QuickGameSettings
            settings={launcherSettings}
            draft={launcherDraft}
            dirty={launchSettingsDirty}
            saving={launcherSettingsSaving}
            disabled={operationBlocked || launcherSettingsLoading}
            onChange={onLauncherChange}
            onOpenAll={() => onNavigate("launch")}
            onSave={onSaveLauncherSettings}
          />
        ) : isReady ? <div className="quick-settings quick-settings--loading">{launcherSettingsLoading ? "Reading game settings…" : "Game settings unavailable"}</div> : null}
      </section>

      {updateStatus?.available ? (
        <section className="update-notice" aria-label="Preflight update available">
          <strong>Preflight {updateStatus.version} is available</strong>
          <button type="button" className="text-button" onClick={() => onNavigate("settings")}>Review update <ArrowIcon /></button>
        </section>
      ) : null}

      <NoticeBanner
        message={message}
        tone={status === "error" ? "error" : messageTone}
        actionLabel={status === "error" ? retryLabel : undefined}
        onAction={status === "error" ? onRetry : undefined}
      />

      {cacheNeedsRepair || cacheInspectionBlocked ? (
        <section className="card run-recovery cache-recovery" aria-label="Prepared data needs repair">
          <div>
            <strong>{cacheIdentityUnknown ? "Current mod setup couldn't be inspected" : cacheBoundaryUnsafe ? "Cache location needs attention" : "Prepared data needs repair"}</strong>
            <p>{cacheHealth.issues[0]?.summary ?? "Some prepared data for this mod setup couldn't be validated."} Starsector and your mods are unchanged.</p>
            {cacheHealth.issues.length > 1 ? <small>{cacheHealth.issues.length - 1} more issue{cacheHealth.issues.length === 2 ? "" : "s"} found.</small> : null}
          </div>
          <div className="run-recovery__actions">
            {!cacheInspectionBlocked ? <button className="button button--primary button--compact" type="button" onClick={onPrimaryLaunch} disabled={operationBlocked || cacheRepairing}>{cacheRepairing ? "Repairing…" : "Repair and launch"}</button> : null}
            <button className="button button--quiet button--compact" type="button" onClick={() => onNavigate("prepare")} disabled={cacheRepairing}>Details</button>
          </div>
        </section>
      ) : null}

      {runFailure ? (
        <section className="card run-recovery" aria-label="Run needs attention">
          <div>
            <strong>Run details saved</strong>
            <p>{runFailure}</p>
          </div>
          <div className="run-recovery__actions">
            <button className="button button--primary button--compact" type="button" onClick={() => onNavigate("reports")}>Open run reports</button>
            <button className="button button--quiet button--compact" type="button" onClick={onDismissRunFailure}>Dismiss</button>
          </div>
        </section>
      ) : null}

      <section className="card home-overview" aria-label="Current Preflight setup">
        <div className="home-fact">
          <span>Mod profile</span>
          <strong>{profilesLoading ? "Reading…" : activeProfile?.name ?? (profiles ? `${profiles.enabledMods.length} enabled mods` : "Unavailable")}</strong>
          <small>{profilesLoading
            ? "Checking the current mod list"
            : activeProfile
              ? `Named by you · ${activeProfile.modCount.toLocaleString()} mod${activeProfile.modCount === 1 ? "" : "s"}`
              : profiles
                ? "Current list isn't saved as a profile"
                : "The current mod list couldn’t be read"}</small>
          <button className="text-button" type="button" onClick={() => onNavigate("profiles")} disabled={!isReady}>Manage profiles <ArrowIcon /></button>
        </div>
        <div className="home-fact">
          <span>Preflight data</span>
          <strong>{cacheLoading ? "Reading…" : cache ? formatBytes(cache.total.bytes) : "Unavailable"}</strong>
          <button className="text-button" type="button" onClick={() => onNavigate("prepare")} disabled={!isReady}>Storage <ArrowIcon /></button>
        </div>
        <div className="home-fact home-fact--installation">
          <span>Installation</span>
          <strong>{isReady && snapshot?.selected ? shortPath(snapshot.selected.installRoot) : "Not selected"}</strong>
          <small>{isReady && snapshot ? `${friendlyPlatform(snapshot.platform)} · ${snapshot.selected?.kind.replace("-", " ")}${snapshot.candidates.length > 1 ? ` · ${snapshot.candidates.length} detected candidates` : " · found automatically"}` : "Choose the game folder to begin."}</small>
          <button type="button" className="text-button" onClick={onChooseInstall} aria-label="Change Starsector installation" disabled={operationBlocked}>Change <ArrowIcon /></button>
        </div>
      </section>
      {!isReady && snapshot?.diagnostics.length ? (
        <details className="setup-diagnostics">
          <summary>Why wasn’t Starsector found?</summary>
          <ul>{snapshot.diagnostics.map((diagnostic) => <li key={diagnostic}>{diagnostic}</li>)}</ul>
        </details>
      ) : null}
    </>
  );
}
