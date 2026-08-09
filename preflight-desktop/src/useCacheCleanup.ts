import { useCallback, useEffect, useRef, useState } from "react";
import { applyCacheCleanup, getCacheCleanup } from "./bridge";
import type { Announce, CacheCleanupPlan } from "./types";
import { formatBytes } from "./uiFormat";

export function useCacheCleanup(
  game: string | undefined,
  announce: Announce,
  refreshCache: () => Promise<void>,
  invalidatePreparationPlan: () => void,
) {
  const [plan, setPlan] = useState<CacheCleanupPlan | null>(null);
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
      const next = await getCacheCleanup(expectedGame);
      if (currentRequest !== request.current || currentGame.current !== expectedGame) return;
      setPlan(next);
      setPlanGame(expectedGame);
      announce(next.safe
        ? next.files === 0
          ? "There’s no unused acceleration data to clean up."
          : "Cleanup is ready to review. Nothing has been removed."
        : next.refusals[0] ?? "Preflight couldn’t prove that cleanup was safe.");
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
    if (!expectedGame || !reviewedPlan?.safe || reviewedPlan.files === 0 || busyRef.current) return;
    const currentRequest = ++request.current;
    busyRef.current = true;
    setBusy(true);
    try {
      const result = await applyCacheCleanup(expectedGame);
      if (currentRequest !== request.current || currentGame.current !== expectedGame) return;
      setPlan(null);
      setPlanGame(null);
      announce(`Freed ${formatBytes(result.bytes)} across ${result.files.toLocaleString()} unused files. The current and named profiles stay warm.`, "success");
      await refreshCache();
      if (currentRequest === request.current && currentGame.current === expectedGame) invalidatePreparationPlan();
    } catch (error) {
      if (currentRequest === request.current && currentGame.current === expectedGame) announce(String(error), "error");
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
