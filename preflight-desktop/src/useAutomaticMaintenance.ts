import { useEffect, useRef } from "react";
import {
  applyCacheCleanup,
  applyEvidenceCleanup,
  getCacheCleanup,
  isDesktopHost,
} from "./bridge";

export const AUTOMATIC_CACHE_LIMIT_BYTES = 12 * 1024 * 1024 * 1024;
export const AUTOMATIC_FREE_SPACE_THRESHOLD_BYTES = 5 * 1024 * 1024 * 1024;

interface AutomaticMaintenanceOptions {
  game?: string;
  cacheBytes?: number;
  freeBytes?: number;
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
    }, 1_500);
    return () => window.clearTimeout(timer);
  }, [enabled, epoch]);

  useEffect(() => {
    const { game, cacheBytes, freeBytes, onCacheCleaned } = options;
    const underSpacePressure = freeBytes !== undefined
      && freeBytes <= AUTOMATIC_FREE_SPACE_THRESHOLD_BYTES
      && (cacheBytes ?? 0) > 0;
    const overCacheLimit = cacheBytes !== undefined && cacheBytes > AUTOMATIC_CACHE_LIMIT_BYTES;

    if (!enabled
      || !game
      || (!overCacheLimit && !underSpacePressure)
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
    }, 1_500);
    return () => window.clearTimeout(timer);
  }, [enabled, epoch, options.cacheBytes, options.freeBytes, options.game, options.onCacheCleaned]);
}
