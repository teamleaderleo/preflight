import { useEffect, useRef, useState } from "react";
import { ShieldIcon } from "../icons";
import { InfoTip } from "./InfoTip";
import { NoticeBanner } from "./NoticeBanner";
import { formatSavedAt, shortPath } from "../uiFormat";
import type { useProfiles } from "../useProfiles";
import type { useSetupCheck } from "../useSetupCheck";
import { filterProfileNames, useProfileSearch } from "../useProfileSearch";
import type { NoticeTone, SetupFindingSeverity } from "../types";

type ProfilesState = ReturnType<typeof useProfiles>;
type SetupCheckState = ReturnType<typeof useSetupCheck>;

type ReviewReturnTarget = {
  profileName: string;
  element: HTMLElement;
};

const FINDING_SEVERITY_ORDER: Record<SetupFindingSeverity, number> = {
  blocking: 0,
  warning: 1,
  unknown: 2,
  info: 3,
};

interface ProfilesPageProps {
  message: string;
  messageTone: NoticeTone;
  profilesState: ProfilesState;
  setupCheck: SetupCheckState;
  operationBlocked: boolean;
}

export function ProfilesPage({ message, messageTone, profilesState, setupCheck, operationBlocked }: ProfilesPageProps) {
  const profileSearch = useProfileSearch();
  const [setupFindingsExpanded, setSetupFindingsExpanded] = useState(false);
  const [readinessExpanded, setReadinessExpanded] = useState(false);
  const [copyFindingsState, setCopyFindingsState] = useState<"idle" | "copied" | "error">("idle");
  const activationReviewRef = useRef<HTMLElement>(null);
  const mutationReviewRef = useRef<HTMLElement>(null);
  const activationReturnRef = useRef<ReviewReturnTarget | null>(null);
  const mutationReturnRef = useRef<ReviewReturnTarget | null>(null);
  const {
    activationPlan,
    mutationPlan,
    profileBusy,
    profileName,
    profiles,
    profilesLoading,
    modReadiness,
    modReadinessLoading,
    renameDraft,
    renameTarget,
    duplicateDraft,
    duplicateTarget,
    applyProfile,
    applyProfileMutation,
    beginRename,
    cancelRename,
    beginDuplicate,
    cancelDuplicate,
    reviewDeleteProfile,
    reviewProfile,
    saveCurrentProfile,
    dismissActivationPlan,
    dismissMutationPlan,
    setProfileName,
    setRenameDraft,
    submitRename,
    setDuplicateDraft,
    submitDuplicate,
    refreshModReadiness,
  } = profilesState;
  const trimmedProfileName = profileName.trim();
  const profileNameAlreadySaved = Boolean(
    trimmedProfileName
      && profiles?.profiles.some((profile) => profile.name === trimmedProfileName),
  );
  const profileCreateHelp = profilesLoading
    ? "Checking saved profile names…"
    : profileNameAlreadySaved
      ? `“${trimmedProfileName}” is already a saved profile. Choose a new name to save this mod setup.`
      : "Creates a new saved profile from the current enabled-mod order.";
  const savedProfiles = profiles?.profiles ?? [];
  const visibleProfiles = filterProfileNames(savedProfiles, profileSearch.query);
  const profileCount = profilesLoading
    ? "Checking…"
    : profileSearch.query.trim()
      ? `${visibleProfiles.length} of ${savedProfiles.length}`
      : `${savedProfiles.length} saved`;
  const blockingModProblems = modReadiness?.counts.blocking ?? 0;
  const otherModProblems = (modReadiness?.counts.warning ?? 0) + (modReadiness?.counts.unknown ?? 0);
  const visibleModProblems = modReadiness?.findings.filter((finding) => finding.severity !== "info") ?? [];
  const shownModProblems = readinessExpanded ? visibleModProblems : visibleModProblems.slice(0, 8);
  const modProblemCount = blockingModProblems + otherModProblems;
  const modProblemLabel = blockingModProblems > 0
    ? `${blockingModProblems} mod problem${blockingModProblems === 1 ? "" : "s"}`
    : `${otherModProblems} mod warning${otherModProblems === 1 ? "" : "s"}`;
  const checkedFindings = [...(setupCheck.result?.findings ?? [])].sort((left, right) =>
    FINDING_SEVERITY_ORDER[left.severity] - FINDING_SEVERITY_ORDER[right.severity]);
  const shownCheckedFindings = setupFindingsExpanded ? checkedFindings : checkedFindings.slice(0, 20);
  const checkedProblemCount = setupCheck.result?.counts.blocking ?? 0;
  const checkedReviewCount = (setupCheck.result?.counts.warning ?? 0)
    + (setupCheck.result?.counts.unknown ?? 0);
  const setupCheckIncomplete = (setupCheck.result?.unavailableProviders.length ?? 0) > 0;
  const setupCheckLabel = checkedProblemCount > 0
    ? `${checkedProblemCount} problem${checkedProblemCount === 1 ? "" : "s"} found`
    : checkedReviewCount > 0
      ? `${checkedReviewCount} item${checkedReviewCount === 1 ? "" : "s"} to review`
      : setupCheckIncomplete
        ? "Check incomplete"
        : "No problems found";
  const retainedResult = Boolean(setupCheck.result)
    && (setupCheck.status === "running" || setupCheck.status === "failed");
  const setupCheckHeading = retainedResult
    ? `Previous check: ${setupCheckLabel.charAt(0).toLowerCase()}${setupCheckLabel.slice(1)}`
    : setupCheckLabel;

  useEffect(() => {
    setSetupFindingsExpanded(false);
    setCopyFindingsState("idle");
  }, [setupCheck.result]);

  useEffect(() => {
    setReadinessExpanded(false);
  }, [modReadiness]);

  useEffect(() => {
    if (activationPlan) {
      if (activationReturnRef.current?.profileName !== activationPlan.name) {
        activationReturnRef.current = null;
      }
      activationReviewRef.current?.focus();
    } else {
      activationReturnRef.current = null;
    }
  }, [activationPlan]);

  useEffect(() => {
    if (mutationPlan) {
      if (mutationReturnRef.current?.profileName !== mutationPlan.name) {
        mutationReturnRef.current = null;
      }
      mutationReviewRef.current?.focus();
    } else {
      mutationReturnRef.current = null;
    }
  }, [mutationPlan]);

  const rememberMutationReturn = (control: HTMLElement, profileName: string) => {
    const details = control.closest("details");
    const summary = details?.querySelector<HTMLElement>("summary");
    mutationReturnRef.current = summary ? { profileName, element: summary } : null;
    details?.removeAttribute("open");
  };

  const cancelActivationReview = () => {
    const returnTarget = activationReturnRef.current;
    activationReturnRef.current = null;
    dismissActivationPlan();
    if (returnTarget?.element.isConnected) returnTarget.element.focus();
  };

  const cancelMutationReview = () => {
    const returnTarget = mutationReturnRef.current;
    mutationReturnRef.current = null;
    dismissMutationPlan();
    if (returnTarget?.element.isConnected) returnTarget.element.focus();
  };

  const copySetupFindings = async () => {
    if (!setupCheck.result) return;
    const lines = [
      `Preflight mod check: ${setupCheckLabel}`,
      ...checkedFindings.map((finding) => {
        const action = finding.actions[0] ? ` — ${finding.actions[0]}` : "";
        return `[${finding.severity.toUpperCase()}] ${finding.summary}${action}`;
      }),
      ...setupCheck.result.unavailableProviders.map((provider) => `[UNKNOWN] ${provider} check unavailable`),
    ];
    try {
      await navigator.clipboard.writeText(lines.join("\n"));
      setCopyFindingsState("copied");
    } catch {
      setCopyFindingsState("error");
    }
  };

  return (
    <div className="profiles-page">
      <NoticeBanner message={message} tone={messageTone} />
      <section className="card setup-check-card" aria-label="Mod check">
        <div className="setup-check-card__heading">
          <div><h2>Mod check</h2><p>Checks dependencies and common broken references.</p></div>
          <button className="button button--primary" type="button" onClick={() => void setupCheck.run()} disabled={operationBlocked}>
            {setupCheck.checking ? "Checking…" : setupCheck.result ? "Check again" : "Check setup"}
          </button>
        </div>
        {setupCheck.result ? (
          <div className={`setup-check-result ${checkedProblemCount > 0 ? "setup-check-result--blocking" : checkedReviewCount > 0 || setupCheckIncomplete ? "setup-check-result--warning" : "setup-check-result--ready"}`} role="status">
            <strong>{setupCheckHeading}</strong>
            {setupCheck.status === "running" ? <small>Checking your current setup… Previous results remain available below.</small> : null}
            {setupCheck.status === "failed" ? (
              <div>
                <small>The latest check couldn’t finish.</small>{" "}
                <button className="text-button" type="button" onClick={() => void setupCheck.run()} disabled={operationBlocked}>Try again</button>
              </div>
            ) : null}
            {shownCheckedFindings.length > 0 ? (
              <ul>{shownCheckedFindings.map((finding) => (
                <li key={`${finding.code}:${finding.summary}`}>
                  <span>{finding.summary}</span>
                  {finding.actions[0] ? <small>{finding.actions[0]}</small> : null}
                </li>
              ))}</ul>
            ) : null}
            {checkedFindings.length > 20 ? (
              <button className="button button--quiet button--compact" type="button" onClick={() => setSetupFindingsExpanded((current) => !current)}>
                {setupFindingsExpanded ? "Show fewer findings" : `Show all ${checkedFindings.length} findings`}
              </button>
            ) : null}
            {checkedFindings.length > 0 ? (
              <button className="button button--quiet button--compact" type="button" onClick={() => void copySetupFindings()}>
                {copyFindingsState === "copied" ? "Findings copied" : copyFindingsState === "error" ? "Try copying again" : "Copy findings"}
              </button>
            ) : null}
            {copyFindingsState === "error" ? <small role="alert">Clipboard access failed. The findings remain available above.</small> : null}
            {setupCheck.result.unavailableProviders.length > 0 ? <small>Some checks couldn't finish. Try again after closing other mod tools.</small> : null}
          </div>
        ) : setupCheck.status === "running" ? (
          <div className="setup-check-result" role="status"><strong>Checking your current setup…</strong></div>
        ) : setupCheck.status === "failed" ? (
          <div className="setup-check-result setup-check-result--warning" role="status">
            <strong>Check couldn’t finish</strong>
            <small>{setupCheck.error ?? "Try the check again."}</small>
            <button className="button button--quiet button--compact" type="button" onClick={() => void setupCheck.run()} disabled={operationBlocked}>Try again</button>
          </div>
        ) : modProblemCount > 0 ? (
          <div className={`mod-readiness ${blockingModProblems > 0 ? "mod-readiness--blocking" : "mod-readiness--warning"}`}>
            <details>
              <summary>{modProblemLabel}<span>Review</span></summary>
              <ul>
                {shownModProblems.map((finding) => (
                  <li key={`${finding.code}:${finding.summary}`}><strong>{finding.summary}</strong></li>
                ))}
              </ul>
              {visibleModProblems.length > 8 ? (
                <button className="button button--quiet button--compact" type="button" onClick={() => setReadinessExpanded((current) => !current)}>
                  {readinessExpanded ? "Show fewer findings" : `Show all ${visibleModProblems.length} findings`}
                </button>
              ) : null}
              <button className="button button--quiet button--compact" type="button" onClick={() => void refreshModReadiness()} disabled={modReadinessLoading}>
                {modReadinessLoading ? "Checking…" : "Refresh"}
              </button>
            </details>
          </div>
        ) : null}
      </section>
      <div className="profiles-grid">
        <section className="card profile-list-card">
          <div className="card__heading">
            <div className="heading-with-info"><h2>Saved profiles</h2><InfoTip label="About mod profiles">A profile remembers enabled mods and their load order. Preflight notices changes made in another launcher when you return. Switching is previewed before it changes the mod list. Mod files stay where they are.</InfoTip></div>
            <span className="field-note">{profileCount}</span>
          </div>
          {savedProfiles.length > 0 || profileSearch.query ? (
            <input
              aria-label="Search saved profiles"
              value={profileSearch.query}
              onChange={(event) => profileSearch.setQuery(event.target.value)}
              placeholder="Search profiles"
              maxLength={100}
              autoComplete="off"
            />
          ) : null}
          <div className="profile-list">
            {!profilesLoading && savedProfiles.length === 0 ? <div className="profile-empty"><span>Save your current mod list, then switch profiles without toggling every mod by hand.</span></div> : null}
            {!profilesLoading && savedProfiles.length > 0 && visibleProfiles.length === 0 ? <div className="profile-empty"><span>No saved profiles match “{profileSearch.query.trim()}”.</span></div> : null}
            {visibleProfiles.map((profile) => (
              <article className={`profile-card ${profile.active ? "profile-card--active" : ""}`} key={profile.name}>
                <div className="profile-card__copy">
                  <div><strong>{profile.name}</strong>{profile.active ? <b>Active</b> : null}</div>
                  <span>{profile.modCount.toLocaleString()} mod{profile.modCount === 1 ? "" : "s"} · saved {formatSavedAt(profile.savedAt)}</span>
                  {!profile.sameInstall ? <small>Saved for a different installation</small> : null}
                  {profile.missingMods.length > 0 ? <small>Missing: {profile.missingMods.join(", ")}</small> : null}
                </div>
                <div className="profile-card__actions">
                  {!profile.active && profile.canActivate ? <button className="button button--quiet button--compact" type="button" onClick={(event) => {
                    activationReturnRef.current = { profileName: profile.name, element: event.currentTarget };
                    void reviewProfile(profile.name);
                  }} disabled={profileBusy || operationBlocked}>Switch…</button> : null}
                  <details className="profile-menu">
                    <summary aria-label={`Manage ${profile.name}`}>Manage</summary>
                    <div>
                      <button type="button" onClick={(event) => {
                        rememberMutationReturn(event.currentTarget, profile.name);
                        beginDuplicate(profile.name);
                      }} disabled={profileBusy || operationBlocked}>Duplicate…</button>
                      <button type="button" onClick={(event) => {
                        rememberMutationReturn(event.currentTarget, profile.name);
                        beginRename(profile.name);
                      }} disabled={profileBusy || operationBlocked}>Rename</button>
                      <button type="button" onClick={(event) => {
                        rememberMutationReturn(event.currentTarget, profile.name);
                        void reviewDeleteProfile(profile.name);
                      }} disabled={profileBusy || operationBlocked}>Delete</button>
                    </div>
                  </details>
                </div>
              </article>
            ))}
          </div>
          {renameTarget ? (
            <div className="profile-rename-editor" role="group" aria-label={`Rename ${renameTarget}`}>
              <label htmlFor="rename-profile">Rename {renameTarget}</label>
              <div>
                <input id="rename-profile" value={renameDraft} onChange={(event) => setRenameDraft(event.target.value)} maxLength={100} autoFocus />
                <button className="button button--quiet button--compact" type="button" onClick={cancelRename}>Cancel</button>
                <button className="button button--primary button--compact" type="button" onClick={submitRename} disabled={!renameDraft.trim() || renameDraft.trim() === renameTarget || profileBusy}>Review rename</button>
              </div>
            </div>
          ) : null}
          {duplicateTarget ? (
            <div className="profile-rename-editor" role="group" aria-label={`Duplicate ${duplicateTarget}`}>
              <label htmlFor="duplicate-profile">Duplicate {duplicateTarget} as</label>
              <div>
                <input id="duplicate-profile" value={duplicateDraft} onChange={(event) => setDuplicateDraft(event.target.value)} maxLength={100} autoFocus />
                <button className="button button--quiet button--compact" type="button" onClick={cancelDuplicate}>Cancel</button>
                <button className="button button--primary button--compact" type="button" onClick={submitDuplicate} disabled={!duplicateDraft.trim() || duplicateDraft.trim() === duplicateTarget || profileBusy}>Review duplicate</button>
              </div>
            </div>
          ) : null}
          {(profiles?.diagnostics.length ?? 0) > 0 ? <div className="profile-diagnostics">{profiles?.diagnostics.map((diagnostic) => <p key={diagnostic}>{diagnostic}</p>)}</div> : null}
        </section>

        <section className="card profile-save-card">
          <div className="heading-with-info"><h2>Save as new profile</h2><InfoTip label="What saving a profile does">Creates a new saved profile containing the enabled-mod list and load order. Existing profiles and mod files stay unchanged.</InfoTip></div>
          <label htmlFor="profile-name">Profile name</label>
          <input id="profile-name" value={profileName} onChange={(event) => setProfileName(event.target.value)} placeholder="e.g. Main campaign" maxLength={96} aria-describedby="profile-create-help" />
          <small id="profile-create-help" className="field-note">{profileCreateHelp}</small>
          <button
            className="button button--primary"
            type="button"
            disabled={!trimmedProfileName || profileBusy || profilesLoading || profileNameAlreadySaved || operationBlocked}
            onClick={() => void saveCurrentProfile()}
          >
            Create profile
          </button>
        </section>
      </div>

      {activationPlan ? (
        <section
          ref={activationReviewRef}
          className="card activation-review"
          aria-label="Profile switch review"
          tabIndex={-1}
        >
          <div className="activation-review__heading">
            <div><p className="eyebrow">Switch review</p><h2>Switch to {activationPlan.name}?</h2></div>
            <button className="text-button" type="button" onClick={cancelActivationReview} disabled={profileBusy}>Cancel</button>
          </div>
          {!activationPlan.sameInstall ? <p className="activation-warning">This profile belongs to {shortPath(activationPlan.savedInstallRoot)} and cannot be applied here.</p> : null}
          {activationPlan.missingMods.length > 0 ? <p className="activation-warning">Install these mods first: {activationPlan.missingMods.join(", ")}</p> : null}
          <div className="activation-columns">
            <div><strong>Enable ({activationPlan.enable.length})</strong>{activationPlan.enable.length ? <ul>{activationPlan.enable.map((mod) => <li key={mod}>{mod}</li>)}</ul> : <span>Nothing</span>}</div>
            <div><strong>Disable ({activationPlan.disable.length})</strong>{activationPlan.disable.length ? <ul>{activationPlan.disable.map((mod) => <li key={mod}>{mod}</li>)}</ul> : <span>Nothing</span>}</div>
          </div>
          <div className="activation-review__footer">
            <span><ShieldIcon /> Preflight checks the current mod list again, saves a backup, then applies this switch.</span>
            <button className="button button--primary" type="button" onClick={() => void applyProfile()} disabled={!activationPlan.canActivate || activationPlan.active || profileBusy || operationBlocked}>{profileBusy ? "Switching…" : "Apply switch"}</button>
          </div>
        </section>
      ) : null}

      {mutationPlan ? (
        <section
          ref={mutationReviewRef}
          className="card activation-review profile-mutation-review"
          aria-label="Profile change review"
          tabIndex={-1}
        >
          <div className="activation-review__heading">
            <div>
              <p className="eyebrow">Profile review</p>
              <h2>{mutationPlan.operation === "rename"
                ? `Rename ${mutationPlan.name} to ${mutationPlan.targetName}?`
                : mutationPlan.operation === "duplicate"
                  ? `Duplicate ${mutationPlan.name} as ${mutationPlan.targetName}?`
                  : `Delete ${mutationPlan.name}?`}</h2>
            </div>
            <button className="text-button" type="button" onClick={cancelMutationReview} disabled={profileBusy}>Cancel</button>
          </div>
          <p>{mutationPlan.operation === "rename"
            ? "This renames the profile. Its mod list and prepared data stay unchanged."
            : mutationPlan.operation === "duplicate"
              ? "This creates an independent profile copy with the same mod list. The original profile and prepared data stay unchanged."
              : "This deletes the profile. The current mod list and prepared data stay unchanged."}</p>
          {mutationPlan.operation === "delete" && mutationPlan.active ? <p className="activation-warning">This is the active profile. Deleting its saved name will not disable any mods.</p> : null}
          <div className="activation-review__footer">
            <span><ShieldIcon /> Preflight checks the saved profile again before making this change.</span>
            <button className={`button ${mutationPlan.operation === "delete" ? "button--danger" : "button--primary"}`} type="button" onClick={() => void applyProfileMutation()} disabled={profileBusy || operationBlocked}>
              {profileBusy ? "Applying…" : mutationPlan.operation === "rename" ? "Rename profile" : mutationPlan.operation === "duplicate" ? "Duplicate profile" : "Delete profile"}
            </button>
          </div>
        </section>
      ) : null}
    </div>
  );
}
