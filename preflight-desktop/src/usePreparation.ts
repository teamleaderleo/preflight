import { useCallback, useEffect, useRef, useState } from "react";
import {
  cancelPreparation,
  getCache,
  getPreparationPlan,
  isDesktopHost,
  startPreparation,
} from "./bridge";
import type {
  CacheSnapshot,
  OptimizationPreset,
  PreparationProgressEvent,
  PreparationStateEvent,
  PreparationStoragePlan,
} from "./types";
import { listenWhileMounted } from "./tauriEvents";

export type TextureStorage = "balanced" | "fastest";

export const resourcePresets = {
  gentle: { workers: 2, memoryMib: 128, label: "Low" },
  balanced: { workers: 4, memoryMib: 256, label: "Balanced" },
  eager: { workers: 8, memoryMib: 512, label: "High" },
} as const;

export function isCurrentProfilePrepared(cache: CacheSnapshot | null): boolean {
  if (!cache?.currentProfileFingerprint) return false;
  return cache.profiles.some((profile) =>
    profile.current
    && profile.fingerprint === cache.currentProfileFingerprint
    && profile.indexBytes > 0
    && profile.manifestBytes > 0);
}

export function usePreparation(
  game: string | undefined,
  showStoragePlan: boolean,
  optimizationPreset: OptimizationPreset,
  launch: () => Promise<void>,
  announce: (message: string) => void,
) {
  const [cache, setCache] = useState<CacheSnapshot | null>(null);
  const [cacheLoading, setCacheLoading] = useState(false);
  const [cacheInstallRoot, setCacheInstallRoot] = useState<string | null>(null);
  const cacheRequest = useRef(0);
  const cacheRequestRoot = useRef<string | null>(null);
  const [preparing, setPreparing] = useState(false);
  const [preparationCancelling, setPreparationCancelling] = useState(false);
  const [preparationProgress, setPreparationProgress] = useState<PreparationProgressEvent | null>(null);
  const completedPreparationPhases = useRef(new Set<string>());
  const [preparationPlan, setPreparationPlan] = useState<PreparationStoragePlan | null>(null);
  const [preparationPlanLoading, setPreparationPlanLoading] = useState(false);
  const launchAfterPreparation = useRef(false);
  const [textureStorage, setTextureStorage] = useState<TextureStorage>("balanced");
  const [resourcePreset, setResourcePreset] = useState<keyof typeof resourcePresets>("balanced");

  const refreshCache = useCallback(async () => {
    if (!game) return;
    const request = ++cacheRequest.current;
    cacheRequestRoot.current = game;
    setCacheLoading(true);
    try {
      const next = await getCache(game);
      if (request === cacheRequest.current) setCache(next);
    } catch (error) {
      if (request === cacheRequest.current) announce(String(error));
    } finally {
      if (request === cacheRequest.current) {
        cacheRequestRoot.current = null;
        setCacheInstallRoot(game);
        setCacheLoading(false);
      }
    }
  }, [announce, game]);

  useEffect(() => {
    if (!game) {
      cacheRequest.current += 1;
      cacheRequestRoot.current = null;
      setCache(null);
      setCacheInstallRoot(null);
      setCacheLoading(false);
      return;
    }
    if (cacheInstallRoot !== game && cacheRequestRoot.current !== game) void refreshCache();
  }, [cacheInstallRoot, cacheLoading, game, refreshCache]);

  useEffect(() => {
    const cacheReady = game && cacheInstallRoot === game && !cacheLoading;
    const shouldPlan = cacheReady
      && optimizationPreset !== "off"
      && (showStoragePlan || !isCurrentProfilePrepared(cache));
    if (!game || !shouldPlan) {
      setPreparationPlan(null);
      setPreparationPlanLoading(false);
      return;
    }
    let cancelled = false;
    setPreparationPlanLoading(true);
    void getPreparationPlan(game, textureStorage, resourcePresets.balanced.workers)
      .then((plan) => {
        if (!cancelled) setPreparationPlan(plan);
      })
      .catch((error) => {
        if (!cancelled) {
          setPreparationPlan(null);
          announce(String(error));
        }
      })
      .finally(() => {
        if (!cancelled) setPreparationPlanLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [announce, cache, cacheInstallRoot, cacheLoading, game, optimizationPreset, showStoragePlan, textureStorage]);

  const prepare = async (launchWhenReady = false) => {
    if (!game) return;
    const resources = resourcePresets[resourcePreset];
    try {
      let plan = preparationPlan;
      if (!plan || plan.textureStorage !== textureStorage) {
        setPreparationPlanLoading(true);
        plan = await getPreparationPlan(game, textureStorage, resources.workers);
        setPreparationPlan(plan);
      }
      if (!plan.safeToPrepare) {
        announce(plan.refusalReason ?? "Preparation was refused because its storage requirement could not be bounded safely.");
        return;
      }
      launchAfterPreparation.current = launchWhenReady;
      completedPreparationPhases.current.clear();
      setPreparationProgress(null);
      setPreparationCancelling(false);
      setPreparing(true);
      announce(launchWhenReady
        ? "Preparing the exact current profile. Starsector will open when it’s ready."
        : "Preparing the exact current profile… You can leave this window open.");
      await startPreparation(game, textureStorage, resources.workers, resources.memoryMib);
      if (!isDesktopHost()) {
        setPreparing(false);
        announce("Preview preparation complete.");
        await refreshCache();
        if (launchWhenReady) {
          launchAfterPreparation.current = false;
          await launch();
        }
      }
    } catch (error) {
      launchAfterPreparation.current = false;
      setPreparing(false);
      announce(String(error));
    } finally {
      setPreparationPlanLoading(false);
    }
  };

  useEffect(() => {
    if (!isDesktopHost()) return;
    return listenWhileMounted<PreparationStateEvent>("prepare-state", ({ payload }) => {
      if (payload.state === "cancelling") {
        setPreparationCancelling(true);
        announce("Stopping preparation safely…");
        return;
      }
      if (payload.state !== "finished" && payload.state !== "cancelled") return;
      const shouldLaunch = launchAfterPreparation.current;
      launchAfterPreparation.current = false;
      setPreparing(false);
      setPreparationCancelling(false);
      if (!payload.success) {
        announce(payload.detail ?? "Preparation stopped before it completed.");
        void refreshCache();
        return;
      }
      announce(shouldLaunch
        ? "Preparation is complete. Opening Starsector…"
        : "Preparation is complete. The current profile is warm and ready.");
      void (async () => {
        await refreshCache();
        if (shouldLaunch) await launch();
      })();
    }, (error) => announce(`Could not observe preparation state: ${error}`));
  }, [announce, launch, refreshCache]);

  useEffect(() => {
    if (!isDesktopHost()) return;
    return listenWhileMounted<PreparationProgressEvent>("prepare-progress", ({ payload }) => {
      if (payload.state === "completed") completedPreparationPhases.current.add(payload.phase);
      setPreparationProgress({ ...payload });
    }, (error) => announce(`Could not observe preparation progress: ${error}`));
  }, [announce]);

  const stopPreparation = async () => {
    if (!preparing || preparationCancelling) return;
    setPreparationCancelling(true);
    launchAfterPreparation.current = false;
    announce("Stopping preparation safely…");
    try {
      const requested = await cancelPreparation();
      if (!requested) {
        setPreparing(false);
        setPreparationCancelling(false);
        announce("Preparation had already finished.");
      }
    } catch (error) {
      setPreparationCancelling(false);
      announce(String(error));
    }
  };

  const clearCache = () => {
    cacheRequest.current += 1;
    cacheRequestRoot.current = null;
    setCache(null);
    setCacheInstallRoot(null);
    setCacheLoading(false);
  };
  const invalidatePreparationPlan = () => setPreparationPlan(null);
  const currentCache = cacheInstallRoot === game ? cache : null;
  const profilePrepared = isCurrentProfilePrepared(currentCache);
  const preparationPhaseLabel = preparationProgress?.phase
    ?.replaceAll("-", " ")
    .replace(/^./, (letter) => letter.toUpperCase());
  const preparationPercent = preparationProgress
    ? Math.min(100, Math.round((completedPreparationPhases.current.size / preparationProgress.totalPhases) * 100))
    : 0;

  return {
    cache: currentCache,
    cacheLoading,
    preparationCancelling,
    preparationPercent,
    preparationPhaseLabel,
    preparationPlan,
    preparationPlanLoading,
    preparing,
    profilePrepared,
    resourcePreset,
    textureStorage,
    clearCache,
    invalidatePreparationPlan,
    prepare,
    refreshCache,
    setResourcePreset,
    setTextureStorage,
    stopPreparation,
  };
}
