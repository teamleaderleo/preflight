import { useCallback, useEffect, useState } from "react";
import { SPEED_RECORD_STORAGE_KEY } from "./desktopStorage";
import { startupImprovementPercent } from "./speedScoreboardFormat";
import type { DesktopBenchmarkComparison } from "./types";

export interface SpeedMeasurement {
  profileFingerprint: string;
  benchmarkIdentitySha256: string;
  measurementOnlyMs: number;
  optimizedMs: number;
  recordedAt: string;
}

/**
 * Controlled benchmark history kept locally on this computer.
 *
 * `personalBest` is the best measured percentage improvement and stays as the trophy even when a
 * later benchmark is disappointing. `latest` is always the newest controlled pair, so unfavorable
 * evidence remains visible instead of being hidden by the trophy.
 *
 * Earlier versions also multiplied benchmark savings by ordinary launches that only matched the
 * mod fingerprint. A benchmark identity binds more than that (install, launcher/runtime/settings,
 * exact Preflight build), so version 3 deliberately stops making that cumulative claim.
 */
export interface SpeedRecord {
  version: 3;
  personalBest: SpeedMeasurement;
  latest: SpeedMeasurement;
}

interface LegacySpeedRecordV2 extends SpeedMeasurement {
  version: 2;
  fastLaunches: number;
  bankedLaunches: number;
  bankedSavedMs: number;
}

export interface SpeedStanding {
  record: SpeedRecord | null;
  rememberBenchmark: (comparison: DesktopBenchmarkComparison | null) => void;
  /** Kept as a compatibility seam for the existing launch lifecycle; profile-only launches do not
   * count as benchmark-comparable evidence. */
  countFastLaunch: (profileFingerprint: string | null) => void;
  forget: () => void;
}

function isFiniteAbove(value: unknown, floor: number): value is number {
  return typeof value === "number" && Number.isFinite(value) && value > floor;
}

function isSha256(value: unknown): value is string {
  return typeof value === "string" && /^[0-9a-f]{64}$/.test(value);
}

function isMeasurement(value: unknown): value is SpeedMeasurement {
  if (!value || typeof value !== "object") return false;
  const measurement = value as Partial<SpeedMeasurement>;
  return isSha256(measurement.profileFingerprint)
    && isSha256(measurement.benchmarkIdentitySha256)
    && isFiniteAbove(measurement.measurementOnlyMs, 0)
    && isFiniteAbove(measurement.optimizedMs, 0)
    && typeof measurement.recordedAt === "string";
}

function measurementFromLegacy(parsed: Partial<LegacySpeedRecordV2>): SpeedMeasurement | null {
  const candidate = {
    profileFingerprint: parsed.profileFingerprint,
    benchmarkIdentitySha256: parsed.benchmarkIdentitySha256,
    measurementOnlyMs: parsed.measurementOnlyMs,
    optimizedMs: parsed.optimizedMs,
    recordedAt: parsed.recordedAt,
  };
  return isMeasurement(candidate) ? candidate : null;
}

export function readSpeedRecord(storage: Storage = window.localStorage): SpeedRecord | null {
  try {
    const raw = storage.getItem(SPEED_RECORD_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<SpeedRecord & LegacySpeedRecordV2>;
    if (parsed.version === 3 && isMeasurement(parsed.personalBest) && isMeasurement(parsed.latest)) {
      return parsed as SpeedRecord;
    }
    if (parsed.version === 2) {
      const measurement = measurementFromLegacy(parsed);
      if (measurement) {
        return { version: 3, personalBest: measurement, latest: measurement };
      }
    }
    storage.removeItem(SPEED_RECORD_STORAGE_KEY);
  } catch {
    // A locked-down or corrupted store costs the local history, nothing else.
  }
  return null;
}

function measurementFromComparison(comparison: DesktopBenchmarkComparison | null): SpeedMeasurement | null {
  const metric = comparison?.available ? comparison.metrics.processToMainMenuMs : undefined;
  const identity = comparison?.identity;
  if (!metric
    || !identity
    || !isSha256(identity.profileFingerprint)
    || !isSha256(identity.benchmarkIdentitySha256)
    || !isFiniteAbove(metric.measurementOnly, 0)
    || !isFiniteAbove(metric.optimized, 0)) return null;
  return {
    profileFingerprint: identity.profileFingerprint,
    benchmarkIdentitySha256: identity.benchmarkIdentitySha256,
    measurementOnlyMs: metric.measurementOnly,
    optimizedMs: metric.optimized,
    recordedAt: new Date().toISOString(),
  };
}

function isBetterMeasurement(candidate: SpeedMeasurement, current: SpeedMeasurement): boolean {
  return startupImprovementPercent(candidate.measurementOnlyMs, candidate.optimizedMs)
    > startupImprovementPercent(current.measurementOnlyMs, current.optimizedMs);
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
      // History remains correct for this session even if local storage cannot be written.
    }
  }, [record]);

  const rememberBenchmark = useCallback((comparison: DesktopBenchmarkComparison | null) => {
    const measurement = measurementFromComparison(comparison);
    if (!measurement) return;
    setRecord((previous) => ({
      version: 3,
      latest: measurement,
      personalBest: !previous || isBetterMeasurement(measurement, previous.personalBest)
        ? measurement
        : previous.personalBest,
    }));
  }, []);

  const countFastLaunch = useCallback((_profileFingerprint: string | null) => {
    // An ordinary launch currently exposes only a profile fingerprint. That is weaker than the
    // controlled benchmark identity, so it cannot truthfully accumulate benchmark-derived savings.
  }, []);

  const forget = useCallback(() => setRecord(null), []);

  return { record, rememberBenchmark, countFastLaunch, forget };
}
