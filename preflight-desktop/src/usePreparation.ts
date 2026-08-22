import { useCallback, useEffect, useRef, useState } from "react";
import {
  cancelPreparation,
  getCache,
  getCacheHealth,
  getCacheInspection,
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
  TextureStorage,
} from "./types";
import { listenWhileMounted } from "./tauriEvents";
import { startOperationReconciliation } from "./operationReconciliation";
import { errorMessage } from "./uiFormat";

export type { TextureStorage } from "./types";

/**
 * `minimal` prepares the exact profile index without building texture artifacts. The launch can
 * still use and learn content-addressed caches keyed from that index. It has no texture
 * storage plan to show: the engine skips that space gate, and `prepare --plan` refuses to describe
 * a textures-free preparation.
 */
export function storagePlanApplies(
  storage: TextureStorage,
): storage is Exclude<TextureStorage, "minimal"> {
  return storage !== "minimal";
}

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

type PreparationOwnership = "local" | "recovered";

export function isCurrentProfilePrepared(
  cache: CacheSnapshot | null,
  textureStorage: TextureStorage = "balanced",
): boolean {
  if (!cache?.currentProfileFingerprint) return false;
  return cache.profiles.some((profile) =>
    profile.current
    && profile.fingerprint === cache.currentProfileFingerprint
    && profile.indexBytes > 0
    && (textureStorage === "minimal" || profile.manifestBytes > 0));
}

export function preparationModeMatchesStorage(
  health: CacheHealth | null,
  textureStorage: TextureStorage,
): boolean {
  if (health?.status !== "ready") return false;
  return textureStorage === "minimal"
    ? health.preparedTextures === false
    : health.preparedTextures !== false;
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
  const [preparationProgressKnown, setPreparationProgressKnown] = useState(true);
  const completedPreparationPhases = useRef(new Set<string>());
  const localPreparationStarting = useRef(false);
  const localPreparationPid = useRef<number | null>(null);
  const recoveredPreparationPid = useRef<number | null>(null);
  const pendingPreparationObservedPid = useRef<number | null>(null);
  const pendingPreparationStartedPid = useRef<number | null>(null);
  const pendingPreparationStates = useRef(new Map<number, PreparationStateEvent>());
  const pendingPreparationProgress = useRef(new Map<number, PreparationProgressEvent[]>());
  const lastHandledTerminalPid = useRef<number | null>(null);
  const [preparationPlanEnvelope, setPreparationPlanEnvelope] = useState<PreparationPlanEnvelope | null>(null);
  const [preparationPlanLoading, setPreparationPlanLoading] = useState(false);
  const launchAfterPreparation = useRef(false);
  const [textureStorage, setTextureStorage] = useState<TextureStorage>("balanced");
  const inferredTextureStorageForGame = useRef<string | null>(null);
  const [resourcePreset, setResourcePreset] = useState<keyof typeof resourcePresets>("balanced");
  const gameRef = useRef(game);
  gameRef.current = game;

  const refreshCache = useCallback(async () => {
    if (!game) return;
    const request = ++cacheRequest.current;
    cacheRequestRoot.current = game;
    setCacheLoading(true);
    try {
      const inspection = isDesktopHost()
        ? await getCacheInspection(game)
        : await Promise.all([getCache(game), getCacheHealth(game)]).then(([cache, health]) => ({
          format: "starsector-preflight-cache-inspection-v1" as const,
          cache,
          health,
        }));
      if (request === cacheRequest.current) {
        setCache(inspection.cache);
        setCacheHealth(inspection.health);
      }
    } catch (error) {
      if (request === cacheRequest.current) {
        setCache(null);
        setCacheHealth(null);
        announce(errorMessage(error), "error");
      }
    } finally {
      if (request === cacheRequest.current) {
        cacheRequestRoot.current = null;
        setCacheInstallRoot(game);
        setCacheLoading(false);
      }
    }
  }, [announce, game]);

  const applyPreparationProgress = useCallback((payload: PreparationProgressEvent) => {
    if (payload.state === "completed") completedPreparationPhases.current.add(payload.phase);
    setPreparationProgress({ ...payload });
  }, []);

  const finishOwnedPreparation = useCallback((
    payload: PreparationStateEvent,
    ownership: PreparationOwnership,
  ) => {
    lastHandledTerminalPid.current = payload.pid;
    if (ownership === "local") {
      localPreparationPid.current = null;
      recoveredPreparationPid.current = null;
    } else {
      recoveredPreparationPid.current = null;
    }

    const localStillPending = ownership === "recovered"
      && (localPreparationStarting.current || localPreparationPid.current !== null);
    if (localStillPending) {
      setPreparationCancelling(false);
      if (localPreparationStarting.current) {
        completedPreparationPhases.current.clear();
        setPreparationProgress(null);
        setPreparationProgressKnown(false);
      }
      void refreshCache();
      return;
    }

    setPreparationProgressKnown(true);
    const shouldLaunch = ownership === "local" && launchAfterPreparation.current;
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
  }, [announce, launch, refreshCache]);

  const retireWithoutTerminal = useCallback((pid: number, ownership: PreparationOwnership) => {
    if (ownership === "local") {
      localPreparationPid.current = null;
    } else {
      recoveredPreparationPid.current = null;
    }

    const localStillPending = ownership === "recovered"
      && (localPreparationStarting.current || localPreparationPid.current !== null);
    if (localStillPending) {
      setPreparationCancelling(false);
      if (localPreparationStarting.current) {
        completedPreparationPhases.current.clear();
        setPreparationProgress(null);
        setPreparationProgressKnown(false);
      }
      void refreshCache();
      return;
    }

    launchAfterPreparation.current = false;
    setPreparationProgressKnown(true);
    setPreparing(false);
    setPreparationCancelling(false);
    setPreparationProgress(null);
    announce(ownership === "recovered"
      ? "Preparation finished while Preflight was reopening. Checking the current profile before launch."
      : "Preparation stopped. Live completion details were unavailable, so Preflight refreshed the current cache state.",
    ownership === "recovered" ? "info" : "warning");
    void refreshCache();
    if (lastHandledTerminalPid.current === pid) lastHandledTerminalPid.current = null;
  }, [announce, refreshCache]);

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
  useEffect(() => {
    if (!game) {
      inferredTextureStorageForGame.current = null;
      return;
    }
    if (inferredTextureStorageForGame.current === game || currentCacheHealth?.status !== "ready") return;
    inferredTextureStorageForGame.current = game;
    if (currentCacheHealth.preparedTextures === false) setTextureStorage("minimal");
  }, [currentCacheHealth, game]);
  const profilePrepared = isCurrentProfilePrepared(currentCache, textureStorage)
    && preparationModeMatchesStorage(currentCacheHealth, textureStorage);
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
      && storagePlanApplies(textureStorage)
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
          announce(errorMessage(error), "error");
        }
      })
      .finally(() => {
        if (!cancelled) setPreparationPlanLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [announce, cacheInstallRoot, cacheLoading, game, optimizationPreset, profilePrepared, resources.workers, showStoragePlan, textureStorage]);

  const runPreparation = async (
    launchWhenReady = false,
    forcePlan = false,
    requestedStorage: TextureStorage = textureStorage,
  ) => {
    if (!game) return;
    try {
      // A textures-free preparation writes about eleven megabytes of metadata. The engine refuses
      // to plan one and runs it without the space gate, so asking for a plan here would fail the
      // preparation on the one setting a user picks precisely because they are short of room.
      if (requestedStorage !== textureStorage) {
        setTextureStorage(requestedStorage);
        setPreparationPlanEnvelope(null);
      }
      let plan = forcePlan || !storagePlanApplies(requestedStorage) ? null : preparationPlan;
      if (!plan && storagePlanApplies(requestedStorage)) {
        setPreparationPlanLoading(true);
        plan = await getPreparationPlan(game, requestedStorage, resources.workers);
        setPreparationPlanEnvelope({
          game,
          profileFingerprint: plan.profileFingerprint,
          textureStorage: requestedStorage,
          workers: resources.workers,
          plan,
        });
      }
      if (plan && !plan.safeToPrepare) {
        announce(plan.refusalReason ?? "Preparation was refused because its storage requirement could not be bounded safely.", "warning");
        return;
      }
      setPreparationProgressKnown(true);
      launchAfterPreparation.current = launchWhenReady;
      completedPreparationPhases.current.clear();
      setPreparationProgress(null);
      setPreparationCancelling(false);
      setPreparing(true);
      announce(launchWhenReady
        ? "Preparing the exact current profile. Starsector will open when it’s ready."
        : "Preparing the exact current profile… You can leave this window open.");
      localPreparationStarting.current = true;
      localPreparationPid.current = null;
      lastHandledTerminalPid.current = null;
      pendingPreparationObservedPid.current = null;
      pendingPreparationStartedPid.current = null;
      pendingPreparationStates.current.clear();
      pendingPreparationProgress.current.clear();

      const started = await startPreparation(game, requestedStorage, resources.workers, resources.memoryMib);
      localPreparationStarting.current = false;
      localPreparationPid.current = started.pid;
      if (recoveredPreparationPid.current === started.pid) recoveredPreparationPid.current = null;
      setPreparationProgressKnown(true);

      const bufferedProgress = pendingPreparationProgress.current.get(started.pid) ?? [];
      for (const progress of bufferedProgress) applyPreparationProgress(progress);
      const bufferedState = pendingPreparationStates.current.get(started.pid);
      pendingPreparationObservedPid.current = null;
      pendingPreparationStartedPid.current = null;
      pendingPreparationStates.current.clear();
      pendingPreparationProgress.current.clear();

      if (bufferedState
          && (bufferedState.state === "finished" || bufferedState.state === "cancelled")) {
        finishOwnedPreparation(bufferedState, "local");
      }

      if (!isDesktopHost()) {
        localPreparationPid.current = null;
        setPreparing(false);
        announce("Preview preparation complete.", "success");
        await refreshCache();
        if (launchAfterPreparation.current) {
          launchAfterPreparation.current = false;
          await launch();
        }
      }
    } catch (error) {
      const observedPid = pendingPreparationStartedPid.current
        ?? pendingPreparationObservedPid.current;
      localPreparationStarting.current = false;
      localPreparationPid.current = null;
      if (recoveredPreparationPid.current === null && observedPid !== null) {
        recoveredPreparationPid.current = observedPid;
      }
      pendingPreparationObservedPid.current = null;
      pendingPreparationStartedPid.current = null;
      pendingPreparationStates.current.clear();
      pendingPreparationProgress.current.clear();
      launchAfterPreparation.current = false;
      if (recoveredPreparationPid.current !== null) {
        completedPreparationPhases.current.clear();
        setPreparationProgress(null);
        setPreparationProgressKnown(false);
        setPreparing(true);
      } else {
        setPreparationProgressKnown(true);
        setPreparing(false);
      }
      announce(errorMessage(error), "error");
    } finally {
      setPreparationPlanLoading(false);
    }
  };

  const prepare = async (
    launchWhenReady = false,
    requestedStorage: TextureStorage = textureStorage,
  ) => runPreparation(launchWhenReady, false, requestedStorage);

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
        preparedTextures: null,
        issues: [],
        repairBytes: 0,
        repairFiles: 0,
      });
      setPreparationPlanEnvelope(null);
      announce(`Removed ${repair.files.toLocaleString()} damaged profile artifact${repair.files === 1 ? "" : "s"}. Rebuilding prepared data now.`, "warning");
      await runPreparation(launchWhenReady, true);
    } catch (error) {
      if (gameRef.current === repairGame) announce(errorMessage(error), "error");
    } finally {
      if (gameRef.current === repairGame) setCacheRepairing(false);
    }
  };

  useEffect(() => {
    if (!isDesktopHost()) return;
    let stopReconciliation: () => void = () => undefined;
    let previousPid: number | null | undefined;
    let firstMissingPid: number | null = null;

    const startReconciliation = () => {
      stopReconciliation();
      previousPid = undefined;
      firstMissingPid = null;
      stopReconciliation = startOperationReconciliation({
        apply: (operation) => {
          if (operation.preparationPid !== null) {
            const firstRead = previousPid === undefined;
            const newGeneration = previousPid !== operation.preparationPid;
            if (newGeneration && lastHandledTerminalPid.current === operation.preparationPid) {
              lastHandledTerminalPid.current = null;
            }
            previousPid = operation.preparationPid;
            firstMissingPid = null;
            setPreparing(true);

            if (localPreparationPid.current === operation.preparationPid) {
              setPreparationProgressKnown(true);
              return;
            }
            if (recoveredPreparationPid.current === operation.preparationPid) {
              setPreparationProgressKnown(false);
              return;
            }
            if (localPreparationStarting.current) {
              pendingPreparationObservedPid.current = operation.preparationPid;
              completedPreparationPhases.current.clear();
              setPreparationProgress(null);
              setPreparationProgressKnown(false);
              return;
            }

            recoveredPreparationPid.current = operation.preparationPid;
            setPreparationProgressKnown(false);
            launchAfterPreparation.current = false;
            completedPreparationPhases.current.clear();
            setPreparationProgress(null);
            setPreparationCancelling(false);
            if (firstRead) {
              announce("Reconnected to profile preparation. Starsector stays closed when it finishes; launch from Home when you’re ready.");
            }
            return;
          }

          if (previousPid !== null && previousPid !== undefined) {
            const endedPid = previousPid;
            if (lastHandledTerminalPid.current === endedPid) {
              lastHandledTerminalPid.current = null;
              previousPid = null;
              firstMissingPid = null;
              return;
            }
            // Native clears the coordinator immediately before it emits the terminal event. Give the
            // event one polling interval to arrive before falling back to state-only reconciliation.
            if (firstMissingPid !== endedPid) {
              firstMissingPid = endedPid;
              return;
            }
            previousPid = null;
            firstMissingPid = null;
            if (localPreparationStarting.current
                && (pendingPreparationObservedPid.current === endedPid
                  || pendingPreparationStartedPid.current === endedPid)) {
              pendingPreparationObservedPid.current = null;
              return;
            }
            if (localPreparationPid.current === endedPid) {
              retireWithoutTerminal(endedPid, "local");
              return;
            }
            if (recoveredPreparationPid.current === endedPid) {
              retireWithoutTerminal(endedPid, "recovered");
              return;
            }
          } else {
            previousPid = null;
            firstMissingPid = null;
          }
        },
        isActive: () => previousPid !== null && previousPid !== undefined,
        onError: (pollError) => announce(`Could not refresh native preparation state: ${pollError}`, "error"),
      });
    };

    // The operation coordinator is authoritative across renderer restarts. The first read is cheap
    // and retires immediately when there is no active preparation; when one exists it also provides
    // a polling fallback in case the terminal event happened before this renderer subscribed.
    startReconciliation();
    const stopListening = listenWhileMounted<PreparationStateEvent>("prepare-state", ({ payload }) => {
      if (payload.state === "started") {
        if (localPreparationStarting.current) pendingPreparationStartedPid.current = payload.pid;
        return;
      }
      if (payload.state === "cancelling") {
        if (payload.pid === localPreparationPid.current
            || payload.pid === recoveredPreparationPid.current) {
          setPreparationCancelling(true);
          announce("Stopping preparation safely…");
        }
        return;
      }
      if (payload.state !== "finished" && payload.state !== "cancelled") return;

      if (payload.pid === localPreparationPid.current) {
        finishOwnedPreparation(payload, "local");
        return;
      }
      if (payload.pid === recoveredPreparationPid.current) {
        finishOwnedPreparation(payload, "recovered");
        return;
      }
      if (localPreparationStarting.current) {
        if (pendingPreparationStates.current.size < 4
            || pendingPreparationStates.current.has(payload.pid)) {
          pendingPreparationStates.current.set(payload.pid, payload);
        }
      }
    }, (error) => {
      announce(`Live preparation updates were interrupted: ${error}. Preflight is checking native state directly.`, "warning");
      startReconciliation();
    });
    return () => {
      stopListening();
      stopReconciliation();
    };
  }, [announce, finishOwnedPreparation, retireWithoutTerminal]);

  useEffect(() => {
    if (!isDesktopHost()) return;
    return listenWhileMounted<PreparationProgressEvent>("prepare-progress", ({ payload }) => {
      if (payload.pid === localPreparationPid.current) {
        applyPreparationProgress(payload);
        return;
      }
      if (payload.pid === recoveredPreparationPid.current) {
        // A renderer that reconnects mid-run cannot know which earlier phases completed, so partial
        // live events must not turn recovered progress into a fabricated percentage.
        return;
      }
      if (localPreparationStarting.current) {
        if (!pendingPreparationProgress.current.has(payload.pid)
            && pendingPreparationProgress.current.size >= 4) return;
        const buffered = pendingPreparationProgress.current.get(payload.pid) ?? [];
        if (buffered.length < 64) buffered.push({ ...payload });
        pendingPreparationProgress.current.set(payload.pid, buffered);
      }
    }, (error) => announce(`Could not observe preparation progress: ${error}`, "error"));
  }, [announce, applyPreparationProgress]);

  const stopPreparation = async () => {
    if (!preparing || preparationCancelling) return;
    setPreparationCancelling(true);
    launchAfterPreparation.current = false;
    announce("Stopping preparation safely…");
    try {
      const requested = await cancelPreparation();
      if (!requested) {
        if (localPreparationStarting.current) {
          setPreparationCancelling(false);
          return;
        }
        localPreparationPid.current = null;
        recoveredPreparationPid.current = null;
        setPreparationProgressKnown(true);
        setPreparing(false);
        setPreparationCancelling(false);
        announce("Preparation had already finished.");
      }
    } catch (error) {
      setPreparationCancelling(false);
      announce(errorMessage(error), "error");
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
  const preparationPercent = preparationProgressKnown
    ? preparationProgress
      ? Math.min(100, Math.round((completedPreparationPhases.current.size / preparationProgress.totalPhases) * 100))
      : 0
    : null;

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
