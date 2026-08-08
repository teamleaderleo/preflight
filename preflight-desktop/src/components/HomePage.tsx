import { ArrowIcon, CheckIcon, FolderIcon, PlayIcon, SparklesIcon } from "../icons";
import type { Page } from "./DesktopShell";
import { QuickGameSettings } from "./QuickGameSettings";
import { optimizationPresets } from "./PreparationPage";
import type { usePreparation } from "../usePreparation";
import type { useProfiles } from "../useProfiles";
import { formatBytes, friendlyPlatform, shortPath } from "../uiFormat";
import type {
  AppStatus,
  DesktopSnapshot,
  LaunchSettings,
  LaunchSettingsUpdate,
  OptimizationDomain,
  OptimizationPreset,
  UpdateStatus,
} from "../types";

type PreparationState = ReturnType<typeof usePreparation>;
type ProfilesState = ReturnType<typeof useProfiles>;

interface HomePageProps {
  snapshot: DesktopSnapshot | null;
  status: AppStatus;
  message: string;
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
  onRetry: () => void;
  onNavigate: (page: Page) => void;
  onSelectProfile: (name: string) => void;
}

export function HomePage({
  snapshot,
  status,
  message,
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
  onRetry,
  onNavigate,
  onSelectProfile,
}: HomePageProps) {
  const {
    cache,
    cacheLoading,
    preparationPlan,
    preparationPlanLoading,
    preparing,
    profilePrepared,
    textureStorage,
  } = preparation;
  const { profiles, profilesLoading } = profilesState;
  const selectedOptimization = optimizationPresets.find((preset) => preset.id === optimizationPreset)
    ?? optimizationPresets[0];
  const activeProfile = profiles?.profiles.find((profile) => profile.active) ?? null;

  return (
    <>
      <section className={`launch-console card ${isReady ? "launch-console--ready" : "launch-console--setup"}`}>
        <div className="launch-console__primary">
          {status === "running" || preparing || needsPreparation || !isReady ? (
            <div className={`status-chip ${isReady && !needsPreparation ? "status-chip--ready" : ""}`}>
              {isReady && !needsPreparation ? <CheckIcon /> : <SparklesIcon />}
              {status === "running" ? "Game running" : preparing ? "Preparing profile" : needsPreparation ? "Profile changed" : "Installation required"}
            </div>
          ) : null}
          {!isReady ? <h2>Choose the game folder</h2> : null}
          {!isReady ? <p>Select the folder that contains the Starsector launcher.</p> : null}
          <div className="launch-console__actions">
            {isReady ? (
              <button className="button button--primary button--launch" type="button" onClick={onPrimaryLaunch} disabled={operationBlocked || status === "loading" || cacheLoading || (needsPreparation && (preparationPlanLoading || !preparationPlan?.safeToPrepare))}>
                {needsPreparation ? <SparklesIcon /> : <PlayIcon />}
                {status === "running" ? "Starsector is running" : preparing ? "Preparing…" : cacheLoading ? "Checking profile…" : preparationPlanLoading && needsPreparation ? "Calculating space…" : needsPreparation ? "Prepare and launch" : "Launch Starsector"}
              </button>
            ) : (
              <button className="button button--primary" type="button" onClick={onChooseInstall} disabled={status === "loading" || operationBlocked}><FolderIcon />Choose game folder</button>
            )}
          </div>
          {isReady ? (
            <div className="launch-console__note">
              <strong>{selectedOptimization.label}</strong>
              <span>{needsPreparation
                ? preparationPlanLoading
                  ? "Reading the winning textures and calculating a safe disk requirement…"
                  : preparationPlan?.safeToPrepare
                    ? `${textureStorage === "balanced" ? "Balanced" : "Fastest"} predicts ${formatBytes(preparationPlan.predictedAdditionalBytes)} additional; ${formatBytes(preparationPlan.usableBytes)} is available.`
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

      {message ? (
        <div className={`notice ${status === "error" ? "notice--error" : ""}`} role="status">
          <span>{status === "error" ? "!" : "✦"}</span>
          <p>{message}</p>
          {status === "error" ? <button type="button" onClick={onRetry}>Try again</button> : null}
        </div>
      ) : null}

      <section className="card home-overview" aria-label="Current Preflight setup">
        <div className="home-fact">
          <span>Profile</span>
          {profiles && profiles.profiles.length > 0 ? (
            <select className="home-profile-select" aria-label="Active profile" value={activeProfile?.name ?? ""} disabled={operationBlocked} onChange={(event) => {
              const name = event.target.value;
              if (name && name !== activeProfile?.name) onSelectProfile(name);
            }}>
              {!activeProfile ? <option value="">Current mod list</option> : null}
              {profiles.profiles.map((profile) => <option value={profile.name} key={profile.name}>{profile.name}</option>)}
            </select>
          ) : <strong>{profilesLoading ? "Reading…" : `${profiles?.enabledMods.length ?? 0} enabled mods`}</strong>}
          <button className="text-button" type="button" onClick={() => onNavigate("profiles")} disabled={!isReady}>Manage <ArrowIcon /></button>
        </div>
        <div className="home-fact">
          <span>Preflight data</span>
          <strong>{cacheLoading ? "Reading…" : formatBytes(cache?.total.bytes ?? 0)}</strong>
          <button className="text-button" type="button" onClick={() => onNavigate("prepare")} disabled={!isReady}>Storage <ArrowIcon /></button>
        </div>
        <div className="home-fact home-fact--installation">
          <span>Installation</span>
          <strong>{isReady && snapshot?.selected ? shortPath(snapshot.selected.installRoot) : "Not selected"}</strong>
          <small>{isReady && snapshot ? `${friendlyPlatform(snapshot.platform)} · ${snapshot.selected?.kind.replace("-", " ")}` : "Choose the game folder to begin."}</small>
          <button type="button" className="text-button" onClick={onChooseInstall} aria-label="Change Starsector installation" disabled={operationBlocked}>Change <ArrowIcon /></button>
        </div>
      </section>
    </>
  );
}
