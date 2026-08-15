import { useCallback, useEffect, useRef, useState } from "react";
import { applyCacheCleanup, applyEvidenceCleanup, getCacheCleanup, getEvidenceCleanup } from "./bridge";
import type { Announce, CacheCleanupPlan, EvidenceCleanupPlan } from "./types";
import { formatBytes } from "./uiFormat";

export interface StorageCleanupPlan {
  cache: CacheCleanupPlan;
  evidence: EvidenceCleanupPlan;
  bytes: number;
  files: number;
}

export function useCacheCleanup(
  game: string | undefined,
  announce: Announce,
  refreshCache: () => Promise<void>,
  invalidatePreparationPlan: () => void,
) {
  const [plan, setPlan] = useState<StorageCleanupPlan | null>(null);
  const [planGame, setPlanGame] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const request = useRef(0);
  const busyRef = useRef(false);
  const currentGame = useRef(game);
  currentGame.current = game;

  useEffect(() => {
    request.current += 1;
    busyRef.current = false;
    setPlan(null);
    setPlanGame(null);
    setBusy(false);
  }, [game]);

  const review = useCallback(async () => {
    const expectedGame = game;
    if (!expectedGame || busyRef.current) return;
    const currentRequest = ++request.current;
    busyRef.current = true;
    setBusy(true);
    try {
      const [cache, evidence] = await Promise.all([
        getCacheCleanup(expectedGame),
        getEvidenceCleanup(),
      ]);
      if (currentRequest !== request.current || currentGame.current !== expectedGame) return;
      const next = {
        cache,
        evidence,
        bytes: cache.bytes + evidence.bytes,
        files: cache.files + evidence.files,
      };
      setPlan(next);
      setPlanGame(expectedGame);
      announce(cache.safe
        ? next.files === 0
          ? "There’s no old Preflight data to clean up."
          : "Cleanup is ready to review. Nothing has been removed."
        : cache.refusals[0] ?? "Preflight couldn’t prove that cleanup was safe.");
    } catch (error) {
      if (currentRequest === request.current && currentGame.current === expectedGame) announce(String(error), "error");
    } finally {
      if (currentRequest === request.current) {
        busyRef.current = false;
        setBusy(false);
      }
    }
  }, [announce, game]);

  const clean = useCallback(async () => {
    const expectedGame = game;
    const reviewedPlan = planGame === game ? plan : null;
    if (!expectedGame || !reviewedPlan?.cache.safe || reviewedPlan.files === 0 || busyRef.current) return;
    const currentRequest = ++request.current;
    busyRef.current = true;
    setBusy(true);
    let freedBytes = 0;
    let freedFiles = 0;
    try {
      if (reviewedPlan.cache.files > 0) {
        const cacheResult = await applyCacheCleanup(expectedGame);
        freedBytes += cacheResult.bytes;
        freedFiles += cacheResult.files;
      }
      if (reviewedPlan.evidence.files > 0) {
        const evidenceResult = await applyEvidenceCleanup();
        freedBytes += evidenceResult.removedBytes;
        freedFiles += evidenceResult.files;
      }
      if (currentRequest !== request.current || currentGame.current !== expectedGame) return;
      setPlan(null);
      setPlanGame(null);
      announce(`Freed ${formatBytes(freedBytes)} across ${freedFiles.toLocaleString()} old files. Current and saved profiles stay fast.`, "success");
      await refreshCache();
      if (currentRequest === request.current && currentGame.current === expectedGame) invalidatePreparationPlan();
    } catch (error) {
      if (currentRequest === request.current && currentGame.current === expectedGame) {
        announce(freedBytes > 0
          ? `Freed ${formatBytes(freedBytes)}, then cleanup stopped: ${String(error)}`
          : String(error), "error");
        await refreshCache();
      }
    } finally {
      if (currentRequest === request.current) {
        busyRef.current = false;
        setBusy(false);
      }
    }
  }, [announce, game, invalidatePreparationPlan, plan, planGame, refreshCache]);

  const dismiss = useCallback(() => {
    request.current += 1;
    busyRef.current = false;
    setPlan(null);
    setPlanGame(null);
    setBusy(false);
  }, []);

  return {
    plan: planGame === game ? plan : null,
    busy,
    review,
    clean,
    dismiss,
  };
}
