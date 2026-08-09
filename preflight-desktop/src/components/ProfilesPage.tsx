import { LayersIcon, RefreshIcon, ShieldIcon, SparklesIcon } from "../icons";
import { NoticeBanner } from "./NoticeBanner";
import { shortPath } from "../uiFormat";
import type { useProfiles } from "../useProfiles";
import type { NoticeTone } from "../types";

type ProfilesState = ReturnType<typeof useProfiles>;

interface ProfilesPageProps {
  message: string;
  messageTone: NoticeTone;
  profilesState: ProfilesState;
  operationBlocked: boolean;
}

export function ProfilesPage({ message, messageTone, profilesState, operationBlocked }: ProfilesPageProps) {
  const {
    activationPlan,
    profileBusy,
    profileName,
    profiles,
    profilesLoading,
    applyProfile,
    refreshProfiles,
    reviewProfile,
    saveCurrentProfile,
    dismissActivationPlan,
    setProfileName,
  } = profilesState;

  return (
    <div className="profiles-page">
      <NoticeBanner message={message} tone={messageTone} />
      <div className="profiles-grid">
        <section className="card profile-list-card">
          <div className="card__heading">
            <div><p className="eyebrow">This installation</p><h2>Saved profiles</h2></div>
            <div className="card__heading-actions">
              <div className={`tiny-status ${profiles?.profiles.some((profile) => profile.active) ? "tiny-status--good" : ""}`}><span />{profilesLoading ? "Checking" : `${profiles?.profiles.length ?? 0} saved`}</div>
              <button className="icon-button icon-button--small" type="button" onClick={() => void refreshProfiles()} aria-label="Refresh saved profiles" disabled={profilesLoading}><RefreshIcon className={profilesLoading ? "spin" : ""} /></button>
            </div>
          </div>
          <div className="profile-list">
            {!profilesLoading && profiles?.profiles.length === 0 ? <div className="profile-empty"><strong>No profiles saved yet</strong><span>Give the current mod set a name to make your first one.</span></div> : null}
            {(profiles?.profiles ?? []).map((profile) => (
              <article className={`profile-card ${profile.active ? "profile-card--active" : ""}`} key={profile.name}>
                <div className="profile-card__mark"><LayersIcon /></div>
                <div className="profile-card__copy">
                  <div><strong>{profile.name}</strong>{profile.active ? <b>Active</b> : null}</div>
                  <span>{profile.modCount.toLocaleString()} mods · saved {new Date(profile.savedAt).toLocaleDateString()}</span>
                  {!profile.sameInstall ? <small>Saved for a different installation</small> : null}
                  {profile.missingMods.length > 0 ? <small>Missing: {profile.missingMods.join(", ")}</small> : null}
                </div>
                <button className="button button--quiet button--compact" type="button" onClick={() => void reviewProfile(profile.name)} disabled={profile.active || !profile.canActivate || profileBusy}>{profile.active ? "Current" : "Review switch"}</button>
              </article>
            ))}
          </div>
          {(profiles?.diagnostics.length ?? 0) > 0 ? <div className="profile-diagnostics">{profiles?.diagnostics.map((diagnostic) => <p key={diagnostic}>{diagnostic}</p>)}</div> : null}
        </section>

        <section className="card profile-save-card">
          <p className="eyebrow">Remember this setup</p>
          <h2>Save current profile</h2>
          <p>Names and load order only. Mod files stay where they are.</p>
          <label htmlFor="profile-name">Profile name</label>
          <input id="profile-name" value={profileName} onChange={(event) => setProfileName(event.target.value)} placeholder="e.g. Heavy campaign" maxLength={96} />
          <button className="button button--primary" type="button" disabled={!profileName.trim() || profileBusy} onClick={() => void saveCurrentProfile()}>Save current profile</button>
          <div className="profile-cache-note"><SparklesIcon /><span>Matching profiles reuse prepared caches automatically.</span></div>
        </section>
      </div>

      {activationPlan ? (
        <section className="card activation-review" aria-label="Profile switch review">
          <div className="activation-review__heading">
            <div><p className="eyebrow">Switch review</p><h2>Switch to {activationPlan.name}?</h2></div>
            <button className="text-button" type="button" onClick={dismissActivationPlan} disabled={profileBusy}>Cancel</button>
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
    </div>
  );
}
