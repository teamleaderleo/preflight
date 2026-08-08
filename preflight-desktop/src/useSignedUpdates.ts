import { useCallback, useEffect, useRef, useState } from "react";
import { checkForUpdate, installUpdate, isDesktopHost } from "./bridge";
import type { UpdateProgressEvent, UpdateStatus } from "./types";
import { listenWhileMounted } from "./tauriEvents";

export function useSignedUpdates(
  readyForBackgroundCheck: boolean,
  installBlocked: boolean,
  announce: (message: string) => void,
) {
  const [updateStatus, setUpdateStatus] = useState<UpdateStatus | null>(null);
  const [updateChecking, setUpdateChecking] = useState(false);
  const [updateInstalling, setUpdateInstalling] = useState(false);
  const [updateProgress, setUpdateProgress] = useState<UpdateProgressEvent | null>(null);
  const [updateError, setUpdateError] = useState("");
  const updateChecked = useRef(false);

  const checkUpdates = useCallback(async (showResult = false) => {
    if (updateChecking || updateInstalling) return;
    setUpdateChecking(true);
    setUpdateError("");
    try {
      const result = await checkForUpdate();
      setUpdateStatus(result);
      if (showResult) {
        announce(result.available
          ? `Preflight ${result.version} is available. Review it before installing.`
          : result.configured
            ? "Preflight is up to date."
            : result.reason ?? "Verified updates aren’t configured in this build.");
      }
    } catch (error) {
      const detail = String(error);
      setUpdateError(detail);
      if (showResult) announce(detail);
    } finally {
      setUpdateChecking(false);
    }
  }, [announce, updateChecking, updateInstalling]);

  useEffect(() => {
    if (!isDesktopHost() || !readyForBackgroundCheck || updateChecked.current) return;
    updateChecked.current = true;
    void checkUpdates(false);
  }, [checkUpdates, readyForBackgroundCheck]);

  useEffect(() => {
    if (!isDesktopHost()) return;
    return listenWhileMounted<UpdateProgressEvent>("update-progress", ({ payload }) => {
      setUpdateProgress(payload);
      if (payload.state === "installed") {
        announce("The verified update is installed. Restarting Preflight…");
      }
    }, (error) => announce(`Could not observe update progress: ${error}`));
  }, [announce]);

  const installSignedUpdate = async () => {
    if (!updateStatus?.available || !updateStatus.version || updateInstalling || installBlocked) return;
    setUpdateInstalling(true);
    setUpdateProgress(null);
    setUpdateError("");
    announce(`Downloading and verifying Preflight ${updateStatus.version}…`);
    try {
      await installUpdate(updateStatus.version);
    } catch (error) {
      const detail = String(error);
      setUpdateStatus(null);
      setUpdateError(detail);
      announce(detail);
      setUpdateInstalling(false);
    }
  };

  return {
    updateChecking,
    updateError,
    updateInstalling,
    updateProgress,
    updateStatus,
    checkUpdates,
    installSignedUpdate,
  };
}
