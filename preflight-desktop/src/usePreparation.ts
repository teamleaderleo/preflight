import { useCallback, useEffect, useRef, useState } from "react";
import { listen } from "@tauri-apps/api/event";
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
    setCacheLoading(true);
    try {
      setCache(await getCache(game));
    } catch (error) {
      announce(String(error));
    } finally {
      setCacheInstallRoot(game);
      setCacheLoading(false);
    }
  }, [announce, game]);

  useEffect(() => {
    if (game && cacheInstallRoot !== game && !cacheLoading) void refreshCache();
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
    let stopListening: (() => void) | undefined;
    void listen<PreparationStateEvent>("prepare-state", ({ payload }) => {
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
    }).then((unlisten) => {
      stopListening = unlisten;
    });
    return () => stopListening?.();
  }, [announce, launch, refreshCache]);

  useEffect(() => {
    if (!isDesktopHost()) return;
    let stopListening: (() => void) | undefined;
    void listen<PreparationProgressEvent>("prepare-progress", ({ payload }) => {
      if (payload.state === "completed") completedPreparationPhases.current.add(payload.phase);
      setPreparationProgress({ ...payload });
    }).then((unlisten) => {
      stopListening = unlisten;
    });
    return () => stopListening?.();
  }, []);

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

  const clearCache = () => setCache(null);
  const invalidatePreparationPlan = () => setPreparationPlan(null);
  const profilePrepared = isCurrentProfilePrepared(cache);
  const preparationPhaseLabel = preparationProgress?.phase
    ?.replaceAll("-", " ")
    .replace(/^./, (letter) => letter.toUpperCase());
  const preparationPercent = preparationProgress
    ? Math.min(100, Math.round((completedPreparationPhases.current.size / preparationProgress.totalPhases) * 100))
    : 0;

  return {
    cache,
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
