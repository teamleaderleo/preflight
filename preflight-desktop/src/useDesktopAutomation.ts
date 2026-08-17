import { useCallback, useEffect, useRef, useState } from "react";
import {
  cancelDesktopSmoke,
  getDesktopSmokeProbe,
  isDesktopHost,
  startDesktopSmoke,
} from "./bridge";
import type { Announce, AppStatus, DesktopBenchmarkComparison, DesktopSmokeProbe, DesktopSmokeStateEvent, OperationSnapshot } from "./types";
import { listenWhileMounted } from "./tauriEvents";
import { startOperationReconciliation } from "./operationReconciliation";
import { errorMessage } from "./uiFormat";

interface DesktopAutomationOptions {
  game: string | undefined;
  installationReady: boolean;
  announce: Announce;
  displayPath: (path: string) => string;
  refreshInstallation: (game?: string) => Promise<boolean>;
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
  const [desktopSmokeRunning, setDesktopSmokeRunning] = useState(false);
  const [desktopSmokeCancelling, setDesktopSmokeCancelling] = useState(false);
  const [desktopSmokeRunDirectory, setDesktopSmokeRunDirectory] = useState<string | null>(null);
  const [desktopBenchmarkComparison, setDesktopBenchmarkComparison] = useState<DesktopBenchmarkComparison | null>(null);
  const probeBusyRef = useRef(false);
  const runningRef = useRef(false);
  const cancellingRef = useRef(false);

  useEffect(() => {
    if (!isDesktopHost()) return;
    let stopReconciliation: () => void = () => undefined;
    const stopListening = listenWhileMounted<DesktopSmokeStateEvent>("desktop-smoke-state", ({ payload }) => {
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
      setDesktopBenchmarkComparison(payload.comparison ?? null);
      setStatus(installationReady ? "ready" : "setup");
      const outcome = payload.state === "cancelled"
        ? payload.detail ?? `Benchmark stopped safely. Evidence is in ${displayPath(payload.runDirectory)}.`
        : payload.success
        ? `Startup benchmark finished. Evidence is in ${displayPath(payload.runDirectory)}.`
        : payload.detail ?? `Benchmark stopped. Evidence is in ${displayPath(payload.runDirectory)}.`;
      void refreshInstallation(game).then((refreshed) => {
        if (refreshed) announce(outcome, payload.success ? "success" : "error");
      });
    }, (error) => {
      announce(`Live benchmark updates were interrupted: ${error}. Preflight is checking native state directly.`, "warning");
      let previousPid: number | null | undefined;
      stopReconciliation();
      stopReconciliation = startOperationReconciliation({
        apply: (operation) => {
          setDesktopSmokeRunDirectory(operation.desktopSmokeRunDirectory);
          if (operation.desktopSmokePid !== null) {
            previousPid = operation.desktopSmokePid;
            runningRef.current = true;
            setDesktopSmokeRunning(true);
            setStatus("running");
            return;
          }
          if (previousPid !== null && previousPid !== undefined) {
            previousPid = null;
            runningRef.current = false;
            cancellingRef.current = false;
            setDesktopSmokeRunning(false);
            setDesktopSmokeCancelling(false);
            setStatus(installationReady ? "ready" : "setup");
            void refreshInstallation(game).then((refreshed) => {
              if (refreshed) announce("The benchmark stopped. Its exact outcome is available in the saved run evidence.", "warning");
            });
          } else {
            previousPid = null;
          }
        },
        isActive: () => true,
        onError: (pollError) => announce(`Could not refresh native benchmark state: ${pollError}`, "error"),
      });
    });
    return () => {
      stopListening();
      stopReconciliation();
    };
  }, [announce, displayPath, game, installationReady, refreshInstallation, setStatus]);

  const checkDesktopAutomation = async (reviewWhenReady = false) => {
    if (probeBusyRef.current || runningRef.current) return;
    probeBusyRef.current = true;
    setDesktopSmokeProbeBusy(true);
    try {
      const probe = await getDesktopSmokeProbe();
      setDesktopSmokeProbe(probe);
      if (probe.probe.ready && reviewWhenReady) await runDesktopAutomation(probe);
    } catch (error) {
      setDesktopSmokeProbe(null);
      announce(errorMessage(error), "error");
    } finally {
      probeBusyRef.current = false;
      setDesktopSmokeProbeBusy(false);
    }
  };

  const runDesktopAutomation = async (readyProbe = desktopSmokeProbe) => {
    if (!game || !readyProbe?.probe.ready || runningRef.current) return;
    runningRef.current = true;
    cancellingRef.current = false;
    setDesktopSmokeRunning(true);
    setDesktopSmokeCancelling(false);
    setDesktopSmokeRunDirectory(null);
    setDesktopBenchmarkComparison(null);
    setStatus("running");
    announce("Starting a normal launch, then a Preflight launch…");
    try {
      await startDesktopSmoke(game);
      if (!isDesktopHost()) {
        runningRef.current = false;
        setDesktopSmokeRunning(false);
        setStatus("ready");
        setDesktopSmokeRunDirectory("~/.starsector-preflight/runs/desktop-smoke-preview");
        setDesktopBenchmarkComparison({
          available: true,
          identity: {
            profileFingerprint: "12".repeat(32),
            benchmarkIdentitySha256: "ab".repeat(32),
          },
          metrics: {
            processToMainMenuMs: { measurementOnly: 88_130, optimized: 15_880, delta: -72_250, improvementPercent: 81.98 },
          },
          context: {
            storage: { scope: "all-prepared-data", bytes: 2_426_028_032, files: 15_104 },
          },
        });
        announce("Startup benchmark finished in browser preview.", "success");
      }
    } catch (error) {
      runningRef.current = false;
      cancellingRef.current = false;
      setDesktopSmokeRunning(false);
      setDesktopSmokeCancelling(false);
      setStatus(installationReady ? "ready" : "setup");
      announce(errorMessage(error), "error");
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
        announce("The benchmark had already stopped.", "warning");
      }
    } catch (error) {
      cancellingRef.current = false;
      setDesktopSmokeCancelling(false);
      announce(errorMessage(error), "error");
    }
  };

  const reconnectDesktopAutomation = useCallback((operation: OperationSnapshot) => {
    if (operation.desktopSmokePid === null) return false;
    runningRef.current = true;
    cancellingRef.current = false;
    setDesktopSmokeRunning(true);
    setDesktopSmokeCancelling(false);
    setDesktopSmokeRunDirectory(operation.desktopSmokeRunDirectory);
    setStatus("running");
    return true;
  }, [setStatus]);

  return {
    desktopSmokeProbe,
    desktopSmokeProbeBusy,
    desktopSmokeRunDirectory,
    desktopBenchmarkComparison,
    desktopSmokeCancelling,
    desktopSmokeRunning,
    checkDesktopAutomation,
    reconnectDesktopAutomation,
    runDesktopAutomation,
    stopDesktopAutomation,
  };
}
