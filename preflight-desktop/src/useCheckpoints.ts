import { useCallback, useEffect, useRef, useState } from "react";
import {
  compareCheckpoint as apiCompareCheckpoint,
  createCheckpoint as apiCreateCheckpoint,
  deleteCheckpoint as apiDeleteCheckpoint,
  getCheckpoints,
  renameCheckpoint as apiRenameCheckpoint,
} from "./bridge";
import {
  prepareReviewedCheckpointRestore,
  restoreReviewedCheckpoint,
} from "./checkpointRestoreBridge";
import type {
  Announce,
  CheckpointDiff,
  CheckpointList,
  CheckpointRestorePlan,
} from "./types";
import { errorMessage } from "./uiFormat";

export function useCheckpoints(
  game: string | undefined,
  visible: boolean,
  refreshInstallation: (game?: string) => Promise<boolean>,
  refreshCache: () => Promise<void>,
  announce: Announce,
) {
  const [checkpoints, setCheckpoints] = useState<CheckpointList | null>(null);
  const [checkpointsLoading, setCheckpointsLoading] = useState(false);
  const [checkpointBusy, setCheckpointBusy] = useState(false);
  const checkpointsRequest = useRef(0);
  const actionRequest = useRef(0);
  const busyRef = useRef(false);
  const currentGame = useRef(game);
  currentGame.current = game;

  const refreshCheckpoints = useCallback(async () => {
    const request = ++checkpointsRequest.current;
    if (!game) {
      setCheckpoints(null);
      setCheckpointsLoading(false);
      return;
    }
    setCheckpointsLoading(true);
    try {
      const next = await getCheckpoints(game);
      if (request === checkpointsRequest.current && currentGame.current === game) {
        setCheckpoints(next);
      }
    } catch (error) {
      if (request === checkpointsRequest.current && currentGame.current === game) {
        announce(errorMessage(error), "error");
      }
    } finally {
      if (request === checkpointsRequest.current) {
        setCheckpointsLoading(false);
      }
    }
  }, [announce, game]);

  useEffect(() => {
    checkpointsRequest.current += 1;
    actionRequest.current += 1;
    busyRef.current = false;
    setCheckpoints(null);
    setCheckpointsLoading(false);
    setCheckpointBusy(false);
  }, [game]);

  useEffect(() => {
    if (visible && checkpoints?.installRoot !== game) {
      void refreshCheckpoints();
    } else if (!game) {
      checkpointsRequest.current += 1;
      setCheckpoints(null);
      setCheckpointsLoading(false);
    }
  }, [checkpoints?.installRoot, game, refreshCheckpoints, visible]);

  const pinCheckpoint = async (
    name: string,
    description?: string | null,
    fromLastRun: boolean = false,
  ) => {
    const trimmed = name.trim();
    const expectedGame = game;
    if (!expectedGame || !trimmed || busyRef.current) return;
    const request = ++actionRequest.current;
    busyRef.current = true;
    setCheckpointBusy(true);
    try {
      await apiCreateCheckpoint(expectedGame, trimmed, description?.trim() || null, fromLastRun);
      if (request !== actionRequest.current || currentGame.current !== expectedGame) return;
      announce(`Pinned checkpoint “${trimmed}” successfully.`, "success");
      await refreshCheckpoints();
    } catch (error) {
      if (request === actionRequest.current && currentGame.current !== expectedGame) {
        announce(errorMessage(error), "error");
        throw error;
      }
    } finally {
      if (request === actionRequest.current) {
        busyRef.current = false;
        setCheckpointBusy(false);
      }
    }
  };

  const compareCheckpoint = async (
    name: string,
    targetName?: string | null,
  ): Promise<CheckpointDiff> => {
    const expectedGame = game;
    if (!expectedGame) throw new Error("No active Starsector installation selected.");
    return apiCompareCheckpoint(expectedGame, name, targetName);
  };

  const restoreCheckpoint = async (
    name: string,
    restoreSettings: boolean,
  ): Promise<CheckpointRestorePlan> => {
    const expectedGame = game;
    if (!expectedGame || busyRef.current) {
      throw new Error("Cannot restore checkpoint while an operation is in progress.");
    }
    const request = ++actionRequest.current;
    busyRef.current = true;
    setCheckpointBusy(true);
    try {
      const review = await prepareReviewedCheckpointRestore(expectedGame, name, restoreSettings);
      if (!review.plan.canRestore) {
        throw new Error(review.plan.refusalReason ?? "This checkpoint cannot be restored.");
      }
      const result = await review.apply();
      if (request !== actionRequest.current || currentGame.current !== expectedGame) {
        return result;
      }
      await Promise.all([
        refreshInstallation(expectedGame),
        refreshCheckpoints(),
        refreshCache(),
      ]);
      announce(`Restored checkpoint “${name}” successfully.`, "success");
      return result;
    } catch (error) {
      if (request === actionRequest.current && currentGame.current !== expectedGame) {
        announce(errorMessage(error), "error");
      }
      throw error;
    } finally {
      if (request === actionRequest.current) {
        busyRef.current = false;
        setCheckpointBusy(false);
      }
    }
  };

  const renameCheckpoint = async (name: string, newName: string): Promise<void> => {
    const expectedGame = game;
    const target = newName.trim();
    if (!expectedGame || !target || target === name || busyRef.current) return;
    const request = ++actionRequest.current;
    busyRef.current = true;
    setCheckpointBusy(true);
    try {
      const plan = await apiRenameCheckpoint(expectedGame, name, target, null, false);
      await apiRenameCheckpoint(expectedGame, name, target, plan.checkpointFingerprint, true);
      if (request !== actionRequest.current || currentGame.current !== expectedGame) return;
      await refreshCheckpoints();
      announce(`Renamed checkpoint “${name}” to “${target}”.`, "success");
    } catch (error) {
      if (request === actionRequest.current && currentGame.current !== expectedGame) {
        announce(errorMessage(error), "error");
        throw error;
      }
    } finally {
      if (request === actionRequest.current) {
        busyRef.current = false;
        setCheckpointBusy(false);
      }
    }
  };

  const deleteCheckpoint = async (name: string): Promise<void> => {
    const expectedGame = game;
    if (!expectedGame || busyRef.current) return;
    const request = ++actionRequest.current;
    busyRef.current = true;
    setCheckpointBusy(true);
    try {
      const plan = await apiDeleteCheckpoint(expectedGame, name, null, false);
      await apiDeleteCheckpoint(expectedGame, name, plan.checkpointFingerprint, true);
      if (request !== actionRequest.current || currentGame.current !== expectedGame) return;
      await refreshCheckpoints();
      announce(`Deleted checkpoint “${name}”. A safety backup was saved.`, "success");
    } catch (error) {
      if (request === actionRequest.current && currentGame.current !== expectedGame) {
        announce(errorMessage(error), "error");
        throw error;
      }
    } finally {
      if (request === actionRequest.current) {
        busyRef.current = false;
        setCheckpointBusy(false);
      }
    }
  };

  const currentCheckpoints = checkpoints?.installRoot === game ? checkpoints : null;

  return {
    checkpoints: currentCheckpoints,
    checkpointsLoading,
    checkpointBusy,
    refreshCheckpoints,
    pinCheckpoint,
    compareCheckpoint,
    restoreCheckpoint,
    renameCheckpoint,
    deleteCheckpoint,
  };
}
