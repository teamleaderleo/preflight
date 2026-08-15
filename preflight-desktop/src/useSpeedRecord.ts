import { useCallback, useEffect, useState } from "react";
import { SPEED_RECORD_STORAGE_KEY } from "./desktopStorage";
import type { DesktopBenchmarkComparison } from "./types";

/**
 * The best startup measurement taken on this computer, and what has been launched since.
 *
 * <p>A benchmark result used to live only in the event that delivered it, so the one number this
 * project exists to move was gone the next time the app opened. Somebody who spent several
 * minutes measuring had nothing to show for it an hour later.
 *
 * <p>`fastLaunches` counts optimized launches taken since this record was measured, so the
 * running total is only ever multiplied by a figure measured on the same machine. When a newer
 * benchmark replaces this one, the total earned under the old figure is banked rather than
 * recomputed -- those launches really were that much faster at the time, and rewriting history
 * with a newer multiplier would make the number fiction.
 */
export interface SpeedRecord {
  version: 1;
  profileFingerprint: string;
  benchmarkIdentitySha256: string;
  measurementOnlyMs: number;
  optimizedMs: number;
  recordedAt: string;
  fastLaunches: number;
  bankedSavedMs: number;
}

export interface SpeedStanding {
  record: SpeedRecord | null;
  /** Milliseconds saved by a single launch, at the measured figures. */
  savedPerLaunchMs: number;
  /** Every millisecond saved since the first measurement, including banked earlier records. */
  totalSavedMs: number;
  /** Normal launch divided by optimized launch: the "3.4x faster" figure. */
  multiplier: number | null;
  rememberBenchmark: (comparison: DesktopBenchmarkComparison | null) => void;
  countFastLaunch: (profileFingerprint: string | null) => void;
  forget: () => void;
}

function isFiniteAbove(value: unknown, floor: number): value is number {
  return typeof value === "number" && Number.isFinite(value) && value > floor;
}

function isSha256(value: unknown): value is string {
  return typeof value === "string" && /^[0-9a-f]{64}$/.test(value);
}

export function readSpeedRecord(storage: Storage = window.localStorage): SpeedRecord | null {
  try {
    const raw = storage.getItem(SPEED_RECORD_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<SpeedRecord>;
    // A zero or negative duration cannot produce a multiplier, and a stored record that would
    // divide by zero is worse than no record at all.
    const usable = parsed.version === 1
      && isSha256(parsed.profileFingerprint)
      && isSha256(parsed.benchmarkIdentitySha256)
      && isFiniteAbove(parsed.measurementOnlyMs, 0)
      && isFiniteAbove(parsed.optimizedMs, 0)
      && typeof parsed.recordedAt === "string"
      && isFiniteAbove(parsed.fastLaunches, -1)
      && isFiniteAbove(parsed.bankedSavedMs, -1);
    if (usable) return parsed as SpeedRecord;
    storage.removeItem(SPEED_RECORD_STORAGE_KEY);
  } catch {
    // A locked-down or corrupted store costs the scoreboard, nothing else.
  }
  return null;
}

export function savedPerLaunchMs(record: SpeedRecord | null): number {
  if (!record) return 0;
  return Math.max(0, record.measurementOnlyMs - record.optimizedMs);
}

export function totalSavedMs(record: SpeedRecord | null): number {
  if (!record) return 0;
  return record.bankedSavedMs + record.fastLaunches * savedPerLaunchMs(record);
}

export function useSpeedRecord(): SpeedStanding {
  const [record, setRecord] = useState<SpeedRecord | null>(() => readSpeedRecord());

  useEffect(() => {
    try {
      if (record) {
        window.localStorage.setItem(SPEED_RECORD_STORAGE_KEY, JSON.stringify(record));
      } else {
        window.localStorage.removeItem(SPEED_RECORD_STORAGE_KEY);
      }
    } catch {
      // The scoreboard stays correct for this session even if it cannot be written down.
    }
  }, [record]);

  const rememberBenchmark = useCallback((comparison: DesktopBenchmarkComparison | null) => {
    const metric = comparison?.available ? comparison.metrics.processToMainMenuMs : undefined;
    const identity = comparison?.identity;
    if (!metric
      || !identity
      || !isSha256(identity.profileFingerprint)
      || !isSha256(identity.benchmarkIdentitySha256)
      || !isFiniteAbove(metric.measurementOnly, 0)
      || !isFiniteAbove(metric.optimized, 0)) return;
    setRecord((previous) => ({
      version: 1,
      profileFingerprint: identity.profileFingerprint,
      benchmarkIdentitySha256: identity.benchmarkIdentitySha256,
      measurementOnlyMs: metric.measurementOnly,
      optimizedMs: metric.optimized,
      recordedAt: new Date().toISOString(),
      fastLaunches: 0,
      bankedSavedMs: totalSavedMs(previous),
    }));
  }, []);

  const countFastLaunch = useCallback((profileFingerprint: string | null) => {
    setRecord((previous) => previous?.profileFingerprint === profileFingerprint
      ? { ...previous, fastLaunches: previous.fastLaunches + 1 }
      : previous);
  }, []);

  const forget = useCallback(() => setRecord(null), []);

  return {
    record,
    savedPerLaunchMs: savedPerLaunchMs(record),
    totalSavedMs: totalSavedMs(record),
    multiplier: record ? record.measurementOnlyMs / record.optimizedMs : null,
    rememberBenchmark,
    countFastLaunch,
    forget,
  };
}
