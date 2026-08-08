import { useEffect, useRef, useState } from "react";
import {
  cancelDesktopSmoke,
  getDesktopSmokeProbe,
  isDesktopHost,
  startDesktopSmoke,
} from "./bridge";
import type { AppStatus, DesktopSmokeProbe, DesktopSmokeStateEvent } from "./types";
import { listenWhileMounted } from "./tauriEvents";

interface DesktopAutomationOptions {
  game: string | undefined;
  installationReady: boolean;
  announce: (message: string) => void;
  displayPath: (path: string) => string;
  refreshInstallation: (game?: string) => Promise<void>;
  setStatus: (status: AppStatus) => void;
}

export function useDesktopAutomation({
  game,
  installationReady,
  announce,
  displayPath,
  refreshInstallation,
  setStatus,
}: DesktopAutomationOptions) {
  const [desktopSmokeProbe, setDesktopSmokeProbe] = useState<DesktopSmokeProbe | null>(null);
  const [desktopSmokeProbeBusy, setDesktopSmokeProbeBusy] = useState(false);
  const [desktopSmokeReview, setDesktopSmokeReview] = useState(false);
  const [desktopSmokeRunning, setDesktopSmokeRunning] = useState(false);
  const [desktopSmokeCancelling, setDesktopSmokeCancelling] = useState(false);
  const [desktopSmokeRunDirectory, setDesktopSmokeRunDirectory] = useState<string | null>(null);
  const probeBusyRef = useRef(false);
  const runningRef = useRef(false);
  const cancellingRef = useRef(false);

  useEffect(() => {
    if (!isDesktopHost()) return;
    return listenWhileMounted<DesktopSmokeStateEvent>("desktop-smoke-state", ({ payload }) => {
      setDesktopSmokeRunDirectory(payload.runDirectory);
      if (payload.state === "cancelling") {
        cancellingRef.current = true;
        setDesktopSmokeCancelling(true);
        announce(payload.detail ?? "Stopping the exact game process and sealing its evidence…");
        return;
      }
      if (payload.state !== "finished" && payload.state !== "cancelled") return;
      runningRef.current = false;
      cancellingRef.current = false;
      setDesktopSmokeRunning(false);
      setDesktopSmokeCancelling(false);
      setStatus(installationReady ? "ready" : "setup");
      const outcome = payload.state === "cancelled"
        ? payload.detail ?? `Automated game test stopped safely. Evidence is in ${displayPath(payload.runDirectory)}.`
        : payload.success
        ? `Automated game test passed. Evidence is in ${displayPath(payload.runDirectory)}.`
        : payload.detail ?? `Automated game test stopped. Evidence is in ${displayPath(payload.runDirectory)}.`;
      void refreshInstallation(game).then(() => announce(outcome));
    }, (error) => announce(`Could not observe automated test state: ${error}`));
  }, [announce, displayPath, game, installationReady, refreshInstallation, setStatus]);

  const checkDesktopAutomation = async () => {
    if (probeBusyRef.current || runningRef.current) return;
    probeBusyRef.current = true;
    setDesktopSmokeProbeBusy(true);
    try {
      setDesktopSmokeProbe(await getDesktopSmokeProbe());
    } catch (error) {
      setDesktopSmokeProbe(null);
      announce(String(error));
    } finally {
      probeBusyRef.current = false;
      setDesktopSmokeProbeBusy(false);
    }
  };

  const runDesktopAutomation = async () => {
    if (!game || !desktopSmokeProbe?.probe.ready || probeBusyRef.current || runningRef.current) return;
    runningRef.current = true;
    cancellingRef.current = false;
    setDesktopSmokeReview(false);
    setDesktopSmokeRunning(true);
    setDesktopSmokeCancelling(false);
    setDesktopSmokeRunDirectory(null);
    setStatus("running");
    announce("Running the checked launch, campaign movement, evidence, and shutdown sequence…");
    try {
      await startDesktopSmoke(game);
      if (!isDesktopHost()) {
        runningRef.current = false;
        setDesktopSmokeRunning(false);
        setStatus("ready");
        setDesktopSmokeRunDirectory("~/.starsector-preflight/runs/desktop-smoke-preview");
        announce("Automated game test passed in browser preview.");
      }
    } catch (error) {
      runningRef.current = false;
      cancellingRef.current = false;
      setDesktopSmokeRunning(false);
      setDesktopSmokeCancelling(false);
      setStatus(installationReady ? "ready" : "setup");
      announce(String(error));
    }
  };

  const stopDesktopAutomation = async () => {
    if (!runningRef.current || cancellingRef.current) return;
    cancellingRef.current = true;
    setDesktopSmokeCancelling(true);
    announce("Stopping the exact game process and sealing its evidence…");
    try {
      const requested = await cancelDesktopSmoke();
      if (!requested) {
        runningRef.current = false;
        cancellingRef.current = false;
        setDesktopSmokeRunning(false);
        setDesktopSmokeCancelling(false);
        setStatus(installationReady ? "ready" : "setup");
        announce("The automated game test had already stopped.");
      }
    } catch (error) {
      cancellingRef.current = false;
      setDesktopSmokeCancelling(false);
      announce(String(error));
    }
  };

  return {
    desktopSmokeProbe,
    desktopSmokeProbeBusy,
    desktopSmokeReview,
    desktopSmokeRunDirectory,
    desktopSmokeCancelling,
    desktopSmokeRunning,
    checkDesktopAutomation,
    runDesktopAutomation,
    setDesktopSmokeReview,
    stopDesktopAutomation,
  };
}
