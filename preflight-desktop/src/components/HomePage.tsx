import { ArrowIcon, CheckIcon, FolderIcon, PlayIcon, SparklesIcon } from "../icons";
import type { Page } from "./DesktopShell";
import type { ThemePreference } from "../useTheme";
import { HangarStage } from "./HangarStage";
import { QuickGameSettings } from "./QuickGameSettings";
import { NoticeBanner } from "./NoticeBanner";
import { storagePlanApplies, type usePreparation } from "../usePreparation";
import type { useProfiles } from "../useProfiles";
import { formatBytes, formatPlaytime, formatSavedAt, friendlyPlatform, shortPath } from "../uiFormat";
import type {
  AppStatus,
  DesktopSnapshot,
  LaunchSettings,
  LaunchSettingsUpdate,
  NoticeTone,
  OptimizationPreset,
  UpdateStatus,
  WireframeHull,
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
  preparation: PreparationState;
  profilesState: ProfilesState;
  updateStatus: UpdateStatus | null;
  launcherSettings: LaunchSettings | null;
  launcherDraft: LaunchSettingsUpdate | null;
  launcherSettingsLoading: boolean;
  launcherSettingsSaving: boolean;
  launchSettingsDirty: boolean;
  operationBlocked: boolean;
  hull: WireframeHull;
  theme: Exclude<ThemePreference, "system">;
  onLauncherChange: (change: Partial<LaunchSettingsUpdate>) => void;
  onChooseInstall: () => void;
  onPrimaryLaunch: () => void;
  onLaunchWithoutPreparing: () => void;
  stoppingGame: boolean;
  forceStopAvailable: boolean;
  onStopGame: () => void;
  onSaveLauncherSettings: () => void;
  retryLabel: string;
  onRetry: () => void;
  runFailure: { summary: string; detail?: string } | null;
  onDismissRunFailure: () => void;
  onOpenStorage: () => void;
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
  preparation,
  profilesState,
  updateStatus,
  launcherSettings,
  launcherDraft,
  launcherSettingsLoading,
  launcherSettingsSaving,
  launchSettingsDirty,
  operationBlocked,
  hull,
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
  onOpenStorage,
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
    prepare,
    stopPreparation,
  } = preparation;
  // Minimal storage prepares no textures, so there is no plan and nothing to gate on. Without this
  // the launch button stays disabled forever on the one setting a short-of-space user would pick.
  const awaitingStoragePlan = storagePlanApplies(textureStorage)
    && (preparationPlanLoading || !preparationPlan);
  const { profiles, profilesLoading, profileBusy, reviewProfile } = profilesState;
  const activeProfile = profiles?.profiles.find((profile) => profile.active) ?? null;
  const accelerationBytes = cache?.groups.find((group) => group.id === "acceleration")?.bytes ?? 0;
  // Nothing to pick between until there is somewhere to switch to, so the card stays plain text.
  const switchable = (profiles?.profiles ?? []).filter((profile) => !profile.active && profile.canActivate);
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
  // Discovery is shared with the command line and says so when it fails: "use --game/--launcher".
  // There is no command line here, and the folder picker above is that same fix already offered, so
  // the flag advice is dropped rather than shown to someone who cannot act on it.
  const setupDiagnostics = (snapshot?.diagnostics ?? [])
    .filter((diagnostic) => !diagnostic.startsWith("Searched "))
    .filter((diagnostic) => !diagnostic.includes("--game") && !diagnostic.includes("--launcher"));
  const playtime = snapshot?.playtime;
  const hasPlaytime = Boolean(playtime?.readable && playtime.launches > 0 && playtime.totalMillis > 0);
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
            : cacheNeedsRepair
              ? "Prepared data needs repair"
              : firstSetup
              ? "First launch setup"
              : needsPreparation
                ? "Preparation needed"
                : optimizationPreset === "off"
                  ? "Optimizations off"
                  : "Fast launch ready";
  /*
   * The chip is always shown in the settled state, and says which of the two launches the button
   * is about to perform. "Ready" alone is the page title's job. Whether the next launch is the
   * fast one is the single piece of status a performance launcher owes its main screen, and it is
   * the only thing here that a player cannot find out by looking at the game.
   */

  /*
   * The stage caption. The prototype put the profile here and it was right: on a screen whose job
   * is "press this to play", the mod setup is the one thing that changes what pressing it does.
   */
  const stageContext = profilesLoading
    ? "Reading mod list"
    : activeProfile
      ? `${activeProfile.name} · ${activeProfile.modCount.toLocaleString()} mod${activeProfile.modCount === 1 ? "" : "s"}`
      : profiles
        ? `${profiles.enabledMods.length.toLocaleString()} enabled mod${profiles.enabledMods.length === 1 ? "" : "s"}`
        : "Mod list unavailable";

  return (
    <>
      <section className={`launch-console card launch-console--${status} ${isReady ? "launch-console--ready" : "launch-console--setup"} ${launchSettingsDirty ? "launch-console--settings-dirty" : ""}`}>
        {isReady ? <HangarStage hull={hull} context={stageContext} ready={isReady} /> : null}
        <div className="launch-console__primary">
          {isReady ? null : flightPlot}
          {/*
            * Before an installation is chosen the heading below already says "Installation
            * required" in longer words, and the chip said it again directly above it. A chip is
            * worth a line when it carries something the heading does not; here it only made the
            * screen look busier than the one decision it asks for.
            */}
          {isReady && status !== "running" && status !== "launching" ? (
            <div className="launch-console__status-line">
              <div className={`status-chip ${isReady && !needsPreparation ? "status-chip--ready" : ""}`}>
                {isReady && !needsPreparation && optimizationPreset !== "off" ? <CheckIcon /> : <SparklesIcon />}
                {statusLabel}
              </div>
              {hasPlaytime && playtime ? (
                <span
                  className="playtime-readout"
                  aria-label={`${formatPlaytime(playtime.totalMillis)} played across ${playtime.launches} recorded sessions`}
                  title={`Time Starsector was open across ${playtime.launches.toLocaleString()} recorded sessions`}
                >
                  <strong>{formatPlaytime(playtime.totalMillis)}</strong> played
                </span>
              ) : null}
            </div>
          ) : null}
          {!isReady ? <h2>{status === "loading" ? "Finding Starsector…" : "Choose your Starsector installation"}</h2> : null}
          {!isReady ? <p>{status === "loading" ? "Checking the usual installation locations." : "Select the folder containing Starsector.app, starsector.exe, or starsector.sh."}</p> : null}
          {/*
            * Nothing on this screen used to say what happens after the folder is chosen, so the
            * first thing Preflight ever asks of somebody is a decision with no stated consequence.
            * The one-off cost and the fact that the game is left alone are both answers people
            * look for before handing an unsigned program their game folder.
            */}
          {!isReady && status !== "loading" ? <p className="setup-next">Preflight prepares your mods once, then opens Starsector. It never moves the game, mods, or saves.</p> : null}
          <div className="launch-console__actions">
            {isReady ? (
              <>
                <button
                  key={theme}
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
                  <span>{status === "launching" ? "Opening Starsector…" : status === "running" ? "Starsector is running" : preparing ? `Preparing ${preparationPercent}%…` : cacheRepairing ? "Repairing prepared data…" : cacheLoading ? "Checking this mod setup…" : cacheInspectionBlocked ? "Review profile check" : cacheNeedsRepair ? "Repair and launch" : preparationPlanLoading && needsPreparation ? "Calculating space…" : storageBlocked ? "Prepare with less disk" : needsPreparation ? "Prepare and launch" : "Launch Starsector"}</span>
                </button>
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
                {/*
                  * Preflight is a launcher first. A refused preparation used to leave the game with no
                  * way to start from here at all, which is the one outcome a launcher cannot have: the
                  * user goes and finds the original shortcut instead. Launching unprepared is already
                  * a supported path -- every missing artifact falls back to the game's own loader --
                  * so the escape hatch is the ordinary launch, offered where it is needed.
                  */}
                {(storageBlocked || cacheInspectionBlocked) && !preparing && !cacheRepairing ? (
                  <button
                    className="button button--quiet launch-console__stop"
                    type="button"
                    onClick={onLaunchWithoutPreparing}
                    disabled={operationBlocked || status === "launching" || status === "running"}
                  >
                    Launch at normal speed
                  </button>
                ) : null}
              </>
            ) : (
              <button className="button button--primary" type="button" onClick={onChooseInstall} disabled={status === "loading" || operationBlocked}><FolderIcon />Choose game folder</button>
            )}
          </div>
          {isReady && (preparing || needsPreparation || !profilePrepared) ? (
            <div className="launch-console__note">
              <span>{preparing
                ? `${preparationPhaseLabel ?? "Preparing"} · Starsector opens automatically. Finished work stays reusable if you stop.`
                : cacheNeedsRepair
                  ? "Damaged prepared data will be rebuilt. Game files, mods, and saves stay unchanged."
                : needsPreparation
                ? !storagePlanApplies(textureStorage)
                  ? "Minimal preparation uses a few megabytes. Starsector opens when it’s ready."
                  : preparationPlanLoading
                  ? "Inspecting this mod setup and calculating a safe disk requirement…"
                  : preparationPlan?.safeToPrepare
                    ? `${firstSetup ? "Initial setup" : "Preparation needed"} · ${textureStorage === "balanced" ? "Balanced" : "Fastest"} uses about ${formatBytes(preparationPlan.predictedAdditionalBytes)}. ${formatBytes(preparationPlan.requiredFreeBytes)} free required; ${formatBytes(preparationPlan.usableBytes)} available.`
                    : preparationPlan
                      ? `Full preparation needs ${formatBytes(preparationPlan.requiredFreeBytes)} free; ${formatBytes(preparationPlan.usableBytes)} is available. Minimal uses a few megabytes.`
                      : "Storage must be calculated before preparation."
                : "Optimizations are off for this launch."}</span>
              {cacheInspectionBlocked ? <span>You can still launch at normal speed while Preflight leaves this cache alone.</span> : null}
            </div>
          ) : isReady && !preparing && !cacheRepairing && !storageBlocked && !cacheInspectionBlocked ? (
            <div className="launch-console__posture" aria-hidden="true">
              <span>{optimizationPreset === "off" ? "Original code and assets · Vanilla fallback" : "Accelerations active · Balanced storage"}</span>
            </div>
          ) : null}
        </div>
      </section>

      {/*
        * Resolution and sound used to share the console with the launch button, which made the
        * first thing anyone sees a screen of settings with a launch button in it. They are still
        * one scroll away and still two clicks from the game, but the console is now about the
        * launch, and the ship is what fills the space they were taking.
        */}
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

      {updateStatus?.available ? (
        <section className="update-notice" aria-label="Preflight update available">
          <strong>Preflight {updateStatus.version} is available</strong>
          <button type="button" className="text-button" onClick={() => onNavigate("settings")}>Review update <ArrowIcon /></button>
        </section>
      ) : null}

      <NoticeBanner
        message={runFailure?.summary === message ? "" : message}
        tone={status === "error" ? "error" : messageTone}
        actionLabel={status === "error" ? retryLabel : undefined}
        onAction={status === "error" ? onRetry : undefined}
      />

      {cacheInspectionBlocked ? (
        <section className="card run-recovery cache-recovery" aria-label="Prepared data needs repair">
          <div>
            <strong>{cacheIdentityUnknown ? "Current mod setup couldn't be inspected" : cacheBoundaryUnsafe ? "Cache location needs attention" : "Prepared data needs repair"}</strong>
            <p>{cacheHealth.issues[0]?.summary ?? "Some prepared data for this mod setup couldn't be validated."} Starsector and your mods are unchanged.</p>
            {cacheHealth.issues.length > 1 ? <small>{cacheHealth.issues.length - 1} more issue{cacheHealth.issues.length === 2 ? "" : "s"} found.</small> : null}
          </div>
          <div className="run-recovery__actions">
            <button className="button button--quiet button--compact" type="button" onClick={() => onNavigate("speed")} disabled={cacheRepairing}>Details</button>
          </div>
        </section>
      ) : null}

      {runFailure ? (
        <section className="card run-recovery" aria-label="Run needs attention" role="alert">
          <div>
            <strong>Run needs attention</strong>
            <p>{runFailure.summary}</p>
            {runFailure.detail ? (
              <details className="run-recovery__details">
                <summary>Technical details</summary>
                <pre>{runFailure.detail}</pre>
              </details>
            ) : null}
          </div>
          <div className="run-recovery__actions">
            <button className="button button--primary button--compact" type="button" onClick={onPrimaryLaunch} disabled={operationBlocked}>Relaunch</button>
            <button className="button button--quiet button--compact" type="button" onClick={() => onNavigate("help")}>Get help</button>
            <button className="button button--quiet button--compact" type="button" onClick={onDismissRunFailure}>Dismiss</button>
          </div>
        </section>
      ) : null}

      {isReady && !needsPreparation && !cacheInspectionBlocked ? <section className="card home-overview" aria-label="Current Preflight setup">
        <div className="home-fact">
          <span>Mod profile</span>
          {switchable.length > 0 ? (
            <select
              className="home-profile-select"
              aria-label="Mod profile"
              value={activeProfile?.name ?? ""}
              disabled={profileBusy || operationBlocked}
              onChange={(event) => {
                const name = event.target.value;
                if (!name || name === activeProfile?.name) return;
                void reviewProfile(name);
                onNavigate("mods");
              }}
            >
              {activeProfile ? null : <option value="">{`Not saved · ${profiles?.enabledMods.length.toLocaleString() ?? 0} enabled mods`}</option>}
              {(profiles?.profiles ?? []).map((profile) => (
                <option value={profile.name} key={profile.name} disabled={!profile.active && !profile.canActivate}>{profile.name}</option>
              ))}
            </select>
          ) : (
            <strong>{profilesLoading ? "Reading…" : activeProfile?.name ?? (profiles ? `${profiles.enabledMods.length} enabled mods` : "Unavailable")}</strong>
          )}
          <small>{profilesLoading
            ? "Checking the current mod list"
            : activeProfile
              ? `${activeProfile.modCount.toLocaleString()} mod${activeProfile.modCount === 1 ? "" : "s"} · saved ${formatSavedAt(activeProfile.savedAt)}`
              : profiles
                ? "Current list isn't saved as a profile"
                : "The current mod list couldn’t be read"}</small>
          <div className="home-fact__links">
            <button className="text-button" type="button" onClick={() => onNavigate("mods")} disabled={!isReady}>Profiles <ArrowIcon /></button>
          </div>
        </div>
        <div className="home-fact">
          <span>Prepared data</span>
          <strong>{cacheLoading ? "Reading…" : cache ? formatBytes(accelerationBytes) : "Unavailable"}</strong>
          <button className="text-button" type="button" onClick={onOpenStorage} disabled={!isReady}>Free space <ArrowIcon /></button>
        </div>
        <div className="home-fact home-fact--installation">
          <span>Installation</span>
          <strong>{isReady && snapshot?.selected ? shortPath(snapshot.selected.installRoot) : "Not selected"}</strong>
          <small>{isReady && snapshot ? `${friendlyPlatform(snapshot.platform)} · ${snapshot.selected?.kind.replace("-", " ")}${snapshot.candidates.length > 1 ? ` · ${snapshot.candidates.length} detected candidates` : " · found automatically"}` : "Choose the game folder to begin."}</small>
          <button type="button" className="text-button" onClick={onChooseInstall} aria-label="Change Starsector installation" disabled={operationBlocked}>Change <ArrowIcon /></button>
        </div>
      </section> : null}
      {/*
        * The engine reports where it looked as well as that it failed. Both belong here: the reason
        * anyone opens this is to work out whether their own install is somewhere unusual, and that
        * cannot be answered without the list of places that were already ruled out.
        */}
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
