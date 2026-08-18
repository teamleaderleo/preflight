import { useCallback, useEffect, useRef, useState } from "react";
import {
  deleteProfile,
  duplicateProfile,
  getProfiles,
  renameProfile,
  saveProfile,
} from "./bridge";
import {
  activateReviewedProfile,
  type ReviewedProfileActivationPlan,
} from "./profileActivationBridge";
import type {
  Announce,
  ProfileList,
  ProfileMutationPlan,
} from "./types";
import { errorMessage } from "./uiFormat";

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
  const [activationPlan, setActivationPlan] = useState<ReviewedProfileActivationPlan | null>(null);
  const [activationPlanGame, setActivationPlanGame] = useState<string | null>(null);
  const [mutationPlan, setMutationPlan] = useState<ProfileMutationPlan | null>(null);
  const [mutationPlanGame, setMutationPlanGame] = useState<string | null>(null);
  // Which profile the rename editor is open for. This lives here rather than in the profiles page
  // so the home card can open it on the way over.
  const [renameTarget, setRenameTarget] = useState<string | null>(null);
  const [renameDraft, setRenameDraft] = useState("");
  const [duplicateTarget, setDuplicateTarget] = useState<string | null>(null);
  const [duplicateDraft, setDuplicateDraft] = useState("");
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
      if (request === profilesRequest.current && currentGame.current === game) announce(errorMessage(error), "error");
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
    setMutationPlan(null);
    setMutationPlanGame(null);
    setRenameTarget(null);
    setRenameDraft("");
    setDuplicateTarget(null);
    setDuplicateDraft("");
  }, [game]);

  useEffect(() => {
    // Page navigation is not a filesystem change. Keep the last list for this exact installation
    // instead of starting another JVM every time Home or Mods becomes visible again.
    if (visible && profiles?.installRoot !== game) {
      void refreshProfiles();
    } else if (!game) {
      profilesRequest.current += 1;
      setProfiles(null);
      setProfilesLoading(false);
    }
  }, [game, profiles?.installRoot, refreshProfiles, visible]);

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
      if (request === actionRequest.current && currentGame.current === expectedGame) announce(errorMessage(error), "error");
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
      const plan = await activateReviewedProfile(expectedGame, name, false);
      if (request !== actionRequest.current || currentGame.current !== expectedGame) return;
      setMutationPlan(null);
      setMutationPlanGame(null);
      setActivationPlan(plan);
      setActivationPlanGame(expectedGame);
    } catch (error) {
      if (request === actionRequest.current && currentGame.current === expectedGame) announce(errorMessage(error), "error");
    } finally {
      if (request === actionRequest.current) {
        busyRef.current = false;
        setProfileBusy(false);
      }
    }
  };

  const reviewProfileMutation = async (
    operation: "rename" | "duplicate" | "delete",
    name: string,
    targetName?: string,
  ) => {
    const expectedGame = game;
    if (!expectedGame || busyRef.current) return;
    const request = ++actionRequest.current;
    busyRef.current = true;
    setProfileBusy(true);
    try {
      const plan = operation === "rename"
        ? await renameProfile(expectedGame, name, targetName ?? "", null, false)
        : operation === "duplicate"
          ? await duplicateProfile(expectedGame, name, targetName ?? "", null, false)
          : await deleteProfile(expectedGame, name, null, false);
      if (request !== actionRequest.current || currentGame.current !== expectedGame) return;
      setActivationPlan(null);
      setActivationPlanGame(null);
      setMutationPlan(plan);
      setMutationPlanGame(expectedGame);
    } catch (error) {
      if (request === actionRequest.current && currentGame.current === expectedGame) announce(errorMessage(error), "error");
    } finally {
      if (request === actionRequest.current) {
        busyRef.current = false;
        setProfileBusy(false);
      }
    }
  };

  const applyProfileMutation = async () => {
    const expectedGame = game;
    const reviewedPlan = mutationPlanGame === game ? mutationPlan : null;
    if (!expectedGame || !reviewedPlan || busyRef.current) return;
    const request = ++actionRequest.current;
    busyRef.current = true;
    setProfileBusy(true);
    try {
      const result = reviewedPlan.operation === "rename"
        ? await renameProfile(
          expectedGame,
          reviewedPlan.name,
          reviewedPlan.targetName ?? "",
          reviewedPlan.profileFingerprint,
          true,
        )
        : reviewedPlan.operation === "duplicate"
          ? await duplicateProfile(
            expectedGame,
            reviewedPlan.name,
            reviewedPlan.targetName ?? "",
            reviewedPlan.profileFingerprint,
            true,
          )
          : await deleteProfile(
            expectedGame,
            reviewedPlan.name,
            reviewedPlan.profileFingerprint,
            true,
          );
      if (request !== actionRequest.current || currentGame.current !== expectedGame) return;
      await refreshProfiles();
      if (request !== actionRequest.current || currentGame.current !== expectedGame) return;
      setMutationPlan(null);
      setMutationPlanGame(null);
      announce(result.operation === "rename"
        ? `Renamed “${result.name}” to “${result.targetName}”.`
        : result.operation === "duplicate"
          ? `Duplicated “${result.name}” as “${result.targetName}”.`
          : `Deleted “${result.name}”. Its prepared data was kept.`, "success");
    } catch (error) {
      if (request === actionRequest.current && currentGame.current === expectedGame) {
        setMutationPlan(null);
        setMutationPlanGame(null);
        announce(errorMessage(error), "error");
        await refreshProfiles();
      }
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
      const result = await activateReviewedProfile(expectedGame, reviewedPlan.name, true);
      if (request !== actionRequest.current || currentGame.current !== expectedGame) return;
      if (result.applied && !result.reviewChanged && result.canActivate) {
        // The enabled-mods file changed before the refreshes below. Update the cached list by the
        // target fingerprint now, including equivalent duplicate profiles, so a failed refresh can
        // never leave Home naming the profile that was active before this successful switch.
        setProfiles((current) => {
          if (!current || current.installRoot !== expectedGame) return current;
          const target = current.profiles.find((profile) => profile.name === result.name);
          return {
            ...current,
            profiles: current.profiles.map((profile) => ({
              ...profile,
              active: target ? profile.profileFingerprint === target.profileFingerprint : false,
            })),
          };
        });
      }
      await Promise.all([refreshInstallation(expectedGame), refreshProfiles(), refreshCache()]);
      if (request !== actionRequest.current || currentGame.current !== expectedGame) return;
      if (result.reviewChanged) {
        setActivationPlan(result);
        setActivationPlanGame(expectedGame);
        announce(
          result.sourceChanged
            ? "The current mod selection changed since you reviewed this switch. Review the updated changes, then apply again."
            : result.profileChanged
              ? "The saved profile changed since you reviewed this switch. Review the updated changes, then apply again."
              : "This profile switch needs a fresh review. Review the updated changes, then apply again.",
          "warning",
        );
      } else if (!result.canActivate) {
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
      if (request === actionRequest.current && currentGame.current === expectedGame) announce(errorMessage(error), "error");
    } finally {
      if (request === actionRequest.current) {
        busyRef.current = false;
        setProfileBusy(false);
      }
    }
  };

  const beginRename = (name: string) => {
    setDuplicateTarget(null);
    setDuplicateDraft("");
    setRenameTarget(name);
    setRenameDraft(name);
  };
  const cancelRename = () => {
    setRenameTarget(null);
    setRenameDraft("");
  };
  const submitRename = () => {
    const target = renameTarget;
    const wanted = renameDraft.trim();
    if (!target || !wanted || wanted === target) return;
    void reviewProfileMutation("rename", target, wanted);
    cancelRename();
  };

  const beginDuplicate = (name: string) => {
    setRenameTarget(null);
    setRenameDraft("");
    setDuplicateTarget(name);
    setDuplicateDraft(`${name} (Copy)`);
  };
  const cancelDuplicate = () => {
    setDuplicateTarget(null);
    setDuplicateDraft("");
  };
  const submitDuplicate = () => {
    const target = duplicateTarget;
    const wanted = duplicateDraft.trim();
    if (!target || !wanted || wanted === target) return;
    void reviewProfileMutation("duplicate", target, wanted);
    cancelDuplicate();
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
    setMutationPlan(null);
    setMutationPlanGame(null);
    setRenameTarget(null);
    setRenameDraft("");
    setDuplicateTarget(null);
    setDuplicateDraft("");
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
  const dismissMutationPlan = () => {
    actionRequest.current += 1;
    busyRef.current = false;
    setProfileBusy(false);
    setMutationPlan(null);
    setMutationPlanGame(null);
  };
  const currentProfiles = profiles?.installRoot === game ? profiles : null;
  const currentActivationPlan = activationPlanGame === game ? activationPlan : null;
  const currentMutationPlan = mutationPlanGame === game ? mutationPlan : null;

  return {
    activationPlan: currentActivationPlan,
    mutationPlan: currentMutationPlan,
    profileBusy,
    profileName,
    profiles: currentProfiles,
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
    clearProfiles,
    refreshProfiles,
    reviewProfile,
    setRenameDraft,
    submitRename,
    setDuplicateDraft,
    submitDuplicate,
    reviewDeleteProfile: (name: string) => reviewProfileMutation("delete", name),
    reviewRenameProfile: (name: string, targetName: string) =>
      reviewProfileMutation("rename", name, targetName),
    reviewDuplicateProfile: (name: string, targetName: string) =>
      reviewProfileMutation("duplicate", name, targetName),
    saveCurrentProfile,
    dismissActivationPlan,
    dismissMutationPlan,
    setProfileName: changeProfileName,
  };
}
