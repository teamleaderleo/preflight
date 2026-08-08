import { useCallback, useEffect, useState } from "react";
import { activateProfile, getProfiles, saveProfile } from "./bridge";
import type { ProfileActivationPlan, ProfileList } from "./types";

export function useProfiles(
  game: string | undefined,
  visible: boolean,
  refreshInstallation: (game?: string) => Promise<void>,
  refreshCache: () => Promise<void>,
  announce: (message: string) => void,
) {
  const [profiles, setProfiles] = useState<ProfileList | null>(null);
  const [profilesLoading, setProfilesLoading] = useState(false);
  const [profileName, setProfileName] = useState("");
  const [profileBusy, setProfileBusy] = useState(false);
  const [activationPlan, setActivationPlan] = useState<ProfileActivationPlan | null>(null);

  const refreshProfiles = useCallback(async () => {
    if (!game) return;
    setProfilesLoading(true);
    try {
      setProfiles(await getProfiles(game));
    } catch (error) {
      announce(String(error));
    } finally {
      setProfilesLoading(false);
    }
  }, [announce, game]);

  useEffect(() => {
    if (visible) void refreshProfiles();
  }, [refreshProfiles, visible]);

  const saveCurrentProfile = async () => {
    const name = profileName.trim();
    if (!game || !name) return;
    setProfileBusy(true);
    try {
      await saveProfile(game, name);
      setProfileName("");
      announce(`Saved the exact current mod order as “${name}”.`);
      await refreshProfiles();
    } catch (error) {
      announce(String(error));
    } finally {
      setProfileBusy(false);
    }
  };

  const reviewProfile = async (name: string) => {
    if (!game) return;
    setProfileBusy(true);
    try {
      setActivationPlan(await activateProfile(game, name, false));
    } catch (error) {
      announce(String(error));
    } finally {
      setProfileBusy(false);
    }
  };

  const applyProfile = async () => {
    if (!game || !activationPlan) return;
    setProfileBusy(true);
    try {
      const result = await activateProfile(game, activationPlan.name, true);
      await Promise.all([refreshInstallation(game), refreshProfiles(), refreshCache()]);
      if (!result.canActivate) {
        setActivationPlan(result);
        announce(result.missingMods.length
          ? `The switch was refused because these mods are now missing: ${result.missingMods.join(", ")}.`
          : "The switch was refused because this profile belongs to a different installation.");
      } else {
        setActivationPlan(null);
        announce(result.applied
          ? `Switched to “${result.name}”. Its exact caches will be reused automatically when available.`
          : `“${result.name}” was already active; nothing changed.`);
      }
    } catch (error) {
      announce(String(error));
    } finally {
      setProfileBusy(false);
    }
  };

  const clearProfiles = () => setProfiles(null);

  return {
    activationPlan,
    profileBusy,
    profileName,
    profiles,
    profilesLoading,
    applyProfile,
    clearProfiles,
    refreshProfiles,
    reviewProfile,
    saveCurrentProfile,
    setActivationPlan,
    setProfileName,
  };
}
