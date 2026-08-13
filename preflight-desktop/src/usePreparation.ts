import { useCallback, useEffect, useRef, useState } from "react";
import {
  cancelPreparation,
  getCache,
  getCacheHealth,
  getPreparationPlan,
  isDesktopHost,
  repairCache,
  startPreparation,
} from "./bridge";
import type {
  Announce,
  CacheHealth,
  CacheSnapshot,
  OptimizationPreset,
  PreparationProgressEvent,
  PreparationStateEvent,
  PreparationStoragePlan,
} from "./types";
import { listenWhileMounted } from "./tauriEvents";
import { startOperationReconciliation } from "./operationReconciliation";

export type TextureStorage = "balanced" | "fastest";

export const resourcePresets = {
  gentle: { workers: 2, memoryMib: 128, label: "Low" },
  balanced: { workers: 4, memoryMib: 256, label: "Medium" },
  eager: { workers: 8, memoryMib: 512, label: "High" },
} as const;

interface PreparationPlanEnvelope {
  game: string;
  profileFingerprint: string;
  textureStorage: TextureStorage;
  workers: number;
  plan: PreparationStoragePlan;
}

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
  announce: Announce,
) {
  const [cache, setCache] = useState<CacheSnapshot | null>(null);
  const [cacheHealth, setCacheHealth] = useState<CacheHealth | null>(null);
  const [cacheLoading, setCacheLoading] = useState(false);
  const [cacheRepairing, setCacheRepairing] = useState(false);
  const [cacheInstallRoot, setCacheInstallRoot] = useState<string | null>(null);
  const cacheRequest = useRef(0);
  const cacheRequestRoot = useRef<string | null>(null);
  const [preparing, setPreparing] = useState(false);
  const [preparationCancelling, setPreparationCancelling] = useState(false);
  const [preparationProgress, setPreparationProgress] = useState<PreparationProgressEvent | null>(null);
  const completedPreparationPhases = useRef(new Set<string>());
  const [preparationPlanEnvelope, setPreparationPlanEnvelope] = useState<PreparationPlanEnvelope | null>(null);
  const [preparationPlanLoading, setPreparationPlanLoading] = useState(false);
  const launchAfterPreparation = useRef(false);
  const [textureStorage, setTextureStorage] = useState<TextureStorage>("balanced");
  const [resourcePreset, setResourcePreset] = useState<keyof typeof resourcePresets>("balanced");
  const gameRef = useRef(game);
  gameRef.current = game;

  const refreshCache = useCallback(async () => {
    if (!game) return;
    const request = ++cacheRequest.current;
    cacheRequestRoot.current = game;
    setCacheLoading(true);
    try {
      const [next, health] = await Promise.all([getCache(game), getCacheHealth(game)]);
      if (request === cacheRequest.current) {
        setCache(next);
        setCacheHealth(health);
      }
    } catch (error) {
      if (request === cacheRequest.current) {
        setCache(null);
        setCacheHealth(null);
        announce(String(error), "error");
      }
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
      setCacheHealth(null);
      setCacheInstallRoot(null);
      setCacheLoading(false);
      return;
    }
    if (cacheInstallRoot !== game && cacheRequestRoot.current !== game) void refreshCache();
  }, [cacheInstallRoot, cacheLoading, game, refreshCache]);

  const currentCache = cacheInstallRoot === game ? cache : null;
  const currentCacheHealth = cacheInstallRoot === game ? cacheHealth : null;
  const profilePrepared = isCurrentProfilePrepared(currentCache)
    && currentCacheHealth?.status === "ready";
  const resources = resourcePresets[resourcePreset];
  const preparationPlan = preparationPlanEnvelope
    && preparationPlanEnvelope.game === game
    && preparationPlanEnvelope.textureStorage === textureStorage
    && preparationPlanEnvelope.workers === resources.workers
    && preparationPlanEnvelope.profileFingerprint === currentCache?.currentProfileFingerprint
    ? preparationPlanEnvelope.plan
    : null;

  useEffect(() => {
    const cacheReady = game && cacheInstallRoot === game && !cacheLoading;
    const shouldPlan = cacheReady
      && optimizationPreset !== "off"
      && (showStoragePlan || !profilePrepared);
    if (!game || !shouldPlan) {
      setPreparationPlanEnvelope(null);
      setPreparationPlanLoading(false);
      return;
    }
    let cancelled = false;
    setPreparationPlanLoading(true);
    void getPreparationPlan(game, textureStorage, resources.workers)
      .then((plan) => {
        if (!cancelled) {
          setPreparationPlanEnvelope({
            game,
            profileFingerprint: plan.profileFingerprint,
            textureStorage,
            workers: resources.workers,
            plan,
          });
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setPreparationPlanEnvelope(null);
          announce(String(error), "error");
        }
      })
      .finally(() => {
        if (!cancelled) setPreparationPlanLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [announce, cacheInstallRoot, cacheLoading, game, optimizationPreset, profilePrepared, resources.workers, showStoragePlan, textureStorage]);

  const runPreparation = async (launchWhenReady = false, forcePlan = false) => {
    if (!game) return;
    try {
      let plan = forcePlan ? null : preparationPlan;
      if (!plan) {
        setPreparationPlanLoading(true);
        plan = await getPreparationPlan(game, textureStorage, resources.workers);
        setPreparationPlanEnvelope({
          game,
          profileFingerprint: plan.profileFingerprint,
          textureStorage,
          workers: resources.workers,
          plan,
        });
      }
      if (!plan.safeToPrepare) {
        announce(plan.refusalReason ?? "Preparation was refused because its storage requirement could not be bounded safely.", "warning");
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
        announce("Preview preparation complete.", "success");
        await refreshCache();
        if (launchAfterPreparation.current) {
          launchAfterPreparation.current = false;
          await launch();
        }
      }
    } catch (error) {
      launchAfterPreparation.current = false;
      setPreparing(false);
      announce(String(error), "error");
    } finally {
      setPreparationPlanLoading(false);
    }
  };

  const prepare = async (launchWhenReady = false) => runPreparation(launchWhenReady, false);

  const repairAndPrepare = async (launchWhenReady = false) => {
    if (!game || cacheRepairing) return;
    const repairGame = game;
    setCacheRepairing(true);
    try {
      const expectedProfile = currentCacheHealth?.profileFingerprint;
      if (!expectedProfile || currentCacheHealth?.status !== "repair-needed") {
        announce("Prepared data changed before repair could begin. Checking it again…", "warning");
        await refreshCache();
        return;
      }
      const repair = await repairCache(repairGame, expectedProfile);
      if (gameRef.current !== repairGame) return;
      if (!repair.safe) {
        announce(repair.status === "profile-changed"
          ? "The mod setup changed before repair began. Nothing was removed; review the refreshed result."
          : repair.applied
            ? `Repair stopped after removing ${repair.files.toLocaleString()} profile-scoped artifact${repair.files === 1 ? "" : "s"} because the cache boundary changed. Review the refreshed result.`
            : "Preflight couldn't verify a safe repair boundary, so nothing was changed.", "error");
        await refreshCache();
        return;
      }
      setCacheHealth({
        format: "starsector-preflight-cache-health-v1",
        status: "cold",
        profileFingerprint: repair.profileFingerprint,
        issues: [],
        repairBytes: 0,
        repairFiles: 0,
      });
      setPreparationPlanEnvelope(null);
      announce(`Removed ${repair.files.toLocaleString()} damaged profile artifact${repair.files === 1 ? "" : "s"}. Rebuilding prepared data now.`, "warning");
      await runPreparation(launchWhenReady, true);
    } catch (error) {
      if (gameRef.current === repairGame) announce(String(error), "error");
    } finally {
      if (gameRef.current === repairGame) setCacheRepairing(false);
    }
  };

  useEffect(() => {
    if (!isDesktopHost()) return;
    let stopReconciliation: () => void = () => undefined;
    const stopListening = listenWhileMounted<PreparationStateEvent>("prepare-state", ({ payload }) => {
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
        announce(payload.detail ?? "Preparation stopped before it completed.", "warning");
        void refreshCache();
        return;
      }
      announce(shouldLaunch
        ? "Preparation is complete. Opening Starsector…"
        : "Preparation is complete. The current profile is ready.", "success");
      void (async () => {
        await refreshCache();
        if (shouldLaunch) await launch();
      })();
    }, (error) => {
      announce(`Live preparation updates were interrupted: ${error}. Preflight is checking native state directly.`, "warning");
      let previousPid: number | null | undefined;
      stopReconciliation();
      stopReconciliation = startOperationReconciliation({
        apply: (operation) => {
          if (operation.preparationPid !== null) {
            previousPid = operation.preparationPid;
            setPreparing(true);
            return;
          }
          if (previousPid !== null && previousPid !== undefined) {
            previousPid = null;
            launchAfterPreparation.current = false;
            setPreparing(false);
            setPreparationCancelling(false);
            setPreparationProgress(null);
            announce("Preparation stopped. Live completion details were unavailable, so Preflight refreshed the current cache state.", "warning");
            void refreshCache();
          } else {
            previousPid = null;
          }
        },
        isActive: () => true,
        onError: (pollError) => announce(`Could not refresh native preparation state: ${pollError}`, "error"),
      });
    });
    return () => {
      stopListening();
      stopReconciliation();
    };
  }, [announce, launch, refreshCache]);

  useEffect(() => {
    if (!isDesktopHost()) return;
    return listenWhileMounted<PreparationProgressEvent>("prepare-progress", ({ payload }) => {
      if (payload.state === "completed") completedPreparationPhases.current.add(payload.phase);
      setPreparationProgress({ ...payload });
    }, (error) => announce(`Could not observe preparation progress: ${error}`, "error"));
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
      announce(String(error), "error");
    }
  };

  const clearCache = () => {
    cacheRequest.current += 1;
    cacheRequestRoot.current = null;
    setCache(null);
    setCacheHealth(null);
    setCacheInstallRoot(null);
    setCacheLoading(false);
  };
  const invalidatePreparationPlan = () => setPreparationPlanEnvelope(null);
  const preparationPhaseLabel = preparationProgress?.phase
    ?.replaceAll("-", " ")
    .replace(/^./, (letter) => letter.toUpperCase());
  const preparationPercent = preparationProgress
    ? Math.min(100, Math.round((completedPreparationPhases.current.size / preparationProgress.totalPhases) * 100))
    : 0;

  return {
    cache: currentCache,
    cacheHealth: currentCacheHealth,
    cacheLoading,
    cacheRepairing,
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
    repairAndPrepare,
    refreshCache,
    setResourcePreset,
    setTextureStorage,
    stopPreparation,
  };
}
