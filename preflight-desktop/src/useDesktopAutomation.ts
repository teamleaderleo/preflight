import { useEffect, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import {
  cancelDesktopSmoke,
  getDesktopSmokeProbe,
  isDesktopHost,
  startDesktopSmoke,
} from "./bridge";
import type { AppStatus, DesktopSmokeProbe, DesktopSmokeStateEvent } from "./types";

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
  const [desktopSmokeRunDirectory, setDesktopSmokeRunDirectory] = useState<string | null>(null);

  useEffect(() => {
    if (!isDesktopHost()) return;
    let stopListening: (() => void) | undefined;
    void listen<DesktopSmokeStateEvent>("desktop-smoke-state", ({ payload }) => {
      setDesktopSmokeRunDirectory(payload.runDirectory);
      if (payload.state === "cancelling") {
        announce(payload.detail ?? "Stopping the exact game process and sealing its evidence…");
        return;
      }
      if (payload.state !== "finished" && payload.state !== "cancelled") return;
      setDesktopSmokeRunning(false);
      setStatus(installationReady ? "ready" : "setup");
      const outcome = payload.state === "cancelled"
        ? payload.detail ?? `Automated game test stopped safely. Evidence is in ${displayPath(payload.runDirectory)}.`
        : payload.success
        ? `Automated game test passed. Evidence is in ${displayPath(payload.runDirectory)}.`
        : payload.detail ?? `Automated game test stopped. Evidence is in ${displayPath(payload.runDirectory)}.`;
      void refreshInstallation(game).then(() => announce(outcome));
    }).then((unlisten) => {
      stopListening = unlisten;
    });
    return () => stopListening?.();
  }, [announce, displayPath, game, installationReady, refreshInstallation, setStatus]);

  const checkDesktopAutomation = async () => {
    setDesktopSmokeProbeBusy(true);
    try {
      setDesktopSmokeProbe(await getDesktopSmokeProbe());
    } catch (error) {
      setDesktopSmokeProbe(null);
      announce(String(error));
    } finally {
      setDesktopSmokeProbeBusy(false);
    }
  };

  const runDesktopAutomation = async () => {
    if (!game || !desktopSmokeProbe?.probe.ready || desktopSmokeRunning) return;
    setDesktopSmokeReview(false);
    setDesktopSmokeRunning(true);
    setDesktopSmokeRunDirectory(null);
    setStatus("running");
    announce("Running the checked launch, campaign movement, evidence, and shutdown sequence…");
    try {
      await startDesktopSmoke(game);
      if (!isDesktopHost()) {
        setDesktopSmokeRunning(false);
        setStatus("ready");
        setDesktopSmokeRunDirectory("~/.starsector-preflight/runs/desktop-smoke-preview");
        announce("Automated game test passed in browser preview.");
      }
    } catch (error) {
      setDesktopSmokeRunning(false);
      setStatus(installationReady ? "ready" : "setup");
      announce(String(error));
    }
  };

  const stopDesktopAutomation = async () => {
    if (!desktopSmokeRunning) return;
    announce("Stopping the exact game process and sealing its evidence…");
    try {
      const requested = await cancelDesktopSmoke();
      if (!requested) {
        setDesktopSmokeRunning(false);
        setStatus(installationReady ? "ready" : "setup");
        announce("The automated game test had already stopped.");
      }
    } catch (error) {
      announce(String(error));
    }
  };

  return {
    desktopSmokeProbe,
    desktopSmokeProbeBusy,
    desktopSmokeReview,
    desktopSmokeRunDirectory,
    desktopSmokeRunning,
    checkDesktopAutomation,
    runDesktopAutomation,
    setDesktopSmokeReview,
    stopDesktopAutomation,
  };
}
