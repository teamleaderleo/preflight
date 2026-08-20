import { useEffect, useRef } from "react";
import { ShieldIcon } from "../icons";
import { InfoTip } from "./InfoTip";
import { NoticeBanner } from "./NoticeBanner";
import { formatSavedAt, shortPath } from "../uiFormat";
import type { useProfiles } from "../useProfiles";
import { filterProfileNames, useProfileSearch } from "../useProfileSearch";
import type { NoticeTone } from "../types";

type ProfilesState = ReturnType<typeof useProfiles>;

type ReviewReturnTarget = {
  profileName: string;
  element: HTMLElement;
};

interface ProfilesPageProps {
  message: string;
  messageTone: NoticeTone;
  profilesState: ProfilesState;
  operationBlocked: boolean;
}

export function ProfilesPage({ message, messageTone, profilesState, operationBlocked }: ProfilesPageProps) {
  const profileSearch = useProfileSearch();
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

  return (
    <div className="profiles-page">
      <NoticeBanner message={message} tone={messageTone} />
      <div className="profiles-grid">
        <section className="card profile-list-card">
          <div className="card__heading">
            <div className="heading-with-info"><h2>Saved profiles</h2><InfoTip label="About mod profiles">A profile remembers enabled mods and their load order. Switching is previewed before Preflight changes the mod list, and matching prepared data is reused automatically.</InfoTip></div>
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
            <span><ShieldIcon /> Preflight rechecks the file, writes a backup, then replaces it safely.</span>
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
            <span><ShieldIcon /> Preflight rechecks the exact reviewed profile before changing it.</span>
            <button className={`button ${mutationPlan.operation === "delete" ? "button--danger" : "button--primary"}`} type="button" onClick={() => void applyProfileMutation()} disabled={profileBusy || operationBlocked}>
              {profileBusy ? "Applying…" : mutationPlan.operation === "rename" ? "Rename profile" : mutationPlan.operation === "duplicate" ? "Duplicate profile" : "Delete profile"}
            </button>
          </div>
        </section>
      ) : null}
    </div>
  );
}
