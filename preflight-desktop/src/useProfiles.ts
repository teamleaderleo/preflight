import { useCallback, useEffect, useRef, useState } from "react";
import { activateProfile, getProfiles, saveProfile } from "./bridge";
import type { Announce, ProfileActivationPlan, ProfileList } from "./types";

export function useProfiles(
  game: string | undefined,
  visible: boolean,
  refreshInstallation: (game?: string) => Promise<boolean>,
  refreshCache: () => Promise<void>,
  announce: Announce,
) {
  const [profiles, setProfiles] = useState<ProfileList | null>(null);
  const [profilesLoading, setProfilesLoading] = useState(false);
  const [profileName, setProfileName] = useState("");
  const [profileBusy, setProfileBusy] = useState(false);
  const [activationPlan, setActivationPlan] = useState<ProfileActivationPlan | null>(null);
  const [activationPlanGame, setActivationPlanGame] = useState<string | null>(null);
  const profilesRequest = useRef(0);
  const actionRequest = useRef(0);
  const busyRef = useRef(false);
  const profileNameRevision = useRef(0);
  const currentGame = useRef(game);
  currentGame.current = game;

  const refreshProfiles = useCallback(async () => {
    const request = ++profilesRequest.current;
    if (!game) {
      setProfiles(null);
      setProfilesLoading(false);
      return;
    }
    setProfilesLoading(true);
    try {
      const next = await getProfiles(game);
      if (request === profilesRequest.current && currentGame.current === game) setProfiles(next);
    } catch (error) {
      if (request === profilesRequest.current && currentGame.current === game) announce(String(error), "error");
    } finally {
      if (request === profilesRequest.current) setProfilesLoading(false);
    }
  }, [announce, game]);

  useEffect(() => {
    profilesRequest.current += 1;
    actionRequest.current += 1;
    busyRef.current = false;
    profileNameRevision.current += 1;
    setProfiles(null);
    setProfilesLoading(false);
    setProfileBusy(false);
    setProfileName("");
    setActivationPlan(null);
    setActivationPlanGame(null);
  }, [game]);

  useEffect(() => {
    if (visible) {
      void refreshProfiles();
    } else if (!game) {
      profilesRequest.current += 1;
      setProfiles(null);
      setProfilesLoading(false);
    }
  }, [refreshProfiles, visible]);

  const saveCurrentProfile = async () => {
    const name = profileName.trim();
    const expectedGame = game;
    if (!expectedGame || !name || busyRef.current) return;
    const request = ++actionRequest.current;
    const submittedRevision = profileNameRevision.current;
    busyRef.current = true;
    setProfileBusy(true);
    try {
      await saveProfile(expectedGame, name);
      if (request !== actionRequest.current || currentGame.current !== expectedGame) return;
      if (profileNameRevision.current === submittedRevision) setProfileName("");
      announce(`Saved the exact current mod order as “${name}”.`, "success");
      await refreshProfiles();
    } catch (error) {
      if (request === actionRequest.current && currentGame.current === expectedGame) announce(String(error), "error");
    } finally {
      if (request === actionRequest.current) {
        busyRef.current = false;
        setProfileBusy(false);
      }
    }
  };

  const reviewProfile = async (name: string) => {
    const expectedGame = game;
    if (!expectedGame || busyRef.current) return;
    const request = ++actionRequest.current;
    busyRef.current = true;
    setProfileBusy(true);
    try {
      const plan = await activateProfile(expectedGame, name, false);
      if (request !== actionRequest.current || currentGame.current !== expectedGame) return;
      setActivationPlan(plan);
      setActivationPlanGame(expectedGame);
    } catch (error) {
      if (request === actionRequest.current && currentGame.current === expectedGame) announce(String(error), "error");
    } finally {
      if (request === actionRequest.current) {
        busyRef.current = false;
        setProfileBusy(false);
      }
    }
  };

  const applyProfile = async () => {
    const expectedGame = game;
    const reviewedPlan = activationPlanGame === game ? activationPlan : null;
    if (!expectedGame || !reviewedPlan || busyRef.current) return;
    const request = ++actionRequest.current;
    busyRef.current = true;
    setProfileBusy(true);
    try {
      const result = await activateProfile(expectedGame, reviewedPlan.name, true);
      if (request !== actionRequest.current || currentGame.current !== expectedGame) return;
      await Promise.all([refreshInstallation(expectedGame), refreshProfiles(), refreshCache()]);
      if (request !== actionRequest.current || currentGame.current !== expectedGame) return;
      if (!result.canActivate) {
        setActivationPlan(result);
        setActivationPlanGame(expectedGame);
        announce(result.missingMods.length
          ? `The switch was refused because these mods are now missing: ${result.missingMods.join(", ")}.`
          : "The switch was refused because this profile belongs to a different installation.", "warning");
      } else {
        setActivationPlan(null);
        setActivationPlanGame(null);
        announce(result.applied
          ? `Switched to “${result.name}”. Its exact caches will be reused automatically when available.`
          : `“${result.name}” was already active; nothing changed.`);
      }
    } catch (error) {
      if (request === actionRequest.current && currentGame.current === expectedGame) announce(String(error), "error");
    } finally {
      if (request === actionRequest.current) {
        busyRef.current = false;
        setProfileBusy(false);
      }
    }
  };

  const clearProfiles = () => {
    profilesRequest.current += 1;
    actionRequest.current += 1;
    busyRef.current = false;
    setProfiles(null);
    setProfilesLoading(false);
    setProfileBusy(false);
    setActivationPlan(null);
    setActivationPlanGame(null);
  };
  const changeProfileName = (name: string) => {
    profileNameRevision.current += 1;
    setProfileName(name);
  };
  const dismissActivationPlan = () => {
    actionRequest.current += 1;
    busyRef.current = false;
    setProfileBusy(false);
    setActivationPlan(null);
    setActivationPlanGame(null);
  };
  const currentProfiles = profiles?.installRoot === game ? profiles : null;
  const currentActivationPlan = activationPlanGame === game ? activationPlan : null;

  return {
    activationPlan: currentActivationPlan,
    profileBusy,
    profileName,
    profiles: currentProfiles,
    profilesLoading,
    applyProfile,
    clearProfiles,
    refreshProfiles,
    reviewProfile,
    saveCurrentProfile,
    dismissActivationPlan,
    setProfileName: changeProfileName,
  };
}
