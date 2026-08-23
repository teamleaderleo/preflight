import { useEffect, useRef } from "react";
import {
  applyCacheCleanup,
  applyEvidenceCleanup,
  getCacheCleanup,
  isDesktopHost,
} from "./bridge";

export const AUTOMATIC_CACHE_LIMIT_BYTES = 12 * 1024 * 1024 * 1024;
// Housekeeping starts after the opening interaction window. It is bounded maintenance, not part of
// deciding whether Home can launch, and each engine request briefly starts a child JVM.
export const AUTOMATIC_MAINTENANCE_SETTLE_MS = 15_000;

interface AutomaticMaintenanceOptions {
  game?: string;
  cacheBytes?: number;
  onCacheCleaned?: () => void;
}

/** Keeps routine local evidence bounded without turning maintenance into another errand. */
export function useAutomaticMaintenance(
  enabled: boolean,
  epoch: number,
  options: AutomaticMaintenanceOptions = {},
) {
  const lastEvidenceEpoch = useRef<number | null>(null);
  const lastCacheEpoch = useRef<number | null>(null);

  useEffect(() => {
    if (!enabled || lastEvidenceEpoch.current === epoch || !isDesktopHost()) return;
    const timer = window.setTimeout(() => {
      lastEvidenceEpoch.current = epoch;
      void applyEvidenceCleanup().catch(() => {
        // Maintenance is optional. The explicit cleanup review is the recovery path.
      });
    }, AUTOMATIC_MAINTENANCE_SETTLE_MS);
    return () => window.clearTimeout(timer);
  }, [enabled, epoch]);

  useEffect(() => {
    const { game, cacheBytes, onCacheCleaned } = options;
    const overCacheLimit = cacheBytes !== undefined && cacheBytes > AUTOMATIC_CACHE_LIMIT_BYTES;

    if (!enabled
      || !game
      || !overCacheLimit
      || lastCacheEpoch.current === epoch
      || !isDesktopHost()) return;
    const timer = window.setTimeout(() => {
      lastCacheEpoch.current = epoch;
      void getCacheCleanup(game).then(async (plan) => {
        if (!plan.safe || plan.bytes === 0) return;
        const applied = await applyCacheCleanup(game);
        if (applied.applied && applied.bytes > 0) onCacheCleaned?.();
      }).catch(() => {
        // Unsafe, busy, or unreadable data stays put. Review cleanup remains the visible retry.
      });
    }, AUTOMATIC_MAINTENANCE_SETTLE_MS);
    return () => window.clearTimeout(timer);
  }, [enabled, epoch, options.cacheBytes, options.game, options.onCacheCleaned]);
}
