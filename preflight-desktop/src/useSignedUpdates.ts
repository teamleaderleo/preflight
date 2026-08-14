import { useCallback, useEffect, useRef, useState } from "react";
import { checkForUpdate, installUpdate, isDesktopHost } from "./bridge";
import type { Announce, UpdateProgressEvent, UpdateStatus } from "./types";
import { listenWhileMounted } from "./tauriEvents";
import { startOperationReconciliation } from "./operationReconciliation";
import { errorMessage } from "./uiFormat";

const AUTOMATIC_UPDATE_CHECK_STORAGE_KEY = "preflight.automaticUpdateChecks";

/**
 * Whether Preflight may check for updates on its own.
 *
 * The check is the only request a packaged build makes without being asked, and until it could be
 * turned off there was nowhere to say so truthfully. It stays on by default -- a launcher that
 * silently goes stale is worse -- but the setting exists, is disclosed, and is honoured.
 */
function savedAutomaticUpdateChecks(): boolean {
  try {
    return window.localStorage.getItem(AUTOMATIC_UPDATE_CHECK_STORAGE_KEY) !== "off";
  } catch {
    return true;
  }
}

export function useSignedUpdates(
  readyForBackgroundCheck: boolean,
  installBlocked: boolean,
  announce: Announce,
) {
  const [updateStatus, setUpdateStatus] = useState<UpdateStatus | null>(null);
  const [updateChecking, setUpdateChecking] = useState(false);
  const [updateInstalling, setUpdateInstalling] = useState(false);
  const [updateProgress, setUpdateProgress] = useState<UpdateProgressEvent | null>(null);
  const [updateError, setUpdateError] = useState("");
  const updateChecked = useRef(false);
  const [automaticUpdateChecks, setAutomaticUpdateChecks] = useState(savedAutomaticUpdateChecks);

  useEffect(() => {
    try {
      window.localStorage.setItem(
        AUTOMATIC_UPDATE_CHECK_STORAGE_KEY,
        automaticUpdateChecks ? "on" : "off",
      );
    } catch {
      // The choice still holds for this session when persistent storage is unavailable.
    }
  }, [automaticUpdateChecks]);

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
      const failure = `Couldn’t check for updates. ${errorMessage(error)}`;
      setUpdateError(failure);
      if (showResult) announce(failure, "error");
    } finally {
      setUpdateChecking(false);
    }
  }, [announce, updateChecking, updateInstalling]);

  useEffect(() => {
    if (!isDesktopHost() || !automaticUpdateChecks || !readyForBackgroundCheck) return;
    if (updateChecked.current) return;
    updateChecked.current = true;
    void checkUpdates(false);
  }, [automaticUpdateChecks, checkUpdates, readyForBackgroundCheck]);

  useEffect(() => {
    if (!isDesktopHost()) return;
    let stopReconciliation: () => void = () => undefined;
    const stopListening = listenWhileMounted<UpdateProgressEvent>("update-progress", ({ payload }) => {
      setUpdateProgress(payload);
      if (payload.state === "installed") {
        announce("The verified update is installed. Restarting Preflight…");
      }
    }, (error) => {
      announce(`Live update progress was interrupted: ${error}. Preflight is checking native state directly.`, "warning");
      let wasInstalling = false;
      stopReconciliation();
      stopReconciliation = startOperationReconciliation({
        apply: (operation) => {
          if (operation.updateInstalling) {
            wasInstalling = true;
            setUpdateInstalling(true);
            return;
          }
          if (wasInstalling) {
            wasInstalling = false;
            setUpdateInstalling(false);
            announce("The update operation stopped. Check the current version before retrying.", "warning");
          }
        },
        isActive: () => true,
        onError: (pollError) => announce(`Could not refresh native update state: ${pollError}`, "error"),
      });
    });
    return () => {
      stopListening();
      stopReconciliation();
    };
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
      const detail = errorMessage(error);
      const failure = `Update wasn’t installed. Preflight ${updateStatus.currentVersion} is unchanged. ${detail}`;
      setUpdateError(failure);
      announce(failure, "error");
      setUpdateInstalling(false);
    }
  };

  return {
    updateChecking,
    updateError,
    updateInstalling,
    updateProgress,
    updateStatus,
    automaticUpdateChecks,
    setAutomaticUpdateChecks,
    checkUpdates,
    installSignedUpdate,
  };
}
